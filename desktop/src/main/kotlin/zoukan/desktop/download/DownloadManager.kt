package zoukan.desktop.download

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.domain.toSChapter
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A chapter in the download queue. */
class Download(val manga: Manga, val chapter: Chapter) {
    enum class State { QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

    val state = MutableStateFlow(State.QUEUED)

    /** Pages done and the page count; the count is 0 until the page list has loaded */
    val progress = MutableStateFlow(0 to 0)

    @Volatile
    var error: String? = null
}

/**
 * The download queue and the downloaded chapters: the desktop app's DownloadManager, Downloader and
 * DownloadCache in one. Chapters download from up to `parallelSourceLimit` sources at once, one
 * chapter at a time per source. The queue is saved, so it survives a restart.
 */
class DownloadManager(
    private val dirs: ZoukanDirs,
    private val paths: DownloadPaths,
    private val sourceManager: SourceManager,
    private val preferences: DownloadPreferences,
    private val password: CbzPassword,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val getCategories: GetCategories,
    /**
     * The other entries whose downloads go to [Manga]'s folder, with their chapters (see
     * [sharesDownloadFolder]); a delete keeps their chapters' files. Null looks them up in the
     * database; tests without one pass their own.
     */
    coOwners: (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = null,
    /**
     * Makes the downloader of one chapter from its page list, image URL and image calls and the
     * number of pages fetched at once; tests pass one whose file writes fail.
     */
    private val newDownloader: (
        pageList: suspend () -> List<Page>,
        imageUrl: suspend (Page) -> String,
        image: suspend (Page) -> ByteArray,
        parallelPages: Int,
    ) -> ChapterDownloader = { pageList, imageUrl, image, parallelPages -> ChapterDownloader(pageList, imageUrl, image, parallelPages) },
    /** The clock [index] checks its folders' age by; tests pass one they move on. */
    indexClock: () -> Long = System::currentTimeMillis,
    /** The most bytes a page's response may have, as in the reader; tests pass a small one. */
    private val maxPageBytes: Long = zoukan.desktop.image.PageSizeLimits.MAX_PAGE_BYTES,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val queueFile get() = File(dirs.root, "downloads-queue.json")
    private val mutex = Mutex()

    // ZOUKAN -->
    // Not every test registers it; without it there is no data saver
    private val sourcePreferences by lazy { runCatching { Injekt.get<SourcePreferences>() }.getOrNull() }

    /** [coOwners], or the database's; none when there is no database (tests that pass none). */
    private val coOwnerLookup: (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? by lazy {
        coOwners ?: databaseCoOwners()
    }
    // ZOUKAN <--

    private val _queue = MutableStateFlow<List<Download>>(emptyList())
    val queue: StateFlow<List<Download>> = _queue.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Bumped whenever chapters are downloaded or deleted, so screens check again. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /**
     * The download run and the run being cancelled, guarded by [lock]. A run ends itself, under
     * the lock, once it finds nothing queued, so [start] never sees a run that is about to end
     * and leaves new chapters waiting.
     */
    private val lock = Any()
    private var job: Job? = null
    private val stopping = LinkedHashSet<Job>()
    private val stoppingWaiters = ConcurrentHashMap<Job, Job>()

    private suspend fun awaitStopping(jobs: List<Job> = synchronized(lock) { stopping.toList() }) {
        val waiter = currentCoroutineContext().job
        for (stoppingJob in jobs) {
            stoppingWaiters[waiter] = stoppingJob
            try {
                stoppingJob.join()
            } finally {
                stoppingWaiters.remove(waiter, stoppingJob)
            }
        }
    }

    internal fun activeJob(): Job? = synchronized(lock) { job }

    internal fun isWaitingFor(waiter: Job, stoppingJob: Job): Boolean {
        fun contains(root: Job, target: Job): Boolean = root === target || root.children.any { contains(it, target) }
        return stoppingWaiters.any { (waiting, target) -> target === stoppingJob && contains(waiter, waiting) }
    }

    internal fun stoppingCount(): Int = synchronized(lock) { stopping.size }

    /**
     * One lock per entry, shared by the clean-up and the download writer, so clean-up never deletes the
     * files of a download that is starting or running. Only the clean-up holds several at once (the
     * owners of a shared folder), always taken in id order; nothing else holds more than one.
     */
    // Internal so a test can put in a lock that records who asks for it: a Mutex does not show its waiters
    internal val entryLocks = ConcurrentHashMap<Long, Mutex>()
    internal fun entryLock(id: Long) = entryLocks.getOrPut(id) { Mutex() }

    /**
     * One lock per entry held across a title change's rename of its download folders, the save of
     * the new title and a move back ([withRenameLock]), so two refreshes of one entry never move
     * its folders at once. It is a lock of its own, not [entryLock]: a refresh does not wait for a
     * chapter that is downloading. Nothing takes another lock while waiting for it, and nothing
     * under it takes an entry lock or another rename lock, so it cannot deadlock with them.
     */
    // Internal so a test can put in a lock that records who asks for it
    internal val renameLocks = ConcurrentHashMap<Long, Mutex>()

    /** Runs [block] holding entry [mangaId]'s rename lock (see [renameLocks]). */
    suspend fun <T> withRenameLock(mangaId: Long, block: suspend () -> T): T =
        renameLocks.getOrPut(mangaId) { Mutex() }.withLock { block() }

    /** What is in each entry's download folder, read once and checked again by its modified time */
    internal val index = DownloadIndex(clock = indexClock)

    /** How many folders were listed, for the benchmark. */
    internal val foldersRead: Int get() = index.listingsRead

    // Downloaded chapters --------------------------------------------------------------------

    /** When [index] was last emptied, for the download cache renew interval. */
    @Volatile
    private var lastRenew = System.currentTimeMillis()

    private val root: File get() = paths.downloadsRoot

    /**
     * Reads the download folders again once "Download cache renew interval" (KMK) has passed. Folders
     * are also checked by their modified time every few seconds (see [DownloadIndex]), so this is
     * only an upper bound. "Manual" (-1) only renews from Reindex downloads.
     */
    private fun renewIfDue() {
        val hours = preferences.downloadCacheRenewInterval().get()
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastRenew < hours * 60L * 60 * 1000) return
        lastRenew = now
        index.clear()
        _changes.update { it + 1 }
    }

    /**
     * The chapter's download, a folder with files in it or a CBZ with bytes in it, in the entry's
     * folder (or under the name the other non-ASCII setting gives it). Reads the folder on a cache
     * miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun findChapter(manga: Manga, chapter: Chapter): File? {
        renewIfDue()
        return findIn(root, dirsOf(manga), chapter)
    }

    private fun findIn(root: File, dirs: List<File>, chapter: Chapter): File? {
        val names = paths.chapterNameCandidates(chapter)
        for (dir in dirs) {
            index.findValid(root, dir, names)?.let { return File(dir, it) }
        }
        return null
    }

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun isDownloaded(manga: Manga, chapter: Chapter): Boolean = findChapter(manga, chapter) != null

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> {
        renewIfDue()
        // The folders and the downloads folder once per entry, not per chapter
        val root = root
        val dirs = dirsOf(manga)
        return chapters.filter { findIn(root, dirs, it) != null }.mapTo(HashSet()) { it.id }
    }

    /**
     * How many chapters of [manga] are downloaded, for the library's download badge and filter:
     * folders with files and CBZs with bytes, a chapter with both counted once; unfinished
     * downloads, covers and other files are not chapters.
     * Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun downloadCount(manga: Manga): Int {
        renewIfDue()
        val root = root
        return dirsOf(manga).flatMapTo(HashSet()) { index.chapterKeys(root, it) }.size
    }

    /**
     * [downloadCount] of each of [mangas], for the library's badges and the statistics: the folders
     * are read on several threads at once (the common pool, one per core), as a first read is mostly
     * waiting for the disk. An entry whose folder cannot be read counts 0.
     * Reads the folders on a cache miss: call off the UI thread.
     */
    fun downloadCounts(mangas: List<Manga>): Map<Long, Int> {
        renewIfDue()
        val counts = ConcurrentHashMap<Long, Int>()
        mangas.distinctBy { it.id }.parallelStream().forEach { manga ->
            counts[manga.id] = try {
                downloadCount(manga)
            } catch (e: RuntimeException) {
                logcat(LogPriority.WARN, e) { "Counting the downloads of ${manga.ogTitle} failed" }
                0
            }
        }
        return counts
    }

    /**
     * Whether the downloads of [a] and [b] go to the same folder: sources with the same name and
     * language, and titles that give the same folder name. Deleting one's chapters there could then
     * delete the other's.
     */
    fun sharesDownloadFolder(a: Manga, b: Manga): Boolean {
        fun keys(m: Manga) = paths.mangaDirs(m).mapTo(HashSet()) { index.key(root, it.parentFile.name) + "/" + index.key(root, it.name) }
        return keys(a).any { it in keys(b) }
    }

    /**
     * The entry's download folders ([DownloadPaths.mangaDirs]) as named on disk: a source or entry
     * folder named in another Unicode form or case is the same folder (see [DownloadIndex.resolve]).
     */
    private fun dirsOf(manga: Manga): List<File> {
        val root = root
        return paths.mangaDirs(manga).map { index.resolve(root, it) }.distinct()
    }

    /** The names on disk of every copy of [chapter] in [dir], complete or not, unfinished ones included. */
    private fun namesOnDisk(dir: File, chapter: Chapter): List<String> {
        val candidates = paths.validChapterNames(chapter) +
            paths.validChapterNames(chapter).map { it + DownloadPaths.TMP_SUFFIX }
        return index.matching(root, dir, candidates)
    }

    /** [namesOnDisk] of each of [chapters]. */
    private fun namesOnDisk(dir: File, chapters: List<Chapter>): Set<String> =
        chapters.flatMapTo(HashSet()) { namesOnDisk(dir, it) }

    /**
     * Gives a downloaded chapter the name its new name or scanlator calls for, as Android's
     * renameChapter after a source sync, so the download keeps showing and Clean up downloaded
     * chapters does not take it for an unknown file.
     *
     * Never replaces anything: when a download of the new name exists, or the move fails, the
     * download keeps its old name and this returns false. True when renamed or nothing to rename.
     */
    fun renameChapter(manga: Manga, oldChapter: Chapter, newChapter: Chapter): Boolean {
        val dir = index.resolve(root, paths.mangaDir(manga))
        if (!dir.isDirectory) return true
        // Assume there's only 1 version of the chapter name formats present
        index.invalidate(dir)
        val oldDownload = index.matching(root, dir, paths.validChapterNames(oldChapter))
            .map { File(dir, it) }
            .firstOrNull { !it.name.endsWith(DownloadPaths.TMP_SUFFIX) } ?: return true

        var newName = paths.chapterDirName(newChapter)
        if (oldDownload.isFile && oldDownload.extension == "cbz") newName += ".cbz"
        if (oldDownload.name == newName) return true

        val target = File(dir, newName)
        if (target.exists() && !runCatching { Files.isSameFile(oldDownload.toPath(), target.toPath()) }.getOrDefault(false)) {
            logcat(LogPriority.WARN) { "Not renaming ${oldDownload.name} to $newName: a download of that name exists" }
            return false
        }
        return try {
            // No REPLACE_EXISTING: a target created in between makes the move fail
            Files.move(oldDownload.toPath(), target.toPath())
            invalidate(dir)
            true
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Could not rename downloaded chapter: ${oldDownload.name}" }
            false
        }
    }

    /** A download folder a rename moved: [from] is where it was, [to] where it is now. */
    data class FolderMove(val from: File, val to: File)

    /**
     * Moves an entry's download folders to the names [newTitle] gives them, as Android's renameManga
     * ([renameMangaFolders]). True when they moved or there was nothing to move.
     */
    suspend fun renameManga(manga: Manga, newTitle: String): Boolean = renameMangaFolders(manga, newTitle) != null

    /**
     * Moves each of [manga]'s download folders to the name [newTitle] gives a folder of its kind
     * ([DownloadPaths.existingMangaDirRenames]), as Android's renameManga: an entry can have several
     * (downloads made before and after "Disallow non-ASCII filenames" changed, Android's name beside
     * the desktop's).
     *
     * All or none, and never over anything on disk: nothing moves when a chapter of the entry is
     * queued, when a folder is also another entry's ([sharesDownloadFolder]), when a folder of a new
     * name exists, or when two folders would get the same name (their chapters would have to be
     * merged). A move that fails puts the folders moved before it back. When several folders, or a
     * shared one, keep the entry's title, the user is told once per entry and title in a run.
     *
     * @return the moves made, empty when there was nothing to move; null when nothing moved
     */
    suspend fun renameMangaFolders(manga: Manga, newTitle: String): List<FolderMove>? = withContext(Dispatchers.IO) {
        val moves = paths.existingMangaDirRenames(manga.source, manga.ogTitle, newTitle)
            .map { (folder, name) -> FolderMove(folder, File(folder.parentFile, name)) }
            // Already the new name; or a case-only change on a disk that ignores case, which names the same folder
            .filterNot { it.from.name == it.to.name || !it.from.exists() || isSameFolder(it.from, it.to) }
        if (moves.isEmpty()) return@withContext emptyList()
        if (_queue.value.any { it.manga.id == manga.id }) {
            logcat(LogPriority.WARN) { "Not renaming the download folders of the entry: chapters of the entry are queued" }
            return@withContext null
        }
        val others = try {
            coOwnerLookup?.invoke(manga).orEmpty().map { it.first }.filter { it.id != manga.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without knowing who else uses the folder, it does not move
            logcat(LogPriority.WARN, e) { "Not renaming the download folders of the entry: the entries sharing them could not be read" }
            return@withContext null
        }
        if (others.isNotEmpty()) {
            logcat(LogPriority.WARN) { "Not renaming the download folders of the entry: another entry's downloads are in them too" }
            warnRenameOnce(manga.id, newTitle) {
                localized(DMR.strings.download_folder_shared_not_renamed_text, manga.ogTitle, newTitle, moves.first().from.name)
            }
            return@withContext null
        }
        val targets = moves.map { folderKey(it.to) }
        val taken = moves.filter { Files.exists(it.to.toPath(), LinkOption.NOFOLLOW_LINKS) }
        if (taken.isNotEmpty() || targets.distinct().size != targets.size) {
            logcat(LogPriority.WARN) {
                "Not renaming the download folders of the entry: " +
                    if (taken.isNotEmpty()) "a folder named ${taken.first().to.name} exists" else "two folders would get one name"
            }
            // One folder whose new name is taken keeps its title quietly, as before; several are told
            if (moves.size > 1) {
                warnRenameOnce(manga.id, newTitle) {
                    localized(DMR.strings.download_folders_not_renamed_text, manga.ogTitle, newTitle, moves.size)
                }
            }
            return@withContext null
        }
        val done = ArrayList<FolderMove>()
        for (move in moves) {
            try {
                // No REPLACE_EXISTING: nothing already on disk is ever overwritten
                moveFolder(move.from.toPath(), move.to.toPath())
                done += move
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not rename the download folder ${move.from.name} to ${move.to.name}" }
                moveBack(done)
                return@withContext null
            } finally {
                invalidate(move.from)
                invalidate(move.to)
            }
        }
        done
    }

    /**
     * Moves back what [renameMangaFolders] moved, the last move first, when the new title could not
     * be saved: each folder to the name it had, never over anything on disk.
     *
     * @return whether every folder is back
     */
    suspend fun moveFoldersBack(moves: List<FolderMove>): Boolean = withContext(Dispatchers.IO) { moveBack(moves) }

    private fun moveBack(moves: List<FolderMove>): Boolean {
        var all = true
        for (move in moves.asReversed()) {
            try {
                moveFolder(move.to.toPath(), move.from.toPath())
            } catch (e: IOException) {
                // Its chapters are out of view until it has its name again: the user is told which
                logcat(LogPriority.ERROR, e) { "Could not move the download folder ${move.to.name} back to ${move.from.name}" }
                Notifications.post(
                    localized(DMR.strings.download_folders_not_renamed),
                    localized(DMR.strings.download_folder_not_moved_back_text, move.to.name, move.from.name),
                    Notifications.Kind.ERROR,
                )
                all = false
            } finally {
                invalidate(move.from)
                invalidate(move.to)
            }
        }
        return all
    }

    /** Moves a download folder; tests make it fail. Never replaces anything (no REPLACE_EXISTING). */
    @Volatile
    internal var moveFolder: (java.nio.file.Path, java.nio.file.Path) -> Unit = { from, to -> Files.move(from, to) }

    /** The entries and titles whose folders could not follow a new title and the user was told about, in this run. */
    private val renameWarned = ConcurrentHashMap.newKeySet<Pair<Long, String>>()

    private fun warnRenameOnce(mangaId: Long, newTitle: String, text: () -> String) {
        if (!renameWarned.add(mangaId to newTitle)) return
        Notifications.post(localized(DMR.strings.download_folders_not_renamed), text(), Notifications.Kind.WARNING)
    }

    /** [folder]'s source folder and name as the file system compares names (see [DownloadIndex.key]). */
    private fun folderKey(folder: File): String = index.key(root, folder.parentFile.name) + "/" + index.key(root, folder.name)

    private fun isSameFolder(a: File, b: File): Boolean =
        Files.exists(b.toPath(), LinkOption.NOFOLLOW_LINKS) && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false)

    /** Forgets what was read from disk, for example after the storage folder moved. */
    fun invalidateCache() {
        lastRenew = System.currentTimeMillis()
        index.clear()
        _changes.update { it + 1 }
    }

    private fun invalidate(mangaDir: File) {
        index.invalidate(mangaDir)
        _changes.update { it + 1 }
    }

    /**
     * Deletes the downloads of [chapters] (each name they may have, and unfinished downloads), then
     * the entry's folder when nothing is left in it. Never touches other files.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun delete(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        paths.requireStorage()
        cancel(chapters.map { it.id }.toSet())
        // A cancelled download may still be writing its last file; delete after it stopped, or the
        // chapter would come back
        awaitStopping()
        val others = coOwnerChapters(manga, chapters)
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters, others) }
    }

    /**
     * The chapters of the other entries that share [manga]'s folder, read before any file is deleted;
     * none when [chapters] have nothing on disk (no lookup then).
     */
    private suspend fun coOwnerChapters(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val lookup = coOwnerLookup ?: return emptyList()
        val anything = dirsOf(manga).any { dir -> dir.isDirectory && chapters.any { namesOnDisk(dir, it).isNotEmpty() } }
        if (!anything) return emptyList()
        return lookup(manga).filter { (other, _) -> other.id != manga.id }.flatMap { it.second }
    }

    /** The database's entries in [Manga]'s download folder, with their chapters; null without a database. */
    private fun databaseCoOwners(): (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? {
        val mangas = try {
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
        } catch (e: uy.kohesive.injekt.api.InjektionException) {
            return null
        }
        val chapters = try {
            Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        } catch (e: uy.kohesive.injekt.api.InjektionException) {
            return null
        }
        return { manga ->
            // Sources with the same name and language share a folder
            val sourceDir = paths.sourceDir(manga.source)
            val sources = (sourceManager.getOnlineSources().map { it.id }.filter { paths.sourceDir(it) == sourceDir } + manga.source).distinct()
            // The repository throws on a database error: the delete fails rather than guessing
            sources.flatMap { mangas.getMangaBySourceId(it) }
                .filter { it.id != manga.id && sharesDownloadFolder(manga, it) }
                .map { it to chapters.getChapterByMangaId(it.id) }
        }
    }

    /**
     * Under the entry's lock: [chapters]' files in each of the entry's folders, then the folder if
     * empty. A name a chapter of another entry in the folder ([others]) also has is that entry's
     * download too (titles that give the same folder name, chapter names without the URL hash): kept.
     */
    private fun deleteChapterFiles(manga: Manga, chapters: List<Chapter>, others: List<Chapter> = emptyList()) {
        dirsOf(manga).forEach { dir ->
            index.invalidate(dir)
            if (!dir.isDirectory) return@forEach
            val keep = namesOnDisk(dir, others)
            chapters.flatMap { namesOnDisk(dir, it) }.distinct().filter { it !in keep }.forEach { name ->
                if (!SafeDelete.delete(root, File(dir, name), minDepth = 3)) {
                    logcat(LogPriority.WARN) { "Could not delete the download $name of ${manga.title}" }
                }
            }
            SafeDelete.deleteIfEmpty(root, dir, minDepth = 2)
            invalidate(dir)
        }
    }

    /**
     * Deletes the downloads of [chapters], all of an entry's chapters, and takes them out of the
     * queue, as Android's deleteManga(removeQueued = true). Unlike Android it deletes only those
     * chapters' files (and unfinished downloads of them): an entry whose title gives the same folder
     * name as another entry's, or files copied in by hand, are kept. The folder, and then the
     * source's folder, go once empty.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun deleteManga(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        paths.requireStorage()
        cancelSeries(manga.id)
        awaitStopping()
        val others = coOwnerChapters(manga, chapters)
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters, others) }
        SafeDelete.deleteIfEmpty(root, paths.sourceDir(manga.source), minDepth = 1)
    }

    /**
     * Which of [chapters] the automatic deletions may delete, as Android's getChaptersToDelete:
     * read chapters of an entry in an excluded category stay ("Exclude categories"), and so do
     * bookmarked chapters unless "Allow deleting bookmarked chapters" is on.
     */
    suspend fun chaptersToDelete(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val excluded = preferences.removeExcludeCategories().get().mapNotNull(String::toLongOrNull).toSet()
        val categories = try {
            getCategories.await(manga.id).map { it.id }.ifEmpty { listOf(0L) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without the categories the "Exclude categories" protection can't be checked; delete nothing
            logcat(LogPriority.WARN, e) { "Reading the categories of ${manga.title} failed; not deleting its chapters" }
            return emptyList()
        }
        val allowed = if (categories.any { it in excluded }) chapters.filterNot { it.read } else chapters
        return if (preferences.removeBookmarkedChapters().get()) allowed else allowed.filterNot { it.bookmark }
    }

    /**
     * Deletes the downloads of read [chapters] the way an automatic deletion does (see
     * [chaptersToDelete]), each from the entry it belongs to, which for a merged entry is a child.
     */
    suspend fun deleteRead(chapters: List<Chapter>) {
        // An automatic deletion: with the storage drive unplugged there is nothing to delete, and
        // marking the chapters read must not fail because of it
        if (!paths.isStorageAvailable) {
            logcat(LogPriority.WARN) { "Not deleting read chapters: the storage folder is not available" }
            return
        }
        chapters.filter { it.read }.groupBy { it.mangaId }.forEach { (mangaId, list) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            val toDelete = chaptersToDelete(manga, list).filter { isDownloaded(manga, it) || it.id in queuedIds() }
            if (toDelete.isNotEmpty()) delete(manga, toDelete)
        }
    }

    /**
     * After chapters are marked as read by hand (not by reading): deletes their downloads when
     * "After manually marked as read" is on, as Android's SetReadStatus.
     */
    suspend fun afterMarkedRead(chapters: List<Chapter>) {
        if (!preferences.removeAfterMarkedAsRead().get()) return
        deleteRead(chapters.map { it.copy(read = true) })
    }

    private fun queuedIds(): Set<Long> = _queue.value.mapTo(HashSet()) { it.chapter.id }

    /** What "Clean up downloaded chapters" would delete, to show before anything is deleted. */
    data class CleanupPlan(
        val items: List<CleanupItem>,
        /**
         * Whether the downloads folder's volume has a system trash ([SafeDelete.canTrash]): what the
         * preview says and what [applyCleanupResult] should be given, so the two always agree.
         */
        val toTrash: Boolean = false,
        /** How the plan was made, to check each item again just before it is deleted; null checks nothing. */
        val recheck: CleanupRecheck? = null,
    ) {
        val orphans: List<CleanupItem> get() = items.filter { it.reason == CleanupReason.ORPHAN_FOLDER }
        val others: List<CleanupItem> get() = items.filter { it.reason != CleanupReason.ORPHAN_FOLDER }
    }

    /** The choices and the database reads a [CleanupPlan] was made with. */
    class CleanupRecheck internal constructor(
        internal val removeRead: Boolean,
        internal val removeNonFavorite: Boolean,
        internal val protectedIds: Set<Long>,
        internal val allManga: suspend () -> List<Manga>,
        internal val chaptersOf: suspend (Long) -> List<Chapter>,
    )

    /**
     * What [applyCleanupResult] did: [deleted] files and folders; [notDeleted] ones it tried and could
     * not delete (or move to the trash), still on disk; [skipped] ones it left because they changed
     * since the plan (read again, back in the library, queued, an orphan that has an entry now).
     */
    data class CleanupResult(val deleted: Int, val notDeleted: Int, val skipped: Int)

    /**
     * One file or folder a clean-up would delete. [owners] are the entries whose folder it is in
     * (more than one when titles give the same folder name); empty for an orphaned folder.
     */
    data class CleanupItem(
        val file: File,
        val reason: CleanupReason,
        val owners: Set<Long>,
        val bytes: Long,
    )

    enum class CleanupReason {
        /** An unfinished download (`_tmp`) */
        UNFINISHED,

        /** A read chapter, when "Delete read chapters" is ticked */
        READ,

        /** A chapter of an entry that left the library, when that option is ticked */
        NOT_IN_LIBRARY,

        /** A folder no entry of the database has; only deleted when the user ticks it */
        ORPHAN_FOLDER,
    }

    /**
     * SY's "Clean up downloaded chapters" (Settings > Advanced), first step: finds what it would
     * delete and deletes nothing. Unlike SY it never plans to delete a file it does not know as a
     * chapter of the entry (it may be the Android app's, in a shared folder); orphaned folders are
     * listed for the user to tick; and a source folder whose source has no entry in the database is
     * left alone (a fresh or restored database, or another app's downloads).
     *
     * Entries are grouped by folder, as two sources with the same name and language, or two entries
     * with titles that give the same folder name, share one. Entries in [protectedIds] (the children
     * of merged entries, which are not in the library themselves) never count as "not in library".
     */
    suspend fun planCleanup(
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        allManga: suspend () -> List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
        protectedIds: Set<Long> = emptySet(),
    ): CleanupPlan = withContext(Dispatchers.IO) {
        paths.requireStorage()
        val mangas = allManga()
        val busy = _queue.value.mapTo(HashSet()) { it.manga.id }
        val chapterCache = HashMap<Long, List<Chapter>>()
        val knownChapters: suspend (Long) -> List<Chapter> = { id -> chapterCache.getOrPut(id) { chaptersOf(id) } }
        // Every entry by the source folder its downloads go to: other sources (even ones not
        // installed) whose folder has the same name count as owners too
        val bySourceDir = mangas.groupBy { paths.sourceDir(it.source) }
        val items = ArrayList<CleanupItem>()
        sourceManager.getOnlineSources().map { paths.sourceDir(it.id) }.distinct().forEach { sourceDir ->
            val sourceMangas = bySourceDir[sourceDir].orEmpty()
            // Nothing in the database for this folder: it is not the desktop's to judge
            if (sourceMangas.isEmpty()) return@forEach
            // A linked folder (or a Windows junction) is not walked into: what it points to is not the desktop's
            val folders = sourceDir.listFiles()
                ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !SafeDelete.isLink(it.toPath()) }
                .orEmpty()
            if (folders.isEmpty()) return@forEach
            val byFolder = HashMap<String, MutableSet<Manga>>()
            sourceMangas.forEach { m ->
                paths.mangaDirNames(m.ogTitle).forEach { name -> byFolder.getOrPut(index.key(root, name)) { LinkedHashSet() } += m }
            }
            folders.forEach folder@{ folder ->
                val owners = byFolder[index.key(root, folder.name)]?.toList()
                if (owners == null) {
                    // Not orphaned when it holds chapters of an entry whose folder has another name (the
                    // source changed the title, and a download may have used the old one since)
                    val owner = renamedOwner(folder, sourceMangas, knownChapters)
                    if (owner != null) {
                        logcat(LogPriority.WARN) { "Kept ${folder.name}: its chapters belong to ${owner.ogTitle}, whose folder has another name" }
                    } else {
                        items += CleanupItem(folder, CleanupReason.ORPHAN_FOLDER, emptySet(), sizeOf(folder))
                    }
                    return@folder
                }
                if (owners.any { it.id in busy }) return@folder
                items += planFolder(folder, owners, knownChapters, removeRead, removeNonFavorite, protectedIds)
            }
        }
        CleanupPlan(
            items,
            toTrash = SafeDelete.canTrash(root),
            recheck = CleanupRecheck(removeRead, removeNonFavorite, protectedIds, allManga, chaptersOf),
        )
    }

    /** The entries of [mangas] whose download folder is [folder] (a folder in a source's folder). */
    private fun ownersOf(folder: File, mangas: List<Manga>): List<Manga> {
        val key = index.key(root, folder.name)
        return mangas.filter { m ->
            paths.sourceDir(m.source) == folder.parentFile && paths.mangaDirNames(m.ogTitle).any { index.key(root, it) == key }
        }
    }

    private suspend fun planFolder(
        folder: File,
        owners: List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        protectedIds: Set<Long>,
    ): List<CleanupItem> {
        index.invalidate(folder)
        val ownerIds = owners.mapTo(LinkedHashSet()) { it.id }
        val chapters = owners.flatMap { chaptersOf(it.id) }
        val items = ArrayList<CleanupItem>()
        // Unfinished downloads of any chapter, known or not: the queue of these entries is empty
        index.names(root, folder).filter { it.endsWith(DownloadPaths.TMP_SUFFIX) }.forEach { name ->
            items += CleanupItem(File(folder, name), CleanupReason.UNFINISHED, ownerIds, sizeOf(File(folder, name)))
        }
        val notInLibrary = removeNonFavorite && owners.all { !it.favorite && it.id !in protectedIds }
        val toDelete: List<Chapter> = when {
            notInLibrary -> chapters
            removeRead -> chapters.filter { it.read }
            else -> emptyList()
        }
        if (toDelete.isEmpty()) return items
        // A name an unread chapter of another entry in this folder also has is that entry's too: kept
        val keep = if (notInLibrary) {
            emptySet()
        } else {
            namesOnDisk(folder, chapters.filterNot { it.read })
        }
        val reason = if (notInLibrary) CleanupReason.NOT_IN_LIBRARY else CleanupReason.READ
        toDelete.flatMap { namesOnDisk(folder, it) }.distinct()
            .filter { it !in keep && !it.endsWith(DownloadPaths.TMP_SUFFIX) }
            .forEach { name -> items += CleanupItem(File(folder, name), reason, ownerIds, sizeOf(File(folder, name))) }
        return items
    }

    /**
     * Second step: deletes what [plan] lists, except orphaned folders not in [orphansToDelete], each
     * through [SafeDelete], into the system trash when [toTrash] (a file that cannot go there is
     * kept, never deleted for good). Entry folders left empty go too.
     *
     * Each item is checked again just before it goes, under the locks of all the entries in its
     * folder: one whose entry was queued since, whose chapter is no longer read, whose entry is back
     * in the library, or an orphaned folder that has an entry now, is left alone.
     *
     * @return how many files and folders it deleted
     */
    suspend fun applyCleanup(plan: CleanupPlan, orphansToDelete: Set<File> = emptySet(), toTrash: Boolean = false): Int =
        applyCleanupResult(plan, orphansToDelete, toTrash).deleted

    /** [applyCleanup], with what it could not delete and what it left because it changed. */
    suspend fun applyCleanupResult(
        plan: CleanupPlan,
        orphansToDelete: Set<File> = emptySet(),
        toTrash: Boolean = false,
    ): CleanupResult = withContext(Dispatchers.IO) {
        paths.requireStorage()
        val recheck = plan.recheck
        // The database as it is now, read once
        val mangas = recheck?.allManga?.invoke()
        var deleted = 0
        var notDeleted = 0
        var skipped = 0
        plan.orphans.filter { it.file in orphansToDelete }.forEach { item ->
            if (recheck != null && mangas != null && hasOwnerNow(item.file, mangas, recheck.chaptersOf)) {
                logcat(LogPriority.WARN) { "Kept ${item.file.name}: an entry uses that folder now" }
                skipped++
                return@forEach
            }
            if (SafeDelete.delete(root, item.file, minDepth = 2, toTrash = toTrash)) deleted++ else notDeleted++
            invalidate(item.file)
        }
        plan.others.groupBy { it.file.parentFile }.forEach { (folder, items) ->
            val owners = mangas?.let { ownersOf(folder, it) }
            val ids = (items.flatMap { it.owners } + owners.orEmpty().map { it.id }).distinct().sorted()
            // The queue is read under the entries' locks, right before deleting: an entry queued since
            // the plan is left alone, and a download of it waits for this to finish
            withEntryLocks(ids) {
                if (_queue.value.any { it.manga.id in ids }) {
                    skipped += items.size
                    return@withEntryLocks
                }
                // What the plan would list now, with each entry's library and read state read again
                val still = if (recheck != null && owners != null) {
                    if (owners.isEmpty()) {
                        emptySet()
                    } else {
                        val chapters = HashMap<Long, List<Chapter>>()
                        planFolder(
                            folder,
                            owners,
                            { id -> chapters.getOrPut(id) { recheck.chaptersOf(id) } },
                            recheck.removeRead,
                            recheck.removeNonFavorite,
                            recheck.protectedIds,
                        ).mapTo(HashSet()) { it.file }
                    }
                } else {
                    null
                }
                items.forEach { item ->
                    if (still != null && item.file !in still) {
                        logcat(LogPriority.INFO) { "Kept ${item.file.name}: it changed since the clean-up was planned" }
                        skipped++
                        return@forEach
                    }
                    if (SafeDelete.delete(root, item.file, minDepth = 3, toTrash = toTrash)) deleted++ else notDeleted++
                }
                if (SafeDelete.deleteIfEmpty(root, folder, minDepth = 2)) deleted++
            }
            invalidate(folder)
        }
        CleanupResult(deleted, notDeleted, skipped)
    }

    /** Runs [block] holding the entry locks of [ids], taken in the order given (ascending ids). */
    private suspend fun withEntryLocks(ids: List<Long>, block: suspend () -> Unit) {
        if (ids.isEmpty()) return block()
        entryLock(ids.first()).withLock { withEntryLocks(ids.drop(1), block) }
    }

    /** Whether an orphaned [folder] planned for deletion is an entry's folder now (see [planCleanup]). */
    private suspend fun hasOwnerNow(folder: File, mangas: List<Manga>, chaptersOf: suspend (Long) -> List<Chapter>): Boolean {
        if (ownersOf(folder, mangas).isNotEmpty()) return true
        val sourceMangas = mangas.filter { paths.sourceDir(it.source) == folder.parentFile }
        return renamedOwner(folder, sourceMangas, chaptersOf) != null
    }

    /**
     * Plans and runs a clean-up in one go, leaving orphaned folders alone (the settings screen shows
     * the plan first, see [planCleanup] and [applyCleanup]).
     *
     * @return how many files and folders it deleted
     */
    suspend fun cleanup(
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        allManga: suspend () -> List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
    ): Int = applyCleanup(planCleanup(removeRead, removeNonFavorite, allManga, chaptersOf))

    private fun sizeOf(file: File): Long = try {
        Files.walk(file.toPath()).use { s ->
            s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { it.toFile().length() }.sum()
        }
    } catch (e: IOException) {
        0L
    } catch (e: java.io.UncheckedIOException) {
        0L
    }

    /**
     * An entry of the source that has a chapter's download in [folder], which is not the folder its
     * title names, and not in its own folder (which may or may not exist).
     */
    private suspend fun renamedOwner(
        folder: File,
        sourceMangas: List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
    ): Manga? {
        index.invalidate(folder)
        if (index.names(root, folder).isEmpty()) return null
        return sourceMangas.firstOrNull { manga ->
            // A copy of a chapter the entry's own folder also has is a leftover, not the only download
            chaptersOf(manga.id).any { chapter ->
                index.matching(root, folder, paths.validChapterNames(chapter)).isNotEmpty() && !isDownloaded(manga, chapter)
            }
        }
    }

    // Queue -----------------------------------------------------------------------------------

    /** Queues each entry's chapters that are not downloaded or queued yet. Safe to call from the UI thread. */
    suspend fun enqueueAll(entries: List<Pair<Manga, List<Chapter>>>, start: Boolean = true) {
        withContext(Dispatchers.IO) {
            entries.forEach { (manga, chapters) -> enqueue(manga, chapters, start) }
        }
    }

    /**
     * Adds [chapters] to the queue, unless downloaded or queued already.
     * Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun enqueue(manga: Manga, chapters: List<Chapter>, start: Boolean = true) {
        if (chapters.isEmpty()) return
        // As Android's Downloader.queueChapters: only an installed online source downloads, so the
        // local source and an entry whose extension is not installed queue nothing; nor does a merged
        // entry itself (callers pass its children here one by one)
        if (!canDownload(manga)) return
        val candidates = chapters
            .distinctBy { it.id }
            .filterNot { isDownloaded(manga, it) }
            // Oldest first, as they are read
            .sortedByDescending { it.sourceOrder }
        if (candidates.isEmpty()) return
        // The queued check and the add in one atomic update, so callers racing each other (the
        // library update and the UI) never queue a chapter twice
        var added = emptyList<Download>()
        _queue.update { q ->
            val queued = q.mapTo(HashSet()) { it.chapter.id }
            added = candidates.filterNot { it.id in queued }.map { Download(manga, it) }
            if (added.isEmpty()) q else q + added
        }
        if (added.isEmpty()) return
        save()
        if (start) start()
    }

    /**
     * Starts downloading, retrying failed chapters. A running queue picks them up as it goes. Saved
     * downloads that a failed database read held back at start-up ([held]) are read again first.
     */
    fun start() {
        if (heldUnread && heldRetry.compareAndSet(false, true)) {
            scope.launch {
                try {
                    restoreHeld()
                } finally {
                    heldRetry.set(false)
                }
            }
        }
        synchronized(lock) { startLocked() }
    }

    private fun startLocked() {
        _queue.value.filter { it.state.value == Download.State.ERROR }.forEach {
            it.error = null
            it.state.value = Download.State.QUEUED
        }
        if (job != null) return
        if (_queue.value.none { it.state.value == Download.State.QUEUED }) return
        // A paused run may still be finishing a file; the new one waits, so the two never write
        // the same chapter
        val previous = stopping.toList()
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                awaitStopping(previous)
                run(self)
            } finally {
                synchronized(lock) {
                    if (job === self) {
                        job = null
                        _running.value = false
                    }
                }
            }
        }
        job = newJob
        _running.value = true
        newJob.start()
    }

    fun pause() {
        synchronized(lock) {
            val current = job ?: return
            job = null
            stopping.add(current)
            _running.value = false
            current.cancel()
            current.invokeOnCompletion { synchronized(lock) { stopping.remove(current) } }
            _queue.value.filter { it.state.value == Download.State.DOWNLOADING }.forEach { it.state.value = Download.State.QUEUED }
        }
    }

    fun cancel(chapterIds: Set<Long>) {
        // The state check, the pause and the removal happen under the lock, as the worker's claim
        // of a chapter does (see [run]), so a chapter is either removed before it starts or its
        // run is paused
        var removed: List<Download> = emptyList()
        var restart = false
        val stopped = synchronized(lock) {
            // A chapter the user cancels is not kept for a later restore either, also one that is
            // only held (not in the queue): the saved file then leaves it out
            val heldBefore = held
            if (heldBefore.isNotEmpty()) {
                held = heldBefore.filterNot { it.chapterId in chapterIds }
                if (held.isEmpty()) heldUnread = false
            }
            removed = _queue.value.filter { it.chapter.id in chapterIds }
            if (removed.isEmpty()) {
                if (held.size != heldBefore.size) save()
                return
            }
            restart = job != null && removed.any { it.state.value == Download.State.DOWNLOADING }
            if (restart) pause()
            _queue.update { q -> q.filterNot { it.chapter.id in chapterIds } }
            stopping.toList()
        }
        scope.launch {
            // After the cancelled download let go of its files
            awaitStopping(stopped)
            if (!paths.isStorageAvailable) return@launch
            removed.groupBy { it.manga.id }.forEach { (id, downloads) ->
                // Under the entry's lock, re-reading the queue: a chapter queued again since keeps its
                // unfinished download, which the new download resumes
                entryLock(id).withLock {
                    val queued = _queue.value.mapTo(HashSet()) { it.chapter.id }
                    downloads.filterNot { it.chapter.id in queued }.forEach { d ->
                        val dir = index.resolve(root, paths.mangaDir(d.manga))
                        val name = paths.chapterDirName(d.chapter)
                        listOf(name + DownloadPaths.TMP_SUFFIX, "$name.cbz" + DownloadPaths.TMP_SUFFIX).forEach {
                            SafeDelete.delete(root, File(dir, it), minDepth = 3)
                        }
                        invalidate(dir)
                    }
                }
            }
        }
        save()
        if (restart) start()
    }

    /** Cancels every download: those queued and those [held] back by a failed database read. */
    fun clearQueue() {
        cancel(_queue.value.mapTo(HashSet()) { it.chapter.id } + held.map { it.chapterId })
    }

    /** Moves a queued download to the front. */
    fun moveToTop(download: Download) {
        // A download that finished meanwhile is not put back
        _queue.update { q -> if (download in q) listOf(download) + (q - download) else q }
        save()
    }

    /** Moves a queued download to the end. */
    fun moveToBottom(download: Download) {
        _queue.update { q -> if (download in q) (q - download) + download else q }
        save()
    }

    /** Moves every queued chapter of [mangaId] to the front or the end, in their order, as Android's "Move series to top". */
    fun moveSeries(mangaId: Long, toTop: Boolean) {
        _queue.update { q ->
            val (series, others) = q.partition { it.manga.id == mangaId }
            if (toTop) series + others else others + series
        }
        save()
    }

    /** Cancels every queued chapter of [mangaId], as Android's "Cancel all for this series". */
    fun cancelSeries(mangaId: Long) {
        cancel(_queue.value.filter { it.manga.id == mangaId }.mapTo(HashSet()) { it.chapter.id })
    }

    /**
     * Sorts the queue, as Android's queue sort: within each source, which keep their order, by
     * [selector], descending when [descending].
     */
    fun <R : Comparable<R>> sortQueue(descending: Boolean, selector: (Download) -> R) {
        _queue.update { q ->
            q.groupBy { it.manga.source }.values.flatMap { downloads ->
                downloads.sortedBy(selector).let { if (descending) it.reversed() else it }
            }
        }
        save()
    }

    /** Waits until nothing is downloading. */
    suspend fun awaitIdle() {
        running.first { !it }
    }

    /**
     * Waits for the work started in the background: queue saves and the clean-up of cancelled
     * chapters' files. They write in the app's folder after the call that started them returns.
     */
    suspend fun awaitBackgroundWork() {
        while (true) {
            val pending = scope.coroutineContext.job.children.filter { it.isActive }.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    private suspend fun run(self: Job) = coroutineScope {
        val downloaded = AtomicInteger()
        val failed = AtomicInteger()
        while (isActive) {
            val pending = synchronized(lock) {
                _queue.value.filter { it.state.value == Download.State.QUEUED }.also {
                    // Ends the run under the lock, so a chapter queued from now on starts a new one
                    if (it.isEmpty() && job === self) {
                        job = null
                        _running.value = false
                    }
                }
            }
            if (pending.isEmpty()) break
            val bySource = pending.groupBy { it.manga.source }.values
                .take(preferences.parallelSourceLimit().get().coerceIn(1, 10))
            coroutineScope {
                bySource.forEach { downloads ->
                    launch {
                        for (download in downloads) {
                            if (!isActive) break
                            // Claims the chapter under the lock, so cancel() sees it either still
                            // queued (and removes it) or downloading (and pauses the run)
                            val claimed = synchronized(lock) {
                                (isActive && download in _queue.value && download.state.value == Download.State.QUEUED)
                                    .also { if (it) download.state.value = Download.State.DOWNLOADING }
                            }
                            if (!claimed) continue
                            if (download(download)) downloaded.incrementAndGet() else failed.incrementAndGet()
                        }
                    }
                }
            }
        }
        val done = downloaded.get()
        val errors = failed.get()
        if (done + errors > 0) {
            Notifications.post(
                localized(DMR.strings.downloads_finished),
                buildString {
                    append(localizedPlural(DMR.plurals.chapters_downloaded, done, done))
                    if (errors > 0) append(", ").append(localized(DMR.strings.n_failed, errors))
                },
                if (errors > 0) Notifications.Kind.WARNING else Notifications.Kind.INFO,
            )
        }
    }

    /** Downloads a chapter that [run] has claimed (set to downloading). @return whether it downloaded */
    private suspend fun download(download: Download): Boolean {
        val manga = download.manga
        val chapter = download.chapter
        return try {
            val source = sourceManager.get(manga.source) as? HttpSource
                ?: throw SourceNotInstalledException(manga.title)
            // Nothing is created on the system disk when the chosen storage folder is unplugged
            paths.requireStorage()
            // The folder as named on disk, so a copy from macOS (NFD names) gets no second folder
            val dir = index.resolve(root, paths.mangaDir(manga))
            // The gate is the disk, not the cache: a chapter copied in since the folder was listed
            // is kept as it is. The entry's lock keeps a clean-up away from the files being written.
            entryLock(manga.id).withLock {
                dirsOf(manga).forEach { index.invalidate(it) }
                if (findChapter(manga, chapter) != null) {
                    invalidate(dir)
                } else {
                    val freeSpace = dir.parentFile?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull(File::exists) }?.usableSpace
                    if (!hasRoom(freeSpace)) error(localized(DMR.strings.download_disk_full))
                    val downloader = newDownloader(
                        { source.getPageList(chapter.toSChapter()) },
                        { source.getImageUrl(it) },
                        { page ->
                            // ZOUKAN -->
                            // The SY data saver, when it applies to downloads, as Android's Downloader
                            val prefs = sourcePreferences
                            val dataSaver =
                                if (prefs != null && prefs.dataSaverDownloader().get()) {
                                    exh.util.DataSaver(source, prefs)
                                } else {
                                    exh.util.DataSaver.NoOp
                                }
                            with(exh.util.DataSaver) { source.getImage(page, dataSaver) }.use { response ->
                                // ZOUKAN <--
                                if (!response.isSuccessful) error("HTTP ${response.code}")
                                // Bounded as the reader bounds it: an endless body cannot fill the heap
                                zoukan.desktop.image.PageSizeLimits.readPageBody(response.body, maxPageBytes)
                            }
                        },
                        preferences.parallelPageLimit().get(),
                    )
                    // Only a CBZ is encrypted: a folder download never reads the secret store
                    val asCbz = preferences.saveChaptersAsCBZ().get()
                    val encryption = if (asCbz) password.encryption() else null
                    val options = ChapterDownloader.Options(
                        asCbz = asCbz,
                        encryption = encryption,
                        comicInfo = comicInfo(manga, chapter, source, encryption),
                        // ZOUKAN -->
                        splitTallImages = preferences.splitTallImages().get(),
                        // ZOUKAN <--
                    )
                    downloader.download(dir, paths.chapterDirName(chapter), options) { done, total ->
                        download.progress.value = done to total
                    }
                    invalidate(dir)
                }
            }
            download.state.value = Download.State.DOWNLOADED
            _queue.update { it - download }
            save()
            true
        } catch (e: CancellationException) {
            if (download.state.value == Download.State.DOWNLOADING) download.state.value = Download.State.QUEUED
            throw e
        } catch (e: Throwable) {
            if (e is SourceNotInstalledException) {
                // An expected state (a restored library whose extension is not installed yet): the
                // user gets the notification below, and the log needs no stack trace
                logcat(LogPriority.WARN) { "Downloading ${manga.title} / ${chapter.name} failed: ${e.message}" }
            } else {
                logcat(LogPriority.ERROR, e) { "Downloading ${manga.title} / ${chapter.name} failed" }
            }
            download.error = errorText(e)
            download.state.value = Download.State.ERROR
            // After the state, so a fatal error leaves no download stuck at downloading; a source's
            // LinkageError (an outdated extension) is a failed download like any other
            e.rethrowIfVmFailure()
            Notifications.post(localized(DMR.strings.download_failed), "${manga.title}: ${chapter.name}\n${download.error}", Notifications.Kind.ERROR)
            false
        }
    }

    private suspend fun comicInfo(manga: Manga, chapter: Chapter, source: HttpSource, encryption: CbzEncryption?): ComicInfoXml {
        val categories = zoukan.desktop.app.runCatchingCancellable { getCategories.await(manga.id) }.getOrDefault(emptyList())
            .map { it.name.trim() }.filter { it.isNotEmpty() }
        val url = runCatching { source.getChapterUrl(chapter.toSChapter()).trim() }.getOrNull()
        return ComicInfoXml(
            buildMap {
                put("Title", chapter.name)
                put("Series", manga.title)
                chapter.chapterNumber.takeIf { it >= 0 }?.let {
                    put("Number", if (it % 1 == 0.0) it.toInt().toString() else it.toString())
                }
                url?.takeIf { it.isNotEmpty() }?.let { put("Web", it) }
                manga.description?.let { put("Summary", it) }
                manga.author?.let { put("Writer", it) }
                manga.artist?.let { put("Penciller", it) }
                chapter.scanlator?.let { put("Translator", it) }
                manga.genre?.let { put("Genre", it.joinToString()) }
                put("PublishingStatusTachiyomi", ComicInfoXml.statusToComicInfo(manga.status))
                if (categories.isNotEmpty()) put("Categories", categories.joinToString())
                put("SourceMihon", source.name)
                password.comicInfoPadding(encryption)?.let { put("PaddingTachiyomiSY", it) }
            },
        )
    }

    // Saved queue ------------------------------------------------------------------------------

    @Serializable
    private data class SavedDownload(val mangaId: Long, val chapterId: Long)

    private fun save() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    // The saved downloads a restore has not read yet, or could not read, stay saved.
                    // Read before the queue: restore() adds to the queue first and then lets go of them,
                    // so each is in one or the other. Before restore() the saved file is all of them;
                    // one that cannot be read is left for restore() to keep aside
                    val kept = if (restoreBegun) held else peekSavedQueue() ?: return@runCatching
                    // The queue as it is now, not as it was at the call: saves start on IO threads
                    // and can take the lock out of order, so an older list must never be written last
                    val queued = _queue.value.map { SavedDownload(it.manga.id, it.chapter.id) }
                    val ids = queued.mapTo(HashSet()) { it.chapterId }
                    val saved = queued + kept.filterNot { it.chapterId in ids }
                    val tmp = File(queueFile.path + ".tmp")
                    tmp.writeText(json.encodeToString(saved))
                    try {
                        Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }.onFailure { logcat(LogPriority.WARN, it) { "Saving the download queue failed" } }
            }
        }
    }

    /** The saved queue as it is, empty when there is none; null when it cannot be read. Changes nothing. */
    private fun peekSavedQueue(): List<SavedDownload>? {
        val file = queueFile
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<SavedDownload>>(file.readText())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is SerializationException && e !is IllegalArgumentException && e !is IOException) throw e
            null
        }
    }

    /**
     * The saved queue, or null when there is none (first start) or it cannot be read. A file that
     * cannot be read is moved to `downloads-queue.unreadable-<time>.json`, never replaced or
     * deleted, so the next save does not overwrite it, and the user is told.
     */
    private fun readSavedQueue(): List<SavedDownload>? {
        val file = queueFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<List<SavedDownload>>(file.readText())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is SerializationException && e !is IllegalArgumentException && e !is IOException) throw e
            val keptName = keepUnreadableQueue(file)
            logcat(LogPriority.WARN, e) { "The saved download queue could not be read; kept as $keptName" }
            Notifications.post(
                localized(DMR.strings.download_queue_unreadable),
                localized(DMR.strings.download_queue_unreadable_kept, keptName.orEmpty()),
                Notifications.Kind.WARNING,
            )
            null
        }
    }

    private fun keepUnreadableQueue(file: File): String? {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        for (n in 0..99) {
            val name = "downloads-queue.unreadable-$stamp${if (n == 0) "" else "-$n"}.json"
            try {
                // No REPLACE_EXISTING: a kept file is never overwritten
                Files.move(file.toPath(), File(file.parentFile, name).toPath())
                return name
            } catch (_: FileAlreadyExistsException) {
                continue
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not keep the unreadable download queue aside" }
                return null
            }
        }
        return null
    }

    /** Whether [manga]'s chapters can download: an installed online source, and not a merged entry itself. */
    private fun canDownload(manga: Manga): Boolean =
        manga.source != MERGED_SOURCE_ID && sourceManager.get(manga.source) is HttpSource

    /**
     * The saved downloads [restore] has not put in the queue yet, or could not read from the
     * database: every save writes them after the queue, so they are restored when the queue next
     * starts ([restoreHeld]) or, failing that, at the next start of the app.
     */
    @Volatile
    private var held: List<SavedDownload> = emptyList()

    /** Whether [held] has downloads a failed database read left unread, which [start] reads again. */
    @Volatile
    private var heldUnread = false

    /** Whether a [restoreHeld] is running, so a second [start] does not read the same downloads again. */
    private val heldRetry = java.util.concurrent.atomic.AtomicBoolean()

    /** Whether [restore] has read the saved file; until then, every save keeps what it holds. */
    @Volatile
    private var restoreBegun = false

    /** The entries and chapters as the database reads them; null when not registered (tests without one). */
    private val restoreRepositories by lazy {
        try {
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>() to
                Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
        } catch (e: uy.kohesive.injekt.api.InjektionException) {
            null
        }
    }

    /**
     * [saved]'s chapter, or null when it was deleted. Unlike [GetChapter], which returns null on a
     * failed read too, a read that fails throws, so the saved download is not taken for deleted.
     */
    private suspend fun readSavedChapter(saved: SavedDownload): Chapter? {
        val chapters = restoreRepositories?.second ?: return getChapter.await(saved.chapterId)
        return chapters.getChapterById(saved.chapterId)
    }

    /**
     * [chapter]'s entry. A chapter is deleted with its entry, so the entry of a chapter that exists
     * exists too, and a read that fails throws. A saved entry that is not the chapter's is looked up
     * as before.
     */
    private suspend fun readSavedManga(saved: SavedDownload, chapter: Chapter): Manga? {
        val mangas = restoreRepositories?.first
        if (mangas == null || chapter.mangaId != saved.mangaId) return getManga.await(saved.mangaId)
        return mangas.getMangaById(saved.mangaId)
    }

    /**
     * Loads the saved queue and starts it. Called once at start-up. A download whose entry or chapter
     * was deleted is dropped; when the database cannot be read, the downloads from there on stay in
     * the saved file (see [held]) and the user is told. They are read again when the queue next
     * starts ([start]), in this run.
     */
    suspend fun restore(start: Boolean = true) {
        val saved = readSavedQueue().orEmpty()
        // Until they are in the queue, saves keep them (also when the restore is cancelled)
        held = saved
        restoreBegun = true
        if (saved.isEmpty()) return
        val (downloads, unread) = readSaved(saved)
        synchronized(lock) {
            // Still held: not cancelled (cancel() takes it out of [held]) while the database was read
            val stillHeld = held.mapTo(HashSet()) { it.chapterId }
            _queue.update { q ->
                val queued = q.mapTo(HashSet()) { it.chapter.id }
                q + downloads.distinctBy { it.chapter.id }.filter { d -> d.chapter.id in stillHeld && d.chapter.id !in queued }
            }
            // After the queue has them, so a save in between writes each download at least once
            held = unread.filter { it.chapterId in stillHeld }
            heldUnread = held.isNotEmpty()
        }
        if (unread.isNotEmpty()) {
            Notifications.post(
                localized(DMR.strings.download_queue_restore_failed),
                localized(DMR.strings.download_queue_restore_failed_kept),
                Notifications.Kind.WARNING,
            )
        }
        // Not start(): it would read the held downloads again at once, with the database that has
        // just failed. They are read again when the queue next starts
        if (start) synchronized(lock) { startLocked() }
    }

    /**
     * The downloads of [saved] as the database reads them, in order, and the saved downloads from
     * the first read that failed on (empty when every read worked). A download whose entry or chapter
     * was deleted, or whose source can no longer download, is dropped.
     */
    private suspend fun readSaved(saved: List<SavedDownload>): Pair<List<Download>, List<SavedDownload>> {
        val mangas = HashMap<Long, Manga?>()
        val downloads = ArrayList<Download>()
        for ((i, s) in saved.withIndex()) {
            val download = try {
                val chapter = readSavedChapter(s) ?: continue
                val manga = mangas.getOrPut(s.mangaId) { readSavedManga(s, chapter) } ?: continue
                Download(manga, chapter)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "The saved download queue could not be read from the database" }
                return downloads to saved.subList(i, saved.size).toList()
            }
            // As Android's DownloadStore.restore: a chapter whose source can no longer download is dropped
            if (canDownload(download.manga)) downloads += download
        }
        return downloads to emptyList()
    }

    /**
     * Reads the downloads [restore] held back again, as the queue starts: the database may read
     * again by now. Those it reads join the queue, after what is queued and never twice; a held
     * download the user cancelled meanwhile stays out. What still cannot be read stays held, and the
     * user, told once at start-up, is not told again.
     */
    private suspend fun restoreHeld() {
        val snapshot = held
        if (snapshot.isEmpty()) return
        val (downloads, unread) = readSaved(snapshot)
        val unreadIds = unread.mapTo(HashSet()) { it.chapterId }
        val added = synchronized(lock) {
            // Still held: not cancelled (cancel() takes it out of [held]) while the database was read
            val stillHeld = held.mapTo(HashSet()) { it.chapterId }
            val adding = downloads.distinctBy { it.chapter.id }.filter { it.chapter.id in stillHeld }
            _queue.update { q ->
                val queued = q.mapTo(HashSet()) { it.chapter.id }
                q + adding.filterNot { d -> d.chapter.id in queued }
            }
            // After the queue has them, so a save in between writes each download at least once
            held = held.filter { it.chapterId in unreadIds }
            heldUnread = held.isNotEmpty()
            adding
        }
        if (unread.isEmpty()) logcat(LogPriority.INFO) { "The held download queue was restored" }
        save()
        if (added.isNotEmpty()) synchronized(lock) { startLocked() }
    }

    companion object {
        internal const val MIN_FREE_SPACE = 200L * 1024 * 1024

        /** A disk that reports 0 bytes free is full. */
        internal fun hasRoom(freeSpace: Long?): Boolean = freeSpace == null || freeSpace >= MIN_FREE_SPACE

        internal fun errorText(e: Throwable): String {
            val full = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(8).any {
                it is java.io.IOException && it.message?.let { m ->
                    m.contains("No space left", ignoreCase = true) || m.contains("not enough space", ignoreCase = true)
                } == true
            }
            return if (full) localized(DMR.strings.download_disk_full) else e.message ?: e::class.simpleName.orEmpty()
        }
    }
}

/** The entry's source (its extension) is not installed, so its chapters cannot download. */
internal class SourceNotInstalledException(title: String) : IllegalStateException("The source of $title is not installed")
