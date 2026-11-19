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

    internal var folderOps = DownloadFolderOps(
        paths = paths,
        preferences = preferences,
        index = DownloadIndex(clock = indexClock),
        coOwnerLookup = { coOwnerLookup },
        isEntryQueued = { mangaId -> _queue.value.any { it.manga.id == mangaId } },
        onCacheChanged = { _changes.update { it + 1 } },
    )
        private set

    internal var cleanup = DownloadCleanup(
        paths = paths,
        sourceManager = sourceManager,
        preferences = preferences,
        getManga = getManga,
        getCategories = getCategories,
        folderOps = folderOps,
        scope = scope,
        queueLock = lock,
        queuedChapterIds = ::queuedIds,
        queuedMangaIds = ::queuedMangaIds,
        cancelChapters = ::cancel,
        cancelSeries = ::cancelSeries,
        coOwnerLookup = { coOwnerLookup },
    )
        private set

    internal var store = DownloadStore(
        dirs = dirs,
        scope = scope,
        getManga = getManga,
        getChapter = getChapter,
        canDownload = ::canDownload,
        queueLock = lock,
        queue = _queue,
        onRestored = { synchronized(lock) { startLocked() } },
    )
        private set

    internal constructor(
        dirs: ZoukanDirs,
        paths: DownloadPaths,
        sourceManager: SourceManager,
        preferences: DownloadPreferences,
        password: CbzPassword,
        getManga: GetManga,
        getChapter: GetChapter,
        getCategories: GetCategories,
        folderOps: DownloadFolderOps,
        cleanup: DownloadCleanup,
        store: DownloadStore,
    ) : this(dirs, paths, sourceManager, preferences, password, getManga, getChapter, getCategories) {
        this.folderOps = folderOps
        this.cleanup = cleanup
        this.store = store
    }

    internal fun activeJob(): Job? = synchronized(lock) { job }

    internal fun isWaitingFor(waiter: Job, stoppingJob: Job): Boolean = cleanup.isWaitingFor(waiter, stoppingJob)

    internal fun stoppingCount(): Int = cleanup.stoppingCount()

    // Internal so a test can put in a lock that records who asks for it: a Mutex does not show its waiters
    internal val entryLocks: ConcurrentHashMap<Long, Mutex> get() = cleanup.entryLocks
    internal fun entryLock(id: Long): Mutex = cleanup.entryLock(id)

    // Internal so a test can put in a lock that records who asks for it
    internal val renameLocks: ConcurrentHashMap<Long, Mutex> get() = folderOps.renameLocks

    /** Runs [block] holding entry [mangaId]'s rename lock (see [renameLocks]). */
    suspend fun <T> withRenameLock(mangaId: Long, block: suspend () -> T): T =
        folderOps.withRenameLock(mangaId, block)

    /** What is in each entry's download folder, read once and checked again by its modified time */
    internal val index: DownloadIndex get() = folderOps.index

    /** How many folders were listed, for the benchmark. */
    internal val foldersRead: Int get() = folderOps.foldersRead

    /** Moves a download folder; tests make it fail. Never replaces anything (no REPLACE_EXISTING). */
    internal var moveFolder: (java.nio.file.Path, java.nio.file.Path) -> Unit
        get() = folderOps.moveFolder
        set(value) {
            folderOps.moveFolder = value
        }

    // Downloaded chapters --------------------------------------------------------------------

    /**
     * The chapter's download, a folder with files in it or a CBZ with bytes in it, in the entry's
     * folder (or under the name the other non-ASCII setting gives it). Reads the folder on a cache
     * miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun findChapter(manga: Manga, chapter: Chapter): File? = folderOps.findChapter(manga, chapter)

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun isDownloaded(manga: Manga, chapter: Chapter): Boolean = folderOps.isDownloaded(manga, chapter)

    /** Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models). */
    fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> = folderOps.downloadedIds(manga, chapters)

    /**
     * How many chapters of [manga] are downloaded, for the library's download badge and filter:
     * folders with files and CBZs with bytes, a chapter with both counted once; unfinished
     * downloads, covers and other files are not chapters.
     * Reads the entry's download folder on a cache miss: call off the UI thread (use [enqueueAll] from screen models).
     */
    fun downloadCount(manga: Manga): Int = folderOps.downloadCount(manga)

    /**
     * [downloadCount] of each of [mangas], for the library's badges and the statistics: the folders
     * are read on several threads at once (the common pool, one per core), as a first read is mostly
     * waiting for the disk. An entry whose folder cannot be read counts 0.
     * Reads the folders on a cache miss: call off the UI thread.
     */
    fun downloadCounts(mangas: List<Manga>): Map<Long, Int> = folderOps.downloadCounts(mangas)

    /**
     * Whether the downloads of [a] and [b] go to the same folder: sources with the same name and
     * language, and titles that give the same folder name. Deleting one's chapters there could then
     * delete the other's.
     */
    fun sharesDownloadFolder(a: Manga, b: Manga): Boolean = folderOps.sharesDownloadFolder(a, b)

    /**
     * Gives a downloaded chapter the name its new name or scanlator calls for, as Android's
     * renameChapter after a source sync, so the download keeps showing and Clean up downloaded
     * chapters does not take it for an unknown file.
     *
     * Never replaces anything: when a download of the new name exists, or the move fails, the
     * download keeps its old name and this returns false. True when renamed or nothing to rename.
     */
    fun renameChapter(manga: Manga, oldChapter: Chapter, newChapter: Chapter): Boolean =
        folderOps.renameChapter(manga, oldChapter, newChapter)

    /** A download folder a rename moved: [from] is where it was, [to] where it is now. */
    data class FolderMove(val from: File, val to: File)

    /**
     * Moves an entry's download folders to the names [newTitle] gives them, as Android's renameManga
     * ([renameMangaFolders]). True when they moved or there was nothing to move.
     */
    suspend fun renameManga(manga: Manga, newTitle: String): Boolean = folderOps.renameManga(manga, newTitle)

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
    suspend fun renameMangaFolders(manga: Manga, newTitle: String): List<FolderMove>? =
        folderOps.renameMangaFolders(manga, newTitle)

    /**
     * Moves back what [renameMangaFolders] moved, the last move first, when the new title could not
     * be saved: each folder to the name it had, never over anything on disk.
     *
     * @return whether every folder is back
     */
    suspend fun moveFoldersBack(moves: List<FolderMove>): Boolean = folderOps.moveFoldersBack(moves)

    /** Forgets what was read from disk, for example after the storage folder moved. */
    fun invalidateCache() = folderOps.invalidateCache()

    /**
     * Deletes the downloads of [chapters] (each name they may have, and unfinished downloads), then
     * the entry's folder when nothing is left in it. Never touches other files.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun delete(manga: Manga, chapters: List<Chapter>) = cleanup.delete(manga, chapters)

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
     * Deletes the downloads of [chapters], all of an entry's chapters, and takes them out of the
     * queue, as Android's deleteManga(removeQueued = true). Unlike Android it deletes only those
     * chapters' files (and unfinished downloads of them): an entry whose title gives the same folder
     * name as another entry's, or files copied in by hand, are kept. The folder, and then the
     * source's folder, go once empty.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun deleteManga(manga: Manga, chapters: List<Chapter>) = cleanup.deleteManga(manga, chapters)

    /**
     * Which of [chapters] the automatic deletions may delete, as Android's getChaptersToDelete:
     * read chapters of an entry in an excluded category stay ("Exclude categories"), and so do
     * bookmarked chapters unless "Allow deleting bookmarked chapters" is on.
     */
    suspend fun chaptersToDelete(manga: Manga, chapters: List<Chapter>): List<Chapter> =
        cleanup.chaptersToDelete(manga, chapters)

    /**
     * Deletes the downloads of read [chapters] the way an automatic deletion does (see
     * [chaptersToDelete]), each from the entry it belongs to, which for a merged entry is a child.
     */
    suspend fun deleteRead(chapters: List<Chapter>) = cleanup.deleteRead(chapters)

    /**
     * After chapters are marked as read by hand (not by reading): deletes their downloads when
     * "After manually marked as read" is on, as Android's SetReadStatus.
     */
    suspend fun afterMarkedRead(chapters: List<Chapter>) = cleanup.afterMarkedRead(chapters)

    private fun queuedIds(): Set<Long> = _queue.value.mapTo(HashSet()) { it.chapter.id }

    private fun queuedMangaIds(): Set<Long> = _queue.value.mapTo(HashSet()) { it.manga.id }

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
    ): CleanupPlan = cleanup.planCleanup(removeRead, removeNonFavorite, allManga, chaptersOf, protectedIds)

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
        cleanup.applyCleanup(plan, orphansToDelete, toTrash)

    /** [applyCleanup], with what it could not delete and what it left because it changed. */
    suspend fun applyCleanupResult(
        plan: CleanupPlan,
        orphansToDelete: Set<File> = emptySet(),
        toTrash: Boolean = false,
    ): CleanupResult = cleanup.applyCleanupResult(plan, orphansToDelete, toTrash)

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
    ): Int = cleanup.cleanup(removeRead, removeNonFavorite, allManga, chaptersOf)

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
        store.save()
        if (start) start()
    }

    /**
     * Starts downloading, retrying failed chapters. A running queue picks them up as it goes. Saved
     * downloads that a failed database read held back at start-up are read again first.
     */
    fun start() {
        store.retryHeldIfNeeded()
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
        val previous = cleanup.stoppingSnapshot()
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                cleanup.awaitStopping(previous)
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
            _running.value = false
            cleanup.cancelAndTrack(current)
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
            val heldChanged = store.cancelHeld(chapterIds)
            removed = _queue.value.filter { it.chapter.id in chapterIds }
            if (removed.isEmpty()) {
                if (heldChanged) store.save()
                return
            }
            restart = job != null && removed.any { it.state.value == Download.State.DOWNLOADING }
            if (restart) pause()
            _queue.update { q -> q.filterNot { it.chapter.id in chapterIds } }
            cleanup.stoppingSnapshot()
        }
        cleanup.deleteCancelledTempFiles(stopped, removed)
        store.save()
        if (restart) start()
    }

    /** Cancels every download: those queued and those held back by a failed database read. */
    fun clearQueue() {
        cancel(_queue.value.mapTo(HashSet()) { it.chapter.id } + store.heldChapterIds())
    }

    /** Moves a queued download to the front. */
    fun moveToTop(download: Download) {
        // A download that finished meanwhile is not put back
        _queue.update { q -> if (download in q) listOf(download) + (q - download) else q }
        store.save()
    }

    /** Moves a queued download to the end. */
    fun moveToBottom(download: Download) {
        _queue.update { q -> if (download in q) (q - download) + download else q }
        store.save()
    }

    /** Moves every queued chapter of [mangaId] to the front or the end, in their order, as Android's "Move series to top". */
    fun moveSeries(mangaId: Long, toTop: Boolean) {
        _queue.update { q ->
            val (series, others) = q.partition { it.manga.id == mangaId }
            if (toTop) series + others else others + series
        }
        store.save()
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
        store.save()
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
            val dir = folderOps.resolveMangaDir(manga)
            // The gate is the disk, not the cache: a chapter copied in since the folder was listed
            // is kept as it is. The entry's lock keeps a clean-up away from the files being written.
            entryLock(manga.id).withLock {
                folderOps.invalidateEntryDirs(manga)
                if (findChapter(manga, chapter) != null) {
                    folderOps.invalidate(dir)
                } else {
                    val freeSpace = folderOps.usableSpace(dir)
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
                    folderOps.invalidate(dir)
                }
            }
            download.state.value = Download.State.DOWNLOADED
            _queue.update { it - download }
            store.save()
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

    /** Whether [manga]'s chapters can download: an installed online source, and not a merged entry itself. */
    private fun canDownload(manga: Manga): Boolean =
        manga.source != MERGED_SOURCE_ID && sourceManager.get(manga.source) is HttpSource

    /**
     * Loads the saved queue and starts it. Called once at start-up. A download whose entry or chapter
     * was deleted is dropped; when the database cannot be read, the downloads from there on stay in
     * the saved file and the user is told. They are read again when the queue next starts, in this run.
     */
    suspend fun restore(start: Boolean = true) = store.restore(start)

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
