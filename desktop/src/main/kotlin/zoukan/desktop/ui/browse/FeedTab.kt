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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.tachiyomi.source.CatalogueSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceIdFeed
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR

/**
 * Browse > Feed: Komikku's global feed. Each row is a source's latest updates (popular when it has
 * none) or one of its saved searches; the rows are the "global" feeds a backup carries.
 *
 * With [sourceId] it is that source's own feed (Android's source feed): its latest updates, its
 * popular entries, then the saved searches added to the source's feed.
 */
class FeedScreenModel(
    val sourceId: Long? = null,
    private val getSourceFeeds: GetFeedSavedSearchBySourceId = Injekt.get(),
    private val getSourceFeedSearches: GetSavedSearchBySourceIdFeed = Injekt.get(),
    private val getFeeds: GetFeedSavedSearchGlobal = Injekt.get(),
    private val getFeedSavedSearches: GetSavedSearchGlobalFeed = Injekt.get(),
    private val getExhSavedSearch: GetExhSavedSearch = Injekt.get(),
    private val insertFeed: InsertFeedSavedSearch = Injekt.get(),
    private val deleteFeed: DeleteFeedSavedSearchById = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : StateScreenModel<FeedScreenModel.State>(State()) {

    data class Row(
        val feed: FeedSavedSearch,
        val source: CatalogueSource?,
        val savedSearch: SavedSearch?,
        /** A source feed's fixed rows: popular (true) or latest (false); null for a saved feed */
        val popular: Boolean? = null,
    ) {
        val fixed: Boolean get() = popular != null

        val title: String
            get() = when {
                source == null -> localized(DMR.strings.source_not_installed, feed.source)
                popular == true -> localized(MR.strings.popular)
                popular == false -> localized(MR.strings.latest)
                savedSearch != null && feed.global -> "${source.name} · ${savedSearch.name}"
                savedSearch != null -> savedSearch.name
                source.supportsLatest -> localized(DMR.strings.latest, source.name)
                else -> localized(DMR.strings.popular, source.name)
            }
    }

    sealed interface Result {
        data object Loading : Result
        data class Success(val manga: List<Manga>) : Result
        data class Error(val message: String) : Result
    }

    data class State(
        val rows: List<Row>? = null,
        val results: Map<Long, Result> = emptyMap(),
    )

    private val permits = Semaphore(5)
    private val loads = mutableMapOf<Long, kotlinx.coroutines.Job>()
    private var started = false

    /** Starts loading when the tab is first shown, not at app start. */
    fun start() {
        if (started) return
        started = true
        screenModelScope.launch {
            val sourceId = sourceId
            val feeds = if (sourceId == null) getFeeds.subscribe() else getSourceFeeds.subscribe(sourceId)
            feeds.collect { feeds ->
                val searches = withContext(Dispatchers.IO) {
                    if (sourceId == null) getFeedSavedSearches.await() else getSourceFeedSearches.await(sourceId)
                }.associateBy { it.id }
                val saved = feeds.sortedBy { it.feedOrder }.map { feed ->
                    Row(feed, sourceManager.get(feed.source) as? CatalogueSource, feed.savedSearch?.let(searches::get))
                }
                val rows = if (sourceId == null) saved else fixedRows(sourceId) + saved
                val known = state.value.results.keys
                mutableState.update { it.copy(rows = rows) }
                rows.filter { it.feed.id !in known }.forEach(::load)
            }
        }
    }

    private fun fixedRows(sourceId: Long): List<Row> {
        val source = sourceManager.get(sourceId) as? CatalogueSource
        fun row(id: Long, popular: Boolean) =
            Row(FeedSavedSearch(id = id, source = sourceId, savedSearch = null, global = false, feedOrder = -1), source, null, popular)
        return listOfNotNull(row(LATEST_ROW, false).takeIf { source?.supportsLatest == true }, row(POPULAR_ROW, true))
    }

    fun refresh() = state.value.rows.orEmpty().forEach(::load)

    private fun load(row: Row) {
        val source = row.source ?: return mutableState.update {
            it.copy(results = it.results + (row.feed.id to Result.Error(localized(DMR.strings.install_the_sources_extension_to_see))))
        }
        mutableState.update { it.copy(results = it.results + (row.feed.id to Result.Loading)) }
        // A refresh replaces the row's load still running, so an older result never lands last
        loads.remove(row.feed.id)?.cancel()
        loads[row.feed.id] = screenModelScope.launch {
            val result = permits.withPermit {
                try {
                    val page = withContext(Dispatchers.IO) {
                        withTimeout(60_000) {
                            val saved = row.savedSearch?.let { getExhSavedSearch.awaitOne(it.id, source::getFilterList) }
                            when {
                                saved != null -> source.getSearchManga(1, saved.query.orEmpty(), saved.filterList ?: source.getFilterList())
                                row.popular == true -> source.getPopularManga(1)
                                source.supportsLatest -> source.getLatestUpdates(1)
                                else -> source.getPopularManga(1)
                            }
                        }
                    }
                    Result.Success(withContext(Dispatchers.IO) { networkToLocalManga(page.mangas.map { it.toDomainManga(source.id) }) })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.Error(e.message ?: e::class.simpleName.orEmpty())
                }
            }
            mutableState.update { it.copy(results = it.results + (row.feed.id to result)) }
        }
    }

    fun add(sourceId: Long, savedSearch: Long?) {
        screenModelScope.launch(Dispatchers.IO) {
            val global = this@FeedScreenModel.sourceId == null
            insertFeed.await(FeedSavedSearch(id = -1, source = sourceId, savedSearch = savedSearch, global = global, feedOrder = 0))
        }
    }

    // ZOUKAN -->
    /** ReorderFeed, global or within this source's feed, as Komikku's feed order screen */
    fun move(row: Row, newIndex: Int) {
        if (row.fixed) return
        screenModelScope.launch(Dispatchers.IO) {
            Injekt.get<ReorderFeed>().changeOrder(row.feed, newIndex, global = sourceId == null)
        }
    }
    // ZOUKAN <--

    fun remove(row: Row) {
        screenModelScope.launch(Dispatchers.IO) { deleteFeed.await(row.feed.id) }
    }

    fun sources(): List<CatalogueSource> = sourceManager.getAll().filterIsInstance<CatalogueSource>()
        .filter { it.id != MERGED_SOURCE_ID }
        .sortedBy { it.name.lowercase() }

    companion object {
        private const val LATEST_ROW = -10L
        private const val POPULAR_ROW = -11L
    }
}

/** A source's own feed, opened from the source's screen. */
data class SourceFeedScreen(val sourceId: Long) : cafe.adriel.voyager.core.screen.Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: cafe.adriel.voyager.core.screen.ScreenKey = "source-feed-$sourceId/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { FeedScreenModel(sourceId) }
        val source = remember { Injekt.get<SourceManager>().get(sourceId) }
        Column(Modifier.fillMaxSize()) {
            zoukan.desktop.ui.common.ScreenHeader(source?.name ?: localized(DMR.strings.source_not_installed_2), subtitle = localized(SYMR.strings.feed)) {}
            FeedTab(model)
        }
    }
}

