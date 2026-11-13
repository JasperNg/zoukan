package zoukan.desktop.ui.recs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.SManga
import exh.recs.batch.SearchFlags
import exh.recs.sources.RecommendationPagingSource
import exh.recs.sources.RecommendationSource
import exh.recs.sources.TrackerRecommendationPagingSource
import exh.smartsearch.SmartLibrarySearchEngine
import exh.util.ThrottleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import zoukan.desktop.compat.localized
import java.net.URI
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a running search's status update leaves: [publish]'s rule, without the coroutine. */
internal fun searchStatusAfter(current: SearchStatus, new: SearchStatus, searchActive: Boolean): SearchStatus =
    if (current is SearchStatus.Cancelling || current is SearchStatus.Idle || !searchActive) current else new

/**
 * SY's RecommendationSearchHelper (exh.recs.batch): collects the recommendations of every selected
 * library entry and ranks each service's results by how many of the entries recommend them. The
 * Android wake and Wi-Fi locks have no desktop counterpart.
 */
class RecommendationSearchHelper {
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val getTracks: GetTracks by injectLazy()
    private val networkToLocalManga: NetworkToLocalManga by injectLazy()
    private val preferences: SourcePreferences by injectLazy()

    private val smartSearchEngine by lazy { SmartLibrarySearchEngine() }

    val status: MutableStateFlow<SearchStatus> = MutableStateFlow(SearchStatus.Idle)

    @Synchronized
    fun runSearch(scope: CoroutineScope, mangaList: List<Manga>): Job? {
        if (status.value !is SearchStatus.Idle) {
            return null
        }

        status.value = SearchStatus.Initializing

        return scope.launch(Dispatchers.IO) { beginSearch(mangaList) }
    }

    // ZOUKAN -->
    /**
     * Sets the search's own status, unless the user cancelled: a Cancel (Cancelling) is never
     * overwritten, so it can't be lost, and once the dialog went back to Idle or the job was
     * cancelled, a late update can't bring the dialog back. One atomic update, so a Cancel that
     * lands while this runs is seen on the retry.
     */
    private suspend fun publish(new: SearchStatus) {
        val job = currentCoroutineContext()[Job]
        status.update { current -> searchStatusAfter(current, new, searchActive = job?.isActive != false) }
    }
    // ZOUKAN <--

