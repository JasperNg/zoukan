package zoukan.desktop.download

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val queueFile get() = File(dirs.root, "downloads-queue.json")
    private val mutex = Mutex()

    // ZOUKAN -->
    // Not every test registers it; without it there is no data saver
    private val sourcePreferences by lazy { runCatching { Injekt.get<SourcePreferences>() }.getOrNull() }
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
    private var stopping: Job? = null

    /**
     * One lock per entry, shared by the clean-up and the download writer, so clean-up never deletes the
     * files of a download that is starting or running. Never held for two entries at once.
     */
    private val entryLocks = ConcurrentHashMap<Long, Mutex>()
    internal fun entryLock(id: Long) = entryLocks.getOrPut(id) { Mutex() }

    /** What is in each entry's download folder, read once and checked again by its modified time */
    private val index = DownloadIndex()

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
        val names = paths.validChapterNames(chapter)
        for (dir in paths.mangaDirs(manga)) {
            index.findValid(root, dir, names)?.let { return File(dir, it) }
        }
        return null
    }

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun isDownloaded(manga: Manga, chapter: Chapter): Boolean = findChapter(manga, chapter) != null

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> =
        chapters.filter { isDownloaded(manga, it) }.mapTo(HashSet()) { it.id }

    /**
     * How many chapters of [manga] are downloaded, for the library's download badge and filter:
     * folders with files and CBZs with bytes, a chapter with both counted once; unfinished
     * downloads, covers and other files are not chapters.
     * Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun downloadCount(manga: Manga): Int {
        renewIfDue()
        return paths.mangaDirs(manga).flatMapTo(HashSet()) { index.chapterKeys(root, it) }.size
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

    /** The names on disk of every copy of [chapter] in [dir], complete or not, unfinished ones included. */
    private fun namesOnDisk(dir: File, chapter: Chapter): List<String> {
        val candidates = paths.validChapterNames(chapter) +
            paths.validChapterNames(chapter).map { it + DownloadPaths.TMP_SUFFIX }
        return index.matching(root, dir, candidates)
    }

    /**
     * Gives a downloaded chapter the name its new name or scanlator calls for, as Android's
     * renameChapter after a source sync, so the download keeps showing and Clean up downloaded
     * chapters does not take it for an unknown file.
     *
     * Never replaces anything: when a download of the new name exists, or the move fails, the
     * download keeps its old name and this returns false. True when renamed or nothing to rename.
     */
    fun renameChapter(manga: Manga, oldChapter: Chapter, newChapter: Chapter): Boolean {
        val dir = paths.mangaDir(manga)
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

    /**
     * Moves an entry's download folder to the name [newTitle] gives it, as Android's renameManga.
     * Never replaces anything: returns false, and moves nothing, when a folder of that name exists
     * or a chapter of the entry is in the queue. True when there is nothing to move.
     */
    suspend fun renameManga(manga: Manga, newTitle: String): Boolean =
        renameManga(manga.id, manga.source, manga.ogTitle, newTitle)

    suspend fun renameManga(mangaId: Long, sourceId: Long, fromTitle: String, toTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            val old = File(paths.sourceDir(sourceId), paths.mangaDirName(fromTitle))
            val target = File(old.parentFile, paths.mangaDirName(toTitle))
            if (old.name == target.name || !old.exists()) return@withContext true
            if (_queue.value.any { it.manga.id == mangaId }) {
                logcat(LogPriority.WARN) { "Not renaming the download folder ${old.name}: chapters of the entry are queued" }
                return@withContext false
            }
            if (target.exists()) {
                // A case-only change on a case-insensitive disk names the same folder
                if (runCatching { Files.isSameFile(old.toPath(), target.toPath()) }.getOrDefault(false)) {
                    return@withContext true
                }
                logcat(LogPriority.WARN) { "Not renaming the download folder ${old.name}: a folder named ${target.name} exists" }
                return@withContext false
            }
            try {
                // No REPLACE_EXISTING: nothing already on disk is ever overwritten
                Files.move(old.toPath(), target.toPath())
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not rename the download folder ${old.name} to ${target.name}" }
                return@withContext false
            }
            invalidate(old)
            invalidate(target)
            true
        }

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
        synchronized(lock) { stopping }?.join()
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters) }
    }

    /** Under the entry's lock: [chapters]' files in each of the entry's folders, then the folder if empty. */
    private fun deleteChapterFiles(manga: Manga, chapters: List<Chapter>) {
        paths.mangaDirs(manga).forEach { dir ->
            index.invalidate(dir)
            if (!dir.isDirectory) return@forEach
            chapters.flatMap { namesOnDisk(dir, it) }.distinct().forEach { name ->
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
        synchronized(lock) { stopping }?.join()
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters) }
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
    data class CleanupPlan(val items: List<CleanupItem>) {
        val orphans: List<CleanupItem> get() = items.filter { it.reason == CleanupReason.ORPHAN_FOLDER }
        val others: List<CleanupItem> get() = items.filter { it.reason != CleanupReason.ORPHAN_FOLDER }
    }

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
            val folders = sourceDir.listFiles()?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.orEmpty()
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
        CleanupPlan(items)
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
            chapters.filterNot { it.read }.flatMap { namesOnDisk(folder, it) }.toHashSet()
        }
        val reason = if (notInLibrary) CleanupReason.NOT_IN_LIBRARY else CleanupReason.READ
        toDelete.flatMap { namesOnDisk(folder, it) }.distinct()
            .filter { it !in keep && !it.endsWith(DownloadPaths.TMP_SUFFIX) }
            .forEach { name -> items += CleanupItem(File(folder, name), reason, ownerIds, sizeOf(File(folder, name))) }
        return items
    }

    /**
     * Second step: deletes what [plan] lists, except orphaned folders not in [orphansToDelete], each
     * through [SafeDelete], into the system trash when [toTrash] and it works here. A folder whose
     * entry was queued since the plan was made is left alone. Entry folders left empty go too.
     *
     * @return how many files and folders it deleted
     */
    suspend fun applyCleanup(plan: CleanupPlan, orphansToDelete: Set<File> = emptySet(), toTrash: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            paths.requireStorage()
            var cleared = 0
            plan.items.groupBy { it.file.parentFile }.forEach { (_, items) ->
                items.forEach item@{ item ->
                    if (item.reason == CleanupReason.ORPHAN_FOLDER) {
                        if (item.file !in orphansToDelete) return@item
                        if (SafeDelete.delete(root, item.file, minDepth = 2, toTrash = toTrash)) cleared++
                        invalidate(item.file)
                        return@item
                    }
                    // The queue is read under the entry's lock, right before deleting: an entry queued
                    // since the plan is left alone, and a download of it waits for this to finish
                    val folder = item.file.parentFile
                    val id = item.owners.first()
                    entryLock(id).withLock {
                        if (_queue.value.any { it.manga.id in item.owners }) return@withLock
                        if (SafeDelete.delete(root, item.file, minDepth = 3, toTrash = toTrash)) cleared++
                        if (SafeDelete.deleteIfEmpty(root, folder, minDepth = 2)) cleared++
                    }
                    invalidate(folder)
                }
            }
            cleared
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
            s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    } catch (e: Exception) {
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

    /** Starts downloading, retrying failed chapters. A running queue picks them up as it goes. */
    fun start() {
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
        val previous = stopping
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                previous?.join()
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
            stopping = current
            _running.value = false
            current.cancel()
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
            removed = _queue.value.filter { it.chapter.id in chapterIds }
            if (removed.isEmpty()) return
            restart = job != null && removed.any { it.state.value == Download.State.DOWNLOADING }
            if (restart) pause()
            _queue.update { q -> q.filterNot { it.chapter.id in chapterIds } }
            stopping
        }
        scope.launch {
            // After the cancelled download let go of its files
            stopped?.join()
            if (!paths.isStorageAvailable) return@launch
            removed.groupBy { it.manga.id }.forEach { (id, downloads) ->
                // Under the entry's lock, re-reading the queue: a chapter queued again since keeps its
                // unfinished download, which the new download resumes
                entryLock(id).withLock {
                    val queued = _queue.value.mapTo(HashSet()) { it.chapter.id }
                    downloads.filterNot { it.chapter.id in queued }.forEach { d ->
                        val dir = paths.mangaDir(d.manga)
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

    fun clearQueue() {
        cancel(_queue.value.mapTo(HashSet()) { it.chapter.id })
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
                ?: error("The source of ${manga.title} is not installed")
            val dir = paths.mangaDir(manga)
            // Nothing is created on the system disk when the chosen storage folder is unplugged
            paths.requireStorage()
            // The gate is the disk, not the cache: a chapter copied in since the folder was listed
            // is kept as it is. The entry's lock keeps a clean-up away from the files being written.
            entryLock(manga.id).withLock {
                paths.mangaDirs(manga).forEach { index.invalidate(it) }
                if (findChapter(manga, chapter) != null) {
                    invalidate(dir)
                } else {
                    val freeSpace = dir.parentFile?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull(File::exists) }?.usableSpace
                    if (!hasRoom(freeSpace)) error(localized(DMR.strings.download_disk_full))
                    val downloader = ChapterDownloader(
                        pageList = { source.getPageList(chapter.toSChapter()) },
                        imageUrl = { source.getImageUrl(it) },
                        image = { page ->
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
                                response.body.bytes()
                            }
                        },
                        parallelPages = preferences.parallelPageLimit().get(),
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
            logcat(LogPriority.ERROR, e) { "Downloading ${manga.title} / ${chapter.name} failed" }
            download.error = errorText(e)
            download.state.value = Download.State.ERROR
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
                    // The queue as it is now, not as it was at the call: saves start on IO threads
                    // and can take the lock out of order, so an older list must never be written last
                    val saved = _queue.value.map { SavedDownload(it.manga.id, it.chapter.id) }
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

    /** Loads the saved queue and starts it. Called once at start-up. */
    suspend fun restore(start: Boolean = true) {
        val saved = readSavedQueue() ?: return
        val mangas = HashMap<Long, Manga?>()
        val downloads = saved.mapNotNull { s ->
            val manga = mangas.getOrPut(s.mangaId) { getManga.await(s.mangaId) } ?: return@mapNotNull null
            val chapter = getChapter.await(s.chapterId) ?: return@mapNotNull null
            Download(manga, chapter)
        }
        _queue.update { q ->
            val queued = q.mapTo(HashSet()) { it.chapter.id }
            q + downloads.distinctBy { it.chapter.id }.filterNot { d -> d.chapter.id in queued }
        }
        if (start) start()
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
