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
import zoukan.desktop.domain.toSChapter
import zoukan.desktop.tray.Notifications
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

    /** The file names in each manga folder, read once and kept up to date by this class */
    private val cache = ConcurrentHashMap<File, Set<String>>()

    // Downloaded chapters --------------------------------------------------------------------

    private fun namesIn(mangaDir: File): Set<String> =
        cache.getOrPut(mangaDir) { mangaDir.list()?.toHashSet().orEmpty() }

    fun isDownloaded(manga: Manga, chapter: Chapter): Boolean {
        val names = namesIn(paths.mangaDir(manga))
        if (names.isEmpty()) return false
        return paths.validChapterNames(chapter).any { it in names }
    }

    fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> {
        val names = namesIn(paths.mangaDir(manga))
        if (names.isEmpty()) return emptySet()
        return chapters.filter { c -> paths.validChapterNames(c).any { it in names } }.mapTo(HashSet()) { it.id }
    }

    /** How many chapters of [manga] are downloaded, for the library's download badge and filter. */
    fun downloadCount(manga: Manga): Int =
        namesIn(paths.mangaDir(manga)).count { name ->
            !name.endsWith(DownloadPaths.TMP_SUFFIX) && !name.startsWith(".") && NOT_CHAPTER.none { name.endsWith(it, ignoreCase = true) }
        }

    fun findChapter(manga: Manga, chapter: Chapter): File? =
        if (isDownloaded(manga, chapter)) paths.findChapter(manga, chapter) else null

    /** Forgets what was read from disk, for example after the storage folder moved. */
    fun invalidateCache() {
        cache.clear()
        _changes.update { it + 1 }
    }

    private fun invalidate(mangaDir: File) {
        cache.remove(mangaDir)
        _changes.update { it + 1 }
    }

    suspend fun delete(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        cancel(chapters.map { it.id }.toSet())
        // A cancelled download may still be writing its last file; delete after it stopped, or the
        // chapter would come back
        synchronized(lock) { stopping }?.join()
        val dir = paths.mangaDir(manga)
        chapters.forEach { chapter ->
            paths.validChapterNames(chapter).forEach { name ->
                File(dir, name).takeIf { it.exists() }?.deleteRecursively()
            }
            File(dir, paths.chapterDirName(chapter) + DownloadPaths.TMP_SUFFIX).takeIf { it.exists() }?.deleteRecursively()
        }
        if (dir.list()?.isEmpty() == true) dir.delete()
        invalidate(dir)
    }

    /**
     * Which of [chapters] the automatic deletions may delete, as Android's getChaptersToDelete:
     * read chapters of an entry in an excluded category stay ("Exclude categories"), and so do
     * bookmarked chapters unless "Allow deleting bookmarked chapters" is on.
     */
    suspend fun chaptersToDelete(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val excluded = preferences.removeExcludeCategories().get().mapNotNull(String::toLongOrNull).toSet()
        val categories = runCatching { getCategories.await(manga.id).map { it.id } }.getOrDefault(emptyList()).ifEmpty { listOf(0L) }
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

    // Queue -----------------------------------------------------------------------------------

    /** Adds [chapters] to the queue, unless downloaded or queued already. */
    fun enqueue(manga: Manga, chapters: List<Chapter>, start: Boolean = true) {
        if (chapters.isEmpty()) return
        val queued = _queue.value.mapTo(HashSet()) { it.chapter.id }
        val added = chapters
            .filterNot { it.id in queued || isDownloaded(manga, it) }
            // Oldest first, as they are read
            .sortedByDescending { it.sourceOrder }
            .map { Download(manga, it) }
        if (added.isEmpty()) return
        _queue.update { it + added }
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
        val removed = _queue.value.filter { it.chapter.id in chapterIds }
        if (removed.isEmpty()) return
        val restart = synchronized(lock) {
            (job != null && removed.any { it.state.value == Download.State.DOWNLOADING }).also { if (it) pause() }
        }
        _queue.update { q -> q.filterNot { it.chapter.id in chapterIds } }
        val stopped = synchronized(lock) { stopping }
        scope.launch {
            // After the cancelled download let go of its files
            stopped?.join()
            removed.forEach { d ->
                val dir = paths.mangaDir(d.manga)
                File(dir, paths.chapterDirName(d.chapter) + DownloadPaths.TMP_SUFFIX).deleteRecursively()
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
                            if (download !in _queue.value || download.state.value != Download.State.QUEUED) continue
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
                "Downloads finished",
                buildString {
                    append("$done chapters downloaded")
                    if (errors > 0) append(", $errors failed")
                },
                if (errors > 0) Notifications.Kind.WARNING else Notifications.Kind.INFO,
            )
        }
    }

    /** @return whether it downloaded */
    private suspend fun download(download: Download): Boolean {
        val manga = download.manga
        val chapter = download.chapter
        download.state.value = Download.State.DOWNLOADING
        return try {
            val source = sourceManager.get(manga.source) as? HttpSource
                ?: error("The source of ${manga.title} is not installed")
            val dir = paths.mangaDir(manga)
            if (!isDownloaded(manga, chapter)) {
                val freeSpace = dir.parentFile?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull(File::exists) }?.usableSpace
                if (freeSpace != null && freeSpace in 1 until MIN_FREE_SPACE) error("Not enough free disk space")
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
                val options = ChapterDownloader.Options(
                    asCbz = preferences.saveChaptersAsCBZ().get(),
                    encryption = password.encryption(),
                    comicInfo = comicInfo(manga, chapter, source),
                    // ZOUKAN -->
                    splitTallImages = preferences.splitTallImages().get(),
                    // ZOUKAN <--
                )
                downloader.download(dir, paths.chapterDirName(chapter), options) { done, total ->
                    download.progress.value = done to total
                }
                invalidate(dir)
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
            download.error = e.message ?: e::class.simpleName
            download.state.value = Download.State.ERROR
            Notifications.post("Download failed", "${manga.title}: ${chapter.name}\n${download.error}", Notifications.Kind.ERROR)
            false
        }
    }

    private suspend fun comicInfo(manga: Manga, chapter: Chapter, source: HttpSource): ComicInfoXml {
        val categories = runCatching { getCategories.await(manga.id) }.getOrDefault(emptyList())
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
                password.comicInfoPadding()?.let { put("PaddingTachiyomiSY", it) }
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
                    java.nio.file.Files.move(tmp.toPath(), queueFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { logcat(LogPriority.WARN, it) { "Saving the download queue failed" } }
            }
        }
    }

    /** Loads the saved queue and starts it. Called once at start-up. */
    suspend fun restore(start: Boolean = true) {
        val saved = runCatching { json.decodeFromString<List<SavedDownload>>(queueFile.readText()) }.getOrNull() ?: return
        val mangas = HashMap<Long, Manga?>()
        val downloads = saved.mapNotNull { s ->
            val manga = mangas.getOrPut(s.mangaId) { getManga.await(s.mangaId) } ?: return@mapNotNull null
            val chapter = getChapter.await(s.chapterId) ?: return@mapNotNull null
            Download(manga, chapter)
        }
        val queued = _queue.value.mapTo(HashSet()) { it.chapter.id }
        _queue.update { it + downloads.filterNot { d -> d.chapter.id in queued } }
        if (start) start()
    }

    companion object {
        private const val MIN_FREE_SPACE = 200L * 1024 * 1024
        private val NOT_CHAPTER = listOf(".xml", ".json", ".jpg", ".jpeg", ".png", ".webp", ".txt")
    }
}