@Composable
fun FeedTab(model: FeedScreenModel) {
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    var adding by remember { mutableStateOf(false) }
    // ZOUKAN -->
    val bulk = remember { BulkFavorite() }
    var ordering by remember { mutableStateOf(false) }
    // ZOUKAN <--
    // ZOUKAN -->
    val sourcePreferences = remember { Injekt.get<eu.kanade.domain.source.service.SourcePreferences>() }
    val hideInLibrary by sourcePreferences.hideInLibraryFeedItems().changes().collectAsState(sourcePreferences.hideInLibraryFeedItems().get())
    // ZOUKAN <--
    LaunchedEffect(Unit) { model.start() }
    val rows = state.rows ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (model.sourceId == null) localized(DMR.strings.latest_updates_and_saved_searches_of) else localized(DMR.strings.this_sources_latest_popular_and_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // ZOUKAN -->
            BulkSelectButton(bulk)
            // Komikku's feed order: drag the saved feeds into place
            if (rows.count { !it.fixed } > 1) {
                TooltipIconButton(Icons.AutoMirrored.Outlined.Sort, localized(DMR.strings.reorder_the_feed), onClick = { ordering = true })
            }
            // ZOUKAN <--
            TooltipIconButton(Icons.Outlined.Refresh, localized(MR.strings.action_webview_refresh), onClick = model::refresh)
            FilledTonalButton(onClick = { adding = true }) {
                Icon(Icons.Outlined.Add, null)
                Text(localized(MR.strings.action_add))
            }
        }
        // ZOUKAN -->
        BulkFavoriteBar(bulk, rows.flatMap { (state.results[it.feed.id] as? FeedScreenModel.Result.Success)?.manga.orEmpty() })
        // ZOUKAN <--
        if (rows.isEmpty()) {
            EmptyScreen(localized(DMR.strings.no_feeds_yet_add_a_sources), actionLabel = localized(MR.strings.action_add), onAction = { adding = true })
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(rows, key = { it.feed.id }) { row ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = row.source != null) {
                                    val source = row.source ?: return@clickable
                                    navigator.push(
                                        when {
                                            row.savedSearch != null -> SourceScreen(source.id, savedSearch = row.savedSearch.id)
                                            row.popular == true -> SourceScreen(source.id)
                                            else -> SourceScreen(source.id, latest = source.supportsLatest)
                                        },
                                    )
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!row.fixed) TooltipIconButton(Icons.Outlined.Delete, localized(DMR.strings.remove_from_the_feed), onClick = { model.remove(row) })
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
                        }
                        when (val result = state.results[row.feed.id]) {
                            // ZOUKAN -->
                            is FeedScreenModel.Result.Success -> if (result.manga.none { !hideInLibrary || !it.favorite }) {
                                // ZOUKAN <--
                                Text(localized(DMR.strings.nothing_here), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // ZOUKAN -->
                                    items(result.manga.filter { !hideInLibrary || !it.favorite }, key = { it.id }) { manga ->
                                        // ZOUKAN <--
                                        MangaGridItem(
                                            title = manga.title,
                                            cover = Cover.of(manga),
                                            // ZOUKAN -->
                                            onClick = { if (bulk.active) bulk.toggle(manga) else navigator.push(MangaScreen(manga.id)) },
                                            onSecondaryClick = if (bulk.active) ({ bulk.toggle(manga) }) else null,
                                            badge = if (manga.favorite) localized(MR.strings.in_library) else null,
                                            modifier = Modifier.width(140.dp).bulkSelected(bulk.isSelected(manga)),
                                            // ZOUKAN <--
                                        )
                                    }
                                }
                            }
                            is FeedScreenModel.Result.Error -> Text(
                                result.message,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            else -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (adding) AddFeedDialog(model, onDismiss = { adding = false })
    // ZOUKAN -->
    if (ordering) FeedOrderDialog(model, rows.filterNot { it.fixed }, onDismiss = { ordering = false })
    // ZOUKAN <--
}

@Composable
private fun AddFeedDialog(model: FeedScreenModel, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val sources = remember { model.sources() }
    val fixedSource = remember { model.sourceId?.let { id -> sources.firstOrNull { it.id == id } } }
    var source by remember { mutableStateOf(fixedSource) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source?.let { localized(DMR.strings.add, it.name) } ?: localized(DMR.strings.add_a_feed)) },
        text = {
            val picked = source
            if (picked == null) {
                Column {
                    OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text(localized(DMR.strings.source)) }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(sources.filter { it.name.contains(filter.trim(), ignoreCase = true) }, key = { it.id }) {
                            Text(
                                "${it.name} (${languageName(it.lang)})",
                                modifier = Modifier.fillMaxWidth().clickable { source = it }.padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            } else {
                val saved by produceState(emptyList<SavedSearch>(), picked) {
                    value = withContext(Dispatchers.IO) { Injekt.get<GetSavedSearchBySourceId>().await(picked.id) }
                }
                Column {
                    if (fixedSource == null) {
                        Text(
                            if (picked.supportsLatest) localized(DMR.strings.latest_updates) else localized(MR.strings.popular),
                            modifier = Modifier.fillMaxWidth().clickable {
                                model.add(picked.id, null)
                                onDismiss()
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                    saved.forEach { search ->
                        Text(
                            localized(DMR.strings.saved_search, search.name),
                            modifier = Modifier.fillMaxWidth().clickable {
                                model.add(picked.id, search.id)
                                onDismiss()
                            }.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                    if (saved.isEmpty()) {
                        Text(
                            localized(DMR.strings.save_a_search_in_the_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
        dismissButton = if (source != null && fixedSource == null) ({ TextButton(onClick = { source = null }) { Text(localized(MR.strings.action_webview_back)) } }) else null,
    )
}
