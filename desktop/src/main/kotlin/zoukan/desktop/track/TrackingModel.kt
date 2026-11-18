package zoukan.desktop.track

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.base.TrackerIdMetadata
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.Toasts
import zoukan.desktop.ui.common.launchAction

/** One manga's tracking: the logged-in trackers, its tracks, search and binding, and edits. */
class TrackingModel(
    private val manga: Manga,
    private val trackerManager: TrackerManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val deleteTrack: DeleteTrack = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    // SY -->
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    // SY <--
) : StateScreenModel<TrackingModel.State>(State()) {

    data class Item(val tracker: Tracker, val track: Track?)

    data class State(
        val items: List<Item> = emptyList(),
        val searching: Tracker? = null,
        val query: String = "",
        val results: List<TrackSearch>? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    init {
        screenModelScope.launch {
            val source = sourceManager.getOrStub(manga.source)
            combine(getTracks.subscribe(manga.id), trackerManager.loggedInTrackersFlow()) { tracks, trackers ->
                trackers
                    // Enhanced trackers only work with their own sources
                    .filter { it !is EnhancedTracker || it.accept(source) }
                    .map { tracker -> Item(tracker, tracks.find { it.trackerId == tracker.id }) }
            }.collect { items -> mutableState.update { it.copy(items = items) } }
        }
    }

    /** The running search or match; a new search, or closing the search, cancels it. */
    private var searchJob: Job? = null

    fun startSearch(tracker: Tracker) {
        searchJob?.cancel()
        val track = state.value.items.find { it.tracker == tracker }?.track
        mutableState.update {
            it.copy(searching = tracker, query = track?.title ?: manga.ogTitle, results = null, loading = false, error = null)
        }
        when {
            // Enhanced trackers match by the source's ids instead of a search
            tracker is EnhancedTracker -> matchEnhanced(tracker)
            // SY's "Resolve with source metadata": the tracker's id in the entry's metadata. Only
            // for a new track: tracking a different entry would find the same one again
            track == null && trackPreferences.resolveUsingSourceMetadata().get() -> resolveFromMetadata(tracker)
            else -> search()
        }
    }

    // SY -->
    /**
     * Registers the tracker's entry whose id the manga's source metadata holds, as SY's
     * TrackInfoDialog newSearch; without one, or when it cannot be registered, searches instead.
     */
    private fun resolveFromMetadata(tracker: Tracker) {
        searchJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        searchJob = screenModelScope.launch {
            val registered = withContext(Dispatchers.IO) {
                val remoteId = trackerIdFromMetadata(tracker.id) ?: return@withContext false
                registerTrackingById(tracker, remoteId)
            }
            if (registered) {
                applyResult(tracker) { it.copy(searching = null, results = null, loading = false, error = null) }
            } else if (state.value.searching === tracker) {
                mutableState.update { it.copy(loading = false) }
                search()
            }
        }
    }

    /** The id of the entry on [trackerId] in the manga's source metadata, as SY's getTrackerIdFromMetadata. */
    private suspend fun trackerIdFromMetadata(trackerId: Long): String? = try {
        val metadataSource = sourceManager.get(manga.source)?.getMainSource<MetadataSource<*, *>>()
        val metadata = metadataSource?.let { getFlatMetadataById.await(manga.id)?.raise(it.metaClass) }
        TrackerMetadataIds.idFor(trackerId, metadata as? TrackerIdMetadata)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.rethrowIfVmFailure()
        logcat(LogPriority.ERROR, e) { "Failed to get tracker ID from metadata" }
        null
    }

    /** As SY's registerTrackingById; the registration finishes even when the search closes. */
    private suspend fun registerTrackingById(tracker: Tracker, remoteId: String): Boolean = try {
        val found = tracker.searchById(remoteId)
        if (found != null) withContext(NonCancellable) { tracker.register(found, manga.id) }
        found != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.rethrowIfVmFailure()
        logcat(LogPriority.ERROR, e) { "Failed to register tracking by id" }
        false
    }
    // SY <--

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        mutableState.update { it.copy(searching = null, results = null, loading = false, error = null) }
    }

    /** Stores a search's outcome only if [tracker] is still the one being searched; a late result is dropped. */
    internal fun applyResult(tracker: Any, transform: (State) -> State) {
        mutableState.update { if (it.searching === tracker) transform(it) else it }
    }

    private suspend fun <T> runSearch(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfVmFailure()
            Result.failure(e)
        }
    }

    fun search() {
        val tracker = state.value.searching ?: return
        val query = state.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        searchJob = screenModelScope.launch {
            val result = runSearch { tracker.search(query) }
            applyResult(tracker) {
                it.copy(
                    loading = false,
                    results = result.getOrNull().orEmpty(),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: e::class.simpleName },
                )
            }
        }
    }

    private fun matchEnhanced(tracker: EnhancedTracker) {
        searchJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        searchJob = screenModelScope.launch {
            val result = runSearch { tracker.match(manga) }
            applyResult(tracker) {
                it.copy(
                    loading = false,
                    results = listOfNotNull(result.getOrNull()),
                    error = result.exceptionOrNull()?.message ?: if (result.getOrNull() == null) "No match on this server" else null,
                )
            }
        }
    }

    fun bind(result: TrackSearch) {
        val tracker = state.value.searching ?: return
        closeSearch()
        screenModelScope.launch(Dispatchers.IO) {
            tracker.register(result, manga.id)
        }
    }

    /** Runs a change on the tracker's site; BaseTracker saves it and shows errors as toasts. */
    private fun edit(item: Item, block: suspend Tracker.(eu.kanade.tachiyomi.data.database.models.Track) -> Unit) {
        val track = item.track ?: return
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { item.tracker.block(track.toDbTrack()) }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Tracker update failed" }
                    Toasts.error("${item.tracker.name}", it)
                }
        }
    }

    fun setStatus(item: Item, status: Long) = edit(item) { setRemoteStatus(it, status) }

    fun setChapter(item: Item, chapter: Int) = edit(item) { setRemoteLastChapterRead(it, chapter) }

    fun setScore(item: Item, score: String) = edit(item) { setRemoteScore(it, score) }

    fun setStartDate(item: Item, epochMillis: Long) = edit(item) { setRemoteStartDate(it, epochMillis) }

    fun setFinishDate(item: Item, epochMillis: Long) = edit(item) { setRemoteFinishDate(it, epochMillis) }

    fun setPrivate(item: Item, private: Boolean) = edit(item) { setRemotePrivate(it, private) }

    fun refresh(item: Item) = edit(item) { track ->
        refresh(track).toDomainTrack(idRequired = true)?.let { insertTrack.await(it) }
    }

    /** Stops tracking here; [remote] also removes the entry from the user's list on the site. */
    fun remove(item: Item, remote: Boolean) {
        val track = item.track ?: return
        screenModelScope.launchAction(Dispatchers.IO) {
            if (remote) {
                runCatching { (item.tracker as? DeletableTracker)?.delete(track) }
                    .onFailure { Toasts.error("Could not remove it from ${item.tracker.name}", it) }
            }
            deleteTrack.await(manga.id, item.tracker.id)
        }
    }
}
