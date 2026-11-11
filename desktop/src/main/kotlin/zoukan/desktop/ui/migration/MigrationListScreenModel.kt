package zoukan.desktop.ui.migration

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import exh.source.MERGED_SOURCE_ID
import exh.util.ThrottleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.migration.MigrateManga
import zoukan.desktop.ui.common.Toasts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Android app's MigrationListScreenModel: searches the chosen sources, in priority order, for
 * each entry, one entry at a time; then migrates or copies every entry that found a match.
 *
 * [runManually] skips the automatic search (a single entry without smart search), so the entry
 * waits for a manual pick.
 */
class MigrationListScreenModel(
    mangaIds: List<Long>,
    extraSearchQuery: String?,
    runManually: Boolean = false,
    private val preferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val refreshManga: RefreshManga = Injekt.get(),
    private val migrateManga: MigrateManga = Injekt.get(),
) : StateScreenModel<MigrationListScreenModel.State>(State()) {

    data class ChapterInfo(val latestChapter: Double?, val chapterCount: Int)

    sealed interface SearchResult {
        data object Searching : SearchResult
        data object NotFound : SearchResult
        data class Success(val manga: Manga, val chapters: ChapterInfo, val source: String) : SearchResult
    }

    data class Item(
        val manga: Manga,
        val chapters: ChapterInfo,
        val source: String,
        val result: SearchResult = SearchResult.Searching,
        /** The search or pick that may set [result]; a newer one, Stop searching or a pick makes older ones stale */
        val attempt: Long = 0,
    )

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog

        data class Progress(val done: Int, val total: Int) : Dialog

        data object Exit : Dialog
    }

    data class State(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        val dialog: Dialog? = null,
        /** Set when every entry left the list, or the migration finished: the screen closes */
        val finished: Boolean = false,
    ) {
        val finishedCount: Int get() = items.count { it.result != SearchResult.Searching }
        val migrationComplete: Boolean
            get() = items.isNotEmpty() &&
                items.all { it.result != SearchResult.Searching } &&
                items.any { it.result is SearchResult.Success }
    }

    private val smartSearchEngine = SmartSourceSearchEngine(extraSearchQuery)
    private val throttleManager = ThrottleManager()
    private val searchJobs = ConcurrentHashMap<Long, Deferred<Pair<Manga, ChapterInfo>?>>()
    private val pickJobs = ConcurrentHashMap<Long, Job>()
    private val attempts = AtomicLong()
    private var migrateJob: Job? = null

    private val hideUnmatched = preferences.migrationHideUnmatched().get()
    private val hideWithoutUpdates = preferences.migrationHideWithoutUpdates().get()
    private val prioritizeByChapters = preferences.migrationPrioritizeByChapters().get()
    private val deepSearchMode = preferences.migrationDeepSearchMode().get()

    init {
        screenModelScope.launch(Dispatchers.IO) {
            val items = mangaIds.mapNotNull { id ->
                val manga = getManga.await(id) ?: return@mapNotNull null
                Item(
                    manga = manga,
                    chapters = chapterInfo(id),
                    source = sourceName(manga),
                    result = if (runManually) SearchResult.NotFound else SearchResult.Searching,
                )
            }
            mutableState.update { it.copy(loading = false, items = items, finished = items.isEmpty()) }
            if (!runManually) runSearches(items.map { it.manga.id })
        }
        screenModelScope.launch {
            MigrationPicks.picks.collect { pick ->
                useMangaForMigration(pick.current, pick.target) {
                    Toasts.show(localized(MR.strings.migrationListScreen_matchWithoutChapterToast))
                }
            }
        }
    }

    private suspend fun chapterInfo(mangaId: Long) = getChaptersByMangaId.await(mangaId).let { chapters ->
        ChapterInfo(latestChapter = chapters.maxOfOrNull { it.chapterNumber }, chapterCount = chapters.size)
    }

    private suspend fun sourceName(manga: Manga): String {
        val source = sourceManager.getOrStub(manga.source)
        if (manga.source != MERGED_SOURCE_ID) return source.toString()
        // As getNameForMangaInfo: a merged entry names the sources it merges
        return sourceManager.getMergedSources(manga.id).joinToString { it.name }.ifEmpty { source.toString() }
    }

    private fun item(mangaId: Long) = state.value.items.find { it.manga.id == mangaId }

    /** Starts a new attempt for an entry: its result is [result] until the attempt sets another. */
    private fun startAttempt(mangaId: Long, result: SearchResult): Long {
        val attempt = attempts.incrementAndGet()
        mutableState.update { s ->
            s.copy(items = s.items.map { if (it.manga.id == mangaId) it.copy(result = result, attempt = attempt) else it })
        }
        return attempt
    }

    /** Sets an entry's result if [attempt] is still its latest; false when it is stale (or the entry left). */
    private fun setResult(mangaId: Long, attempt: Long, result: SearchResult): Boolean {
        var applied = false
        mutableState.update { s ->
            applied = false
            s.copy(
                items = s.items.map {
                    if (it.manga.id == mangaId && it.attempt == attempt) {
                        applied = true
                        it.copy(result = result)
                    } else {
                        it
                    }
                },
            )
        }
        return applied
    }

    /** Cancels an entry's search and pick in flight. */
    private fun cancelJobs(mangaId: Long) {
        searchJobs.remove(mangaId)?.cancel()
        pickJobs.remove(mangaId)?.cancel()
    }

    private fun removeItem(mangaId: Long) {
        mutableState.update { s ->
            val items = s.items.filterNot { it.manga.id == mangaId }
            s.copy(items = items, finished = items.isEmpty())
        }
    }

    private suspend fun runSearches(ids: List<Long>) = coroutineScope {
        throttleManager.resetThrottle()
        val sources = preferences.migrationSources().get().mapNotNull { sourceManager.get(it) }
        for (id in ids) {
            ensureActive()
            val item = item(id) ?: continue
            // Skipped: stopped, or a manual pick is on its way
            if (item.result != SearchResult.Searching || pickJobs.containsKey(id)) continue

            // The entry's current attempt: a pick or Stop searching from here on makes this search stale
            val attempt = item.attempt
            val search = async { searchSources(item.manga, sources) }
            searchJobs[id] = search
            val found = try {
                search.await()
            } catch (e: CancellationException) {
                // A cancelled entry (Stop searching) waits for a manual pick; the screen closing ends the loop
                ensureActive()
                continue
            } finally {
                searchJobs.remove(id, search)
            }

            if (found != null && found.first.thumbnailUrl == null) {
                runCatching { refreshManga(found.first, fetchDetails = true, fetchChapters = false) }
            }
            val result = found?.let { (manga, chapters) -> success(getManga.await(manga.id) ?: manga, chapters) }
            // A pick or Stop searching since the search began wins
            if (!setResult(id, attempt, result ?: SearchResult.NotFound)) continue

            if (result == null && hideUnmatched) removeItem(id)
            if (result != null &&
                hideWithoutUpdates &&
                (result.chapters.latestChapter ?: 0.0) <= (item.chapters.latestChapter ?: 0.0)
            ) {
                removeItem(id)
            }
        }
    }

    private fun success(manga: Manga, chapters: ChapterInfo) =
        SearchResult.Success(manga, chapters, sourceManager.getOrStub(manga.source).toString())

    private suspend fun searchSources(manga: Manga, sources: List<Source>): Pair<Manga, ChapterInfo>? = coroutineScope {
        if (prioritizeByChapters) {
            // The match furthest ahead, from any source
            val permits = Semaphore(5)
            sources.map { source ->
                async {
                    permits.withPermit {
                        searchSource(manga, source)?.takeIf { it.second.chapterCount > 0 }
                    }
                }
            }.awaitAll().filterNotNull().maxByOrNull { it.second.latestChapter ?: 0.0 }
        } else {
            // The first match, by source priority
            var found: Pair<Manga, ChapterInfo>? = null
            for (source in sources) {
                found = searchSource(manga, source)
                if (found != null) break
            }
            found
        }
    }

    private suspend fun searchSource(manga: Manga, source: Source): Pair<Manga, ChapterInfo>? = try {
        val result = if (deepSearchMode) {
            smartSearchEngine.deepSearch(source, manga.title)
        } else {
            smartSearchEngine.regularSearch(source, manga.title)
        }
        if (result == null || (result.url == manga.url && source.id == manga.source)) {
            null
        } else {
            val local = networkToLocalManga(result)
            try {
                refreshManga(local, fetchDetails = !local.initialized, fetchChapters = true, source = source, throttle = throttleManager::throttle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
            local to chapterInfo(local.id)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Migration search failed in ${source.name}" }
        null
    }

    /** Uses an entry picked in a manual search as [current]'s target; [onMissingChapters] when it has none. */
    fun useMangaForMigration(current: Long, target: Long, onMissingChapters: () -> Unit) {
        if (item(current) == null) return
        cancelJobs(current)
        val attempt = startAttempt(current, SearchResult.Searching)
        // Registered before it starts, so the search loop never misses it
        lateinit var job: Job
        job = screenModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val manga = getManga.await(target)
            val refreshed = manga?.let {
                try {
                    refreshManga(it, fetchDetails = !it.initialized, fetchChapters = true, throttle = throttleManager::throttle).manga
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e)
                    null
                }
            }
            val chapters = refreshed?.let { chapterInfo(it.id) }
            if (refreshed == null || chapters == null || chapters.chapterCount == 0) {
                if (setResult(current, attempt, SearchResult.NotFound)) onMissingChapters()
            } else {
                setResult(current, attempt, success(refreshed, chapters))
            }
            pickJobs.remove(current, job)
        }
        pickJobs[current] = job
        job.start()
    }

    /** Stops searching for an entry, leaving it in the list for a manual search. */
    fun cancelSearch(mangaId: Long) {
        cancelJobs(mangaId)
        if (item(mangaId)?.result == SearchResult.Searching) startAttempt(mangaId, SearchResult.NotFound)
    }

    /** Takes an entry out of the migration ("Don't migrate"). */
    fun removeManga(mangaId: Long) {
        cancelJobs(mangaId)
        removeItem(mangaId)
    }

    fun migrateNow(mangaId: Long, replace: Boolean, onError: (Throwable) -> Unit) {
        val item = item(mangaId) ?: return
        val target = (item.result as? SearchResult.Success)?.manga ?: return
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { migrateManga(item.manga, target, replace) }
                .onSuccess { removeItem(mangaId) }
                .onFailure { if (it is CancellationException) throw it else onError(it) }
        }
    }

    fun showMigrateDialog(copy: Boolean) = mutableState.update { s ->
        s.copy(
            dialog = Dialog.Migrate(
                copy = copy,
                totalCount = s.items.size,
                skippedCount = s.items.count { it.result == SearchResult.NotFound },
            ),
        )
    }

    fun showExitDialog() = mutableState.update { it.copy(dialog = Dialog.Exit) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    /** Migrates (or with [replace] off, copies) every entry that has a match, as the Android app. */
    fun migrateAll(replace: Boolean, onDone: (migrated: Int, failed: Int) -> Unit) {
        // A double click on the confirm button starts one migration
        if (migrateJob?.isActive == true) return
        migrateJob = screenModelScope.launch(Dispatchers.IO) {
            val items = state.value.items
            var migrated = 0
            var failed = 0
            mutableState.update { it.copy(dialog = Dialog.Progress(0, items.size)) }
            try {
                items.forEachIndexed { index, item ->
                    ensureActive()
                    val target = (item.result as? SearchResult.Success)?.manga
                    if (target != null) {
                        try {
                            migrateManga(item.manga, target, replace)
                            migrated++
                            // Done ones leave the list, so a Migrate after Cancel does not repeat them
                            removeItem(item.manga.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed++
                            logcat(LogPriority.WARN, e) { "Migrating ${item.manga.title} failed" }
                        }
                    }
                    mutableState.update { it.copy(dialog = Dialog.Progress(index + 1, items.size)) }
                }
                mutableState.update { it.copy(finished = true) }
            } finally {
                mutableState.update { it.copy(dialog = null) }
                migrateJob = null
                onDone(migrated, failed)
            }
        }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        migrateJob = null
    }
}