    private suspend fun beginSearch(mangaList: List<Manga>) {
        try {
            val flags = preferences.recommendationSearchFlags().get()
            val libraryManga = getLibraryManga.await()
            val tracks = getTracks.await()

            // Trackers such as MAL need to be throttled more strictly
            val stricterThrottling = SearchFlags.hasIncludeTrackers(flags)

            val throttleManager =
                ThrottleManager(
                    max = 3.seconds,
                    inc = 50.milliseconds,
                    initial = if (stricterThrottling) 2.seconds else 0.seconds,
                )

            // Map of results grouped by recommendation source
            val resultsMap = Collections.synchronizedMap(mutableMapOf<String, SearchResults>())

            mangaList.forEachIndexed { index, sourceManga ->
                // Check if the job has been cancelled
                currentCoroutineContext().ensureActive()

                publish(SearchStatus.Processing(sourceManga.toSManga(), index + 1, mangaList.size))

                val jobs = RecommendationPagingSource.createSources(
                    sourceManga,
                    RecommendationSource(sourceManga.source),
                ).mapNotNull { source ->
                    // Apply source filters
                    if (source is TrackerRecommendationPagingSource && !SearchFlags.hasIncludeTrackers(flags)) {
                        return@mapNotNull null
                    }

                    if (source.associatedSourceId != null && !SearchFlags.hasIncludeSources(flags)) {
                        return@mapNotNull null
                    }

                    // Parallelize fetching recommendations from all sources in the current context
                    CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                        val recSourceId = source::class.qualifiedName!!

                        try {
                            val page = source.requestNextPage(1)

                            // Try to filter out mangas that are already in the library
                            val mangas = page.mangas
                                .filterLibraryItemsIfEnabled(source, libraryManga, tracks)

                            // Add or update the result collection for the current source
                            resultsMap.getOrPut(recSourceId) {
                                SearchResults(
                                    recSourceName = source.name,
                                    recSourceCategory = source.category,
                                    recAssociatedSourceId = source.associatedSourceId,
                                    results = mutableListOf(),
                                )
                            }.results.addAll(mangas)
                        } catch (_: NoResultsException) {
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: OutOfMemoryError) {
                            throw e
                        } catch (e: Throwable) {
                            // Throwable: an unloaded extension's Error must not leave the search spinning
                            logcat(LogPriority.ERROR, e) { "Error while fetching recommendations for $recSourceId" }
                        }
                    }
                }
                jobs.awaitAll()

                // Continuously slow down the search to avoid hitting rate limits
                throttleManager.throttle()
            }

            val rankedMap = resultsMap.map {
                RankedSearchResults(
                    recSourceName = it.value.recSourceName,
                    recSourceCategory = it.value.recSourceCategory,
                    recAssociatedSourceId = it.value.recAssociatedSourceId,
                    results = it.value.results
                        // Group by URL and count occurrences
                        .groupingBy(SManga::url)
                        .eachCount()
                        .entries
                        // Sort by occurrences desc
                        .sortedByDescending(Map.Entry<String, Int>::value)
                        // Resolve SManga instances from URL keys
                        .associate { (url, count) ->
                            val manga = it.value.results.first { manga -> manga.url == url }
                            manga to count
                        },
                )
            }

            publish(
                when {
                    rankedMap.isNotEmpty() -> SearchStatus.Finished.WithResults(rankedMap)
                    else -> SearchStatus.Finished.WithoutResults
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error during recommendation search" }
            publish(SearchStatus.Error(e.message.orEmpty()))
        }
    }

    private suspend fun List<SManga>.filterLibraryItemsIfEnabled(
        recSource: RecommendationPagingSource,
        libraryManga: List<LibraryManga>,
        tracks: List<Track>,
    ): List<SManga> {
        val flags = preferences.recommendationSearchFlags().get()

        if (!SearchFlags.hasHideLibraryResults(flags)) {
            return this
        }

        // KMK -->
        // Source recommendations can be directly resolved, if the recommendation is from the same source
        recSource.associatedSourceId?.let { srcId ->
            return networkToLocalManga(map { it.toDomainManga(srcId) })
                .filterNot { local -> libraryManga.any { it.id == local.id } }
                .map { it.toSManga() }
        }
        // KMK <--

        return filterNot { manga ->
            // Tracker recommendations can be resolved by checking if the tracker is attached to the recommendation
            if (recSource is TrackerRecommendationPagingSource) {
                recSource.associatedTrackerId?.let { trackerId ->
                    return@filterNot tracks.any {
                        it.trackerId == trackerId && uriPath(it.remoteUrl) == uriPath(manga.url)
                    }
                }
            }

            // Fallback to smart search otherwise
            smartSearchEngine.smartSearch(libraryManga, manga.title) != null
        }
    }

    /** Android's `toUri().path`. */
    private fun uriPath(url: String): String? = runCatching { URI(url).path }.getOrNull()
}

// Contains the search results for a single source
private typealias SearchResults = Results<MutableList<SManga>>

// Contains the ranked search results for a single source
typealias RankedSearchResults = Results<Map<SManga, Int>>

data class Results<T>(
    val recSourceName: String,
    val recSourceCategory: StringResource,
    val recAssociatedSourceId: Long?,
    val results: T,
)

sealed interface SearchStatus {
    data object Idle : SearchStatus
    data object Initializing : SearchStatus
    data class Processing(val manga: SManga, val current: Int, val total: Int) : SearchStatus
    data class Error(val message: String) : SearchStatus
    data object Cancelling : SearchStatus

