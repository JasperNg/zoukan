package zoukan.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.GlobalSearchSourceFilter
import eu.kanade.tachiyomi.source.CatalogueSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.PickDialog
import zoukan.desktop.ui.manga.SearchAgainField
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationPicks
import zoukan.i18n.desktop.DMR
import java.util.concurrent.TimeoutException

/**
 * Runs a source's request with the browse screens' time limit, failing with an ordinary
 * [TimeoutException] past it. withTimeout's own TimeoutCancellationException is a
 * CancellationException, which the callers rethrow, so a slow source stayed loading forever
 * instead of showing as failed. Cancelling the caller (leaving the screen) still cancels.
 */
internal suspend fun <T> withSourceTimeout(millis: Long = 60_000, block: suspend CoroutineScope.() -> T): T =
    try {
        withTimeout(millis, block)
    } catch (e: TimeoutCancellationException) {
        throw TimeoutException(e.message)
    }

/** A browse screen's error text for a source that failed: a timeout gets a translated message. */
internal fun sourceErrorMessage(e: Throwable): String = when (e) {
    is TimeoutException -> localized(DMR.strings.source_timed_out)
    else -> e.message ?: e::class.simpleName.orEmpty()
}

class GlobalSearchScreenModel(
    val query: String,
    // ZOUKAN -->
    /** A source to leave out: the one an entry migrates away from */
    excludeSource: Long? = null,
    /** Only the enabled sources (languages, not disabled), as Android's global search */
    enabledOnly: Boolean = false,
    // ZOUKAN <--
    sourceManager: SourceManager = Injekt.get(),
    preferences: DesktopPreferences = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    // ZOUKAN -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // ZOUKAN <--
) : StateScreenModel<Map<Long, GlobalSearchScreenModel.Result>>(emptyMap()) {

    sealed interface Result {
        data object Loading : Result
        data class Success(val manga: List<Manga>) : Result
        data class Error(val message: String) : Result
    }

    // ZOUKAN -->
    private val pinned = preferences.pinnedSources().get()

    /** Every source the search can use; [sources] is the part the pinned filter keeps */
    val allSources: List<CatalogueSource> = run {
        val languages = preferences.enabledLanguages().get()
        val disabled = sourcePreferences.disabledSources().get()
        sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id != MERGED_SOURCE_ID && it.id != excludeSource }
            .filter { !enabledOnly || ((it.lang in languages || it is LocalSource) && it.id.toString() !in disabled) }
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
    }

    /** KMK's "Pinned only" chip shows only when there are pinned sources to search */
    val hasPinned: Boolean = allSources.any { it.id.toString() in pinned }

    val sourceFilter = sourcePreferences.globalSearchPinnedState()
    val onlyWithResults = sourcePreferences.globalSearchFilterState()

    /** The sources the search uses with the [filter] chip as it is */
    fun sources(filter: GlobalSearchSourceFilter): List<CatalogueSource> =
        if (filter == GlobalSearchSourceFilter.PinnedOnly && hasPinned) {
            allSources.filter { it.id.toString() in pinned }
        } else {
            allSources
        }

    val sources: List<CatalogueSource> get() = sources(sourceFilter.get())

    private val permits = Semaphore(5)

    init {
        search()
    }

    fun setSourceFilter(filter: GlobalSearchSourceFilter) {
        sourceFilter.set(filter)
        search()
    }

    fun toggleOnlyWithResults() = onlyWithResults.set(!onlyWithResults.get())

    /** Searches the sources the filter adds; results already in stay, as on Android */
    private fun search() {
        val missing = sources.filter { it.id !in state.value }
        mutableState.update { it + missing.associate { source -> source.id to Result.Loading } }
        missing.forEach { source ->
            // ZOUKAN <--
            screenModelScope.launch {
                val result = permits.withPermit {
                    try {
                        val page = withContext(Dispatchers.IO) {
                            withSourceTimeout { source.getSearchManga(1, query, source.getFilterList()) }
                        }
                        val manga = withContext(Dispatchers.IO) {
                            networkToLocalManga(page.mangas.map { it.toDomainManga(source.id) })
                        }
                        // A source can list the same title twice on a page; the row is keyed by id
                        Result.Success(manga.distinctBy { it.id })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.Error(sourceErrorMessage(e))
                    }
                }
                mutableState.update { it + (source.id to result) }
            }
        }
    }
}

