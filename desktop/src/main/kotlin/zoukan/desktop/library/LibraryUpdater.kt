package zoukan.desktop.library

import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import exh.source.MERGED_SOURCE_ID
import exh.source.mangaDexSourceIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.tray.Notifications
import java.io.File
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The library update: the Android app's LibraryUpdateJob for desktop. It checks the library's
 * entries for new chapters with the same category and skip rules, up to five sources at once and one
 * entry at a time per source, queues new chapters for download when that is on, and writes the
 * errors to a file and, as Komikku does, to the database for the update errors screen.
 */
class LibraryUpdater(
    private val dirs: ZoukanDirs,
    private val getLibraryManga: GetLibraryManga,
    private val refreshManga: RefreshManga,
    private val sourceManager: SourceManager,
    private val preferences: LibraryPreferences,
    private val fetchInterval: FetchInterval,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val downloadManager: DownloadManager,
    private val errors: LibraryUpdateErrors,
    // ZOUKAN -->
    private val getManga: GetManga,
    private val getMergedMangaForDownloading: GetMergedMangaForDownloading,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val trackerManager: TrackerManager,
    private val syncPreferences: SyncPreferences,
    private val syncManager: () -> SyncManager,
    // ZOUKAN <--
) {
    data class Progress(val done: Int, val total: Int, val current: List<String>)

    data class Failure(val manga: Manga, val message: String)

    data class Result(
        val updated: Int,
        val newChapters: Int,
        val entriesWithNewChapters: List<Manga>,
        val queuedDownloads: Int,
        val skipped: List<Pair<Manga, String>>,
        val failures: List<Failure>,
        val errorFile: File?,
        val entries: List<Manga> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Null when no update is running */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts an update unless one is running.
     *
     * @param categoryId only this category's entries, or the whole library (with the category
     * settings) when null
     * @param mangaIds only these entries, without the skip rules but "Not set to always update" (a
     * retry from the update errors screen); overrides [categoryId]
     * @param groupIds a source, status or tracking tab's entries, with the skip rules, as Android's
     * group update; overrides [categoryId]
     * @param manual a manual update, which syncs the data first when sync is on, as Android's
     * startNow; false for the scheduled update
     * @return whether it started
     */
    fun start(
        categoryId: Long? = null,
        mangaIds: Set<Long>? = null,
        groupIds: Set<Long>? = null,
        manual: Boolean = true,
        onDone: (Result) -> Unit = {},
    ): Boolean {
        synchronized(this) {
            if (job?.isActive == true) return false
            // ZOUKAN -->
            // Always sync the data before a manual library update when syncing is on; not while a
            // sync runs, as on Android
            val syncFirst = manual && isSyncEnabled()
            if (syncFirst && syncManager().running.value) return false
            // ZOUKAN <--
            _progress.value = Progress(0, 0, emptyList())
            job = scope.launch {
                try {
                    // ZOUKAN -->
                    if (syncFirst) syncManager().syncData()
                    // ZOUKAN <--
                    val result = run(categoryId, mangaIds = mangaIds, groupIds = groupIds)
                    _lastResult.value = result
                    notify(result)
                    onDone(result)
                } catch (e: CancellationException) {
                    // Cancelled by the user or at quit: not a failure
                    logcat { "The library update was cancelled" }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "The library update failed" }
                    Notifications.post("Library update failed", e.message ?: e::class.simpleName.orEmpty(), Notifications.Kind.ERROR)
                } finally {
                    _progress.value = null
                }
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    suspend fun join() {
        job?.join()
    }

    private fun isSyncEnabled(): Boolean =
        SyncManager.SyncService.fromInt(syncPreferences.syncService().get()) in SyncManager.SUPPORTED

    /** Runs an update in the calling coroutine; for the command line. */
    suspend fun run(
        categoryId: Long? = null,
        ignoreRestrictions: Boolean = false,
        mangaIds: Set<Long>? = null,
        groupIds: Set<Long>? = null,
    ): Result {
        preferences.lastUpdatedTimestamp().set(System.currentTimeMillis())
        runCatching { errors.clean() }.onFailure { logcat(LogPriority.WARN, it) { "Cleaning the update errors failed" } }
        val library = getLibraryManga.await().distinctBy { it.manga.id }
        val (selected, skipped) = if (mangaIds != null) {
            // KMK: a targeted update keeps only "Not set to always update" of the skip rules
            library.filter {
                it.manga.id in mangaIds &&
                    !(it.manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && it.totalChapters > 0L)
            } to emptyList()
        } else {
            select(library, categoryId, ignoreRestrictions, groupIds)
        }
        // ZOUKAN -->
        // SY: E-Hentai, ExHentai, nhentai and Pururin are left out of the library update; the
        // E-Hentai gallery updates check E-Hentai galleries
        val toUpdate = selected.filterNot { it.manga.source in LIBRARY_UPDATE_EXCLUDED_SOURCES }
        // ZOUKAN <--
        val total = toUpdate.size
        _progress.value = Progress(0, total, emptyList())

        val failures = mutableListOf<Failure>()
        val withNew = mutableListOf<Manga>()
        var newChapters = 0
        var queued = 0
        var done = 0
        val current = linkedSetOf<String>()
        val lock = Any()

        // SY -->
        val mdList = trackerManager.mdList
        val mdListLogged = mdList.isLoggedIn
        // SY <--

        val semaphore = Semaphore(PARALLEL_SOURCES)
        coroutineScope {
            toUpdate.groupBy { it.manga.source }.values.map { entries ->
                async {
                    semaphore.withPermit {
                        // SY -->
                        // MangaDex entries get their MDList track while signed in to MDList
                        if (mdListLogged && entries.firstOrNull()?.let { it.manga.source in mangaDexSourceIds } == true) {
                            launch {
                                entries.forEach { (manga) ->
                                    try {
                                        val tracks = getTracks.await(manga.id)
                                        if (tracks.isEmpty() || tracks.none { it.trackerId == TrackerManager.MDLIST }) {
                                            val track = mdList.createInitialTracker(manga)
                                            insertTrack.await(mdList.refresh(track).toDomainTrack(false)!!)
                                        }
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        logcat(LogPriority.ERROR, e) { "Error adding initial track for ${manga.title}" }
                                    }
                                }
                            }
                        }
                        // SY <--
                        for (item in entries) {
                            val manga = item.manga
                            ensureActive()
                            // Don't continue to update if the entry left the library
                            if (getManga.await(manga.id)?.favorite != true) {
                                _progress.value = synchronized(lock) {
                                    done++
                                    Progress(done, total, current.toList())
                                }
                                continue
                            }
                            _progress.value = synchronized(lock) {
                                current += manga.title
                                Progress(done, total, current.toList())
                            }
                            try {
                                if (sourceManager.get(manga.source) == null) throw SourceNotFoundException()
                                val result = refreshManga(
                                    manga,
                                    fetchDetails = preferences.autoUpdateMetadata().get(),
                                    fetchChapters = true,
                                )
                                // Only an entry still in the library has new chapters, as Android's updateManga
                                val newOnes = if (result.manga.favorite) result.newChapters.sortedByDescending { it.sourceOrder } else emptyList()
                                if (newOnes.isNotEmpty()) {
                                    val toDownload = filterChaptersForDownload.await(result.manga, newOnes)
                                    if (toDownload.isNotEmpty()) downloadChapters(result.manga, toDownload)
                                    synchronized(lock) {
                                        newChapters += newOnes.size
                                        queued += toDownload.size
                                        withNew += result.manga
                                    }
                                    synchronized(lock) { preferences.newUpdatesCount().set(preferences.newUpdatesCount().get() + newOnes.size) }
                                }
                                try {
                                    errors.clear(manga.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    logcat(LogPriority.WARN, e) { "Clearing the update error of ${manga.title} failed" }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                val message = errorMessage(e)
                                logcat(LogPriority.WARN, e) { "Updating ${manga.title} failed" }
                                synchronized(lock) { failures += Failure(manga, message) }
                                try {
                                    errors.record(manga.id, message)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    logcat(LogPriority.WARN, e) { "Saving the update error of ${manga.title} failed" }
                                }
                            } finally {
                                _progress.value = synchronized(lock) {
                                    current -= manga.title
                                    done++
                                    Progress(done, total, current.toList())
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        // Downloads start once the update ends, as on Android: sites may not like both at once
        if (queued > 0) downloadManager.start()

        val errorFile = if (failures.isNotEmpty()) writeErrors(failures) else null
        return Result(total, newChapters, withNew, queued, skipped, failures, errorFile, toUpdate.map { it.manga })
    }

    /**
     * Queues new chapters without starting the downloads; a merged entry's chapters go under the
     * entries it merges, which own them, as Android's downloadChapters (SY).
     */
    private suspend fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        if (manga.source == MERGED_SOURCE_ID) {
            val downloadingManga = getMergedMangaForDownloading.await(manga.id).associateBy { it.id }
            chapters.groupBy { it.mangaId }.forEach { (id, list) ->
                downloadManager.enqueue(downloadingManga[id] ?: return@forEach, list, start = false)
            }
            return
        }
        downloadManager.enqueue(manga, chapters, start = false)
    }

    /**
     * The entries to update and the skipped ones with why, by the library update settings.
     *
     * @param groupIds a source, status or tracking tab's entries in place of the categories
     */
    internal fun select(
        library: List<LibraryManga>,
        categoryId: Long?,
        ignoreRestrictions: Boolean = false,
        groupIds: Set<Long>? = null,
    ): Pair<List<LibraryManga>, List<Pair<Manga, String>>> {
        val inCategories = if (groupIds != null) {
            library.filter { it.manga.id in groupIds }
        } else if (categoryId != null) {
            library.filter { categoryId in it.categories || (categoryId == 0L && it.categories.isEmpty()) }
        } else {
            val included = preferences.updateCategories().get().mapNotNull { it.toLongOrNull() }.toSet()
            val excluded = preferences.updateCategoriesExclude().get().mapNotNull { it.toLongOrNull() }.toSet()
            library.filter { item ->
                val categories = item.categories.ifEmpty { listOf(0L) }
                (included.isEmpty() || categories.any { it in included }) && categories.none { it in excluded }
            }
        }
        val restrictions = if (ignoreRestrictions) emptySet() else preferences.autoUpdateMangaRestrictions().get()
        val (_, windowEnd) = fetchInterval.getWindow(ZonedDateTime.now())
        val skipped = mutableListOf<Pair<Manga, String>>()
        val selected = inCategories.filter {
            val reason = when {
                !ignoreRestrictions && it.manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE && it.totalChapters > 0L ->
                    "Not set to always update"
                MANGA_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED -> "Completed"
                MANGA_HAS_UNREAD in restrictions && it.unreadCount != 0L -> "Has unread chapters"
                MANGA_NON_READ in restrictions && it.totalChapters > 0L && !it.hasStarted -> "Not started"
                MANGA_OUTSIDE_RELEASE_PERIOD in restrictions && it.manga.nextUpdate > windowEnd -> "Not expected to release yet"
                else -> null
            }
            if (reason != null) skipped += it.manga to reason
            reason == null
        }.sortedWith(
            compareByDescending<LibraryManga> { it.manga.nextUpdate <= windowEnd }
                .thenByDescending { it.lastRead > 0L }
                .thenByDescending { it.lastRead }
                .thenByDescending { it.manga.title },
        )
        return selected to skipped
    }

    private fun writeErrors(failures: List<Failure>): File? = runCatching {
        val file = File(dirs.reports, "library-update-errors.txt")
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("Zoukan library update, ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${Platform.id})")
                failures.groupBy { it.message }.forEach { (message, list) ->
                    appendLine()
                    appendLine("! $message")
                    list.forEach { f ->
                        appendLine("  - ${f.manga.title} (${sourceManager.getOrStub(f.manga.source)})")
                    }
                }
            },
        )
        file
    }.getOrNull()

    private fun notify(result: Result) {
        val text = buildString {
            if (result.newChapters > 0) {
                append("${result.newChapters} new chapters in ")
                append(result.entriesWithNewChapters.take(3).joinToString { it.title })
                if (result.entriesWithNewChapters.size > 3) append(" and ${result.entriesWithNewChapters.size - 3} more")
            } else {
                append("No new chapters")
            }
            if (result.failures.isNotEmpty()) append(". ${result.failures.size} entries failed to update")
        }
        val kind = if (result.failures.isNotEmpty()) Notifications.Kind.WARNING else Notifications.Kind.INFO
        Notifications.post("Library updated", text, kind)
    }

    private class SourceNotFoundException : Exception()

    companion object {
        private const val PARALLEL_SOURCES = 5

        /** The error's message as the Android update writes it, which groups the update errors screen. */
        internal fun errorMessage(e: Throwable): String = when (e) {
            is NoChaptersException -> "No chapters found"
            is SourceNotFoundException -> "Source not found"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
        }
    }
}