    sealed interface Finished : SearchStatus {
        data class WithResults(val results: List<RankedSearchResults>) : Finished
        data object WithoutResults : Finished
    }
}

/**
 * SY's RecommendationSearchBottomSheetDialog: which services to search and whether to hide what is
 * already in the library, kept in the same preference as Android.
 */
@Composable
fun RecommendationSearchDialog(onDismissRequest: () -> Unit, onSearchRequest: () -> Unit) {
    val preference = remember { Injekt.get<SourcePreferences>().recommendationSearchFlags() }
    var flags by remember { mutableIntStateOf(preference.get()) }
    fun set(flag: Int, on: Boolean) {
        var value = 0
        if (if (flag == SearchFlags.INCLUDE_SOURCES) on else SearchFlags.hasIncludeSources(flags)) value = value or SearchFlags.INCLUDE_SOURCES
        if (if (flag == SearchFlags.INCLUDE_TRACKERS) on else SearchFlags.hasIncludeTrackers(flags)) value = value or SearchFlags.INCLUDE_TRACKERS
        if (if (flag == SearchFlags.HIDE_LIBRARY_RESULTS) on else SearchFlags.hasHideLibraryResults(flags)) value = value or SearchFlags.HIDE_LIBRARY_RESULTS
        flags = value
        preference.set(value)
    }
    val sources = SearchFlags.hasIncludeSources(flags)
    val trackers = SearchFlags.hasIncludeTrackers(flags)
    val hideLibrary = SearchFlags.hasHideLibraryResults(flags)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(localized(SYMR.strings.rec_search_short)) },
        text = {
            Column {
                Text(
                    localized(SYMR.strings.rec_services_to_search),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                CheckRow(localized(SYMR.strings.rec_group_source), sources) { set(SearchFlags.INCLUDE_SOURCES, it) }
                CheckRow(localized(SYMR.strings.rec_group_tracker), trackers) { set(SearchFlags.INCLUDE_TRACKERS, it) }
                HorizontalDivider(Modifier.padding(top = 4.dp))
                Text(
                    localized(MR.strings.action_settings),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { set(SearchFlags.HIDE_LIBRARY_RESULTS, !hideLibrary) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(localized(SYMR.strings.rec_hide_library_entries), Modifier.weight(1f))
                    Switch(hideLibrary, onCheckedChange = { set(SearchFlags.HIDE_LIBRARY_RESULTS, it) })
                }
            }
        },
        confirmButton = {
            // Only enable search button if at least one of the checkboxes is checked
            TextButton(enabled = sources || trackers, onClick = onSearchRequest) { Text(localized(SYMR.strings.rec_search)) }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(label)
    }
}

/** SY's RecommendationSearchProgressDialog. */
@Composable
fun RecommendationSearchProgressDialog(
    status: SearchStatus,
    setStatusIdle: () -> Unit,
    setStatusCancelling: () -> Unit,
) {
    val (title, text, positive, negative) = when (status) {
        is SearchStatus.Initializing -> Quad(
            localized(SYMR.strings.rec_collecting),
            localized(SYMR.strings.rec_initializing),
            null,
            setStatusCancelling,
        )
        is SearchStatus.Error -> Quad(
            localized(SYMR.strings.rec_error_title),
            localized(SYMR.strings.rec_error_string, status.message),
            setStatusIdle,
            null,
        )
        is SearchStatus.Processing -> Quad(
            localized(SYMR.strings.rec_collecting),
            localized(SYMR.strings.rec_processing_state, status.current, status.total) + "\n\n" + status.manga.title,
            null,
            setStatusCancelling,
        )
        else -> return
    }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            if (positive != null) TextButton(onClick = positive) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = {
            if (negative != null) TextButton(onClick = negative) { Text(localized(MR.strings.action_cancel)) }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
                if (status is SearchStatus.Processing) {
                    LinearProgressIndicator(
                        progress = { status.current.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
                // KMK -->
                if (status is SearchStatus.Initializing) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                    }
                }
                // KMK <--
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    )
}

private data class Quad(val title: String, val text: String, val positive: (() -> Unit)?, val negative: (() -> Unit)?)