data class GlobalSearchScreen(
    val query: String,
    // ZOUKAN -->
    /** What picking a result does: open it, merge it into an entry, or migrate an entry to it */
    val purpose: SearchPurpose = SearchPurpose.Browse,
    // ZOUKAN <--
) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "global-search-$query-$purpose/"

    @Composable
    override fun Content() {
        // ZOUKAN -->
        val model = rememberScreenModel {
            GlobalSearchScreenModel(query, excludeSource = purpose.excludedSource(), enabledOnly = purpose == SearchPurpose.Browse)
        }
        // ZOUKAN <--
        val results by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        // ZOUKAN -->
        var picked by remember { mutableStateOf<Manga?>(null) }
        picked?.let { manga -> PickDialog(purpose, manga, onDismiss = { picked = null }) }
        val sourceFilter by model.sourceFilter.collectAsState()
        val onlyWithResults by model.onlyWithResults.collectAsState()
        val bulk = remember { BulkFavorite() }
        // ZOUKAN -->
        // A result's right click adds or removes it (Android's long press) outside selecting
        val libraryToggle = rememberQuickLibraryToggle()
        QuickLibraryToggleDialogs(libraryToggle)
        // ZOUKAN <--
        val sources = remember(sourceFilter) { model.sources(sourceFilter) }
        // ZOUKAN <--
        // Sources with results first, then the ones still searching, then empty and failed ones
        val ordered = sources.sortedBy {
            when (val r = results[it.id]) {
                is GlobalSearchScreenModel.Result.Success -> if (r.manga.isNotEmpty()) 0 else 2
                GlobalSearchScreenModel.Result.Loading, null -> 1
                is GlobalSearchScreenModel.Result.Error -> 3
            }
        }
            // ZOUKAN -->
            .filter { !onlyWithResults || (results[it.id] as? GlobalSearchScreenModel.Result.Success)?.manga?.isNotEmpty() == true }
        val done = sources.count { results[it.id] != null && results[it.id] != GlobalSearchScreenModel.Result.Loading }
        // ZOUKAN <--
        Column(Modifier.fillMaxSize()) {
            // ZOUKAN -->
            ScreenHeader(
                when (purpose) {
                    SearchPurpose.Browse -> localized(DMR.strings.search, query)
                    is SearchPurpose.Merge -> localized(DMR.strings.merge_with, query)
                    is SearchPurpose.Migrate, is SearchPurpose.MigrateInList -> localized(DMR.strings.migrate_to, query)
                },
                subtitle = when (purpose) {
                    SearchPurpose.Browse -> localized(DMR.strings.of_sources, done, sources.size)
                    else -> localized(DMR.strings.pick_an_entry_in_one_of, sources.size)
                },
            ) {
                if (purpose != SearchPurpose.Browse) {
                    SearchAgainField(query) { navigator.replace(GlobalSearchScreen(it, purpose)) }
                } else {
                    BulkSelectButton(bulk)
                }
            }
            // KMK's filter chips: pinned sources only or all, and only sources with results
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.hasPinned) {
                    FilterChip(
                        selected = sourceFilter == GlobalSearchSourceFilter.PinnedOnly,
                        onClick = { model.setSourceFilter(GlobalSearchSourceFilter.PinnedOnly) },
                        label = { Text(localized(DMR.strings.pinned_only)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("global-search-pinned"),
                    )
                    FilterChip(
                        selected = sourceFilter == GlobalSearchSourceFilter.All,
                        onClick = { model.setSourceFilter(GlobalSearchSourceFilter.All) },
                        label = { Text(localized(DMR.strings.all_sources)) },
                        leadingIcon = { Icon(Icons.Outlined.DoneAll, null, Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("global-search-all"),
                    )
                }
                FilterChip(
                    selected = onlyWithResults,
                    onClick = model::toggleOnlyWithResults,
                    label = { Text(localized(MR.strings.has_results)) },
                    leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("global-search-has-results"),
                )
            }
            if (purpose == SearchPurpose.Browse) {
                BulkFavoriteBar(bulk, ordered.flatMap { (results[it.id] as? GlobalSearchScreenModel.Result.Success)?.manga.orEmpty() })
            }
            // ZOUKAN <--
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(ordered, key = { it.id }) { source ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = purpose == SearchPurpose.Browse) { navigator.push(SourceScreen(source.id, query = query)) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                Text(languageName(source.lang), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                        }
                        when (val result = results[source.id]) {
                            is GlobalSearchScreenModel.Result.Success -> if (result.manga.isEmpty()) {
                                Text(localized(DMR.strings.no_results), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(result.manga, key = { it.id }) { manga ->
                                        // Android's GlobalSearchCardRow item, with KMK's panorama covers
                                        BrowseRowItem(
                                            manga = manga,
                                            onClick = {
                                                // ZOUKAN -->
                                                when {
                                                    purpose == SearchPurpose.Browse && bulk.active -> bulk.toggle(manga)
                                                    purpose == SearchPurpose.Browse -> navigator.push(MangaScreen(manga.id))
                                                    purpose is SearchPurpose.MigrateInList -> {
                                                        MigrationPicks.pick(purpose.mangaId, manga.id)
                                                        navigator.pop()
                                                    }
                                                    else -> picked = manga
                                                }
                                                // ZOUKAN <--
                                            },
                                            // ZOUKAN -->
                                            // While selecting, right-click selects; in a browse search it adds or
                                            // removes (Android's long press), in a migration search it opens the entry
                                            // (Android's MigrateSearchScreen long press)
                                            onSecondaryClick = when {
                                                bulk.active -> ({ bulk.toggle(manga) })
                                                purpose == SearchPurpose.Browse -> ({ libraryToggle.toggle(manga) })
                                                else -> ({ navigator.push(MangaScreen(manga.id)) })
                                            },
                                            modifier = Modifier.bulkSelected(bulk.isSelected(manga)),
                                            // ZOUKAN <--
                                        )
                                    }
                                }
                            }
                            is GlobalSearchScreenModel.Result.Error -> Text(
                                result.message,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ZOUKAN -->
/** The source a pick search leaves out: the one an entry migrates away from. */
private fun SearchPurpose.excludedSource(): Long? = when (this) {
    is SearchPurpose.Migrate -> sourceId
    is SearchPurpose.MigrateInList -> sourceId
    else -> null
}
// ZOUKAN <--
