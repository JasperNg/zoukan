package zoukan.desktop.ui.recs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.sources.RECOMMENDS_SOURCE
import exh.recs.sources.RecommendationPagingSource
import exh.recs.sources.RecommendationSource
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.QuickLibraryToggleDialogs
import zoukan.desktop.ui.browse.rememberQuickLibraryToggle
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR

/**
 * Related titles from the entry's own source (Komikku's related titles) and recommendations from
 * AniList, MangaUpdates, MyAnimeList, MangaDex and Comick (SY's recommendations), one row each.
 */
class RecommendsScreenModel(
    private val mangaId: Long,
    private val getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<RecommendsScreenModel.State>(State()) {

    sealed interface Result {
        data object Loading : Result
        /** [ranks]: how many of the searched entries recommend each result (by URL), for the common recommendations */
        data class Success(val manga: List<Manga>, val ranks: Map<String, Int> = emptyMap()) : Result
        data class Error(val message: String) : Result
    }

    data class Group(
        val key: String,
        val title: String,
        /** Results open their entry; without a source they start a search for the title */
        val opensEntry: Boolean,
        val result: Result,
    )

    data class State(val title: String = "", val groups: List<Group> = emptyList())

    private val permits = Semaphore(5)

    init {
        screenModelScope.launch {
            val manga = getManga.await(mangaId) ?: return@launch
            mutableState.update { it.copy(title = manga.title) }
            launch { loadRelated(manga) }
            RecommendationPagingSource.createSources(manga, RecommendationSource(manga.source)).forEach { source ->
                val key = "rec-${source.name}-${source.category.key}"
                val title = "${source.name} · ${localized(source.category)}"
                put(Group(key, title, source.associatedSourceId != null, Result.Loading))
                launch {
                    val result = permits.withPermit {
                        runCatching {
                            val page = withContext(Dispatchers.IO) { source.requestNextPage(1) }
                            val sourceId = source.associatedSourceId
                            val titles = if (sourceId != null) {
                                withContext(Dispatchers.IO) { networkToLocalManga(page.mangas.map { it.toDomainManga(sourceId) }) }
                            } else {
                                page.mangas.map { it.toDomainManga(RECOMMENDS_SOURCE) }
                            }
                            Result.Success(titles.distinctBy { it.url })
                        }.getOrElse { Result.Error(it.message?.takeIf { m -> m.isNotBlank() } ?: localized(DMR.strings.no_results)) }
                    }
                    put(Group(key, title, source.associatedSourceId != null, result))
                }
            }
        }
    }

    private suspend fun loadRelated(manga: Manga) {
        if (!sourcePreferences.relatedMangas().get() || manga.source == MERGED_SOURCE_ID) return
        val source = sourceManager.get(manga.source) ?: return
        val loading = Group("related", localized(DMR.strings.related_titles), true, Result.Loading)
        put(loading)
        val found = mutableListOf<Manga>()
        runCatching {
            withContext(Dispatchers.IO) {
                source.getRelatedMangaList(manga.toSManga(), exceptionHandler = {}) { (_, list), _ ->
                    val local = networkToLocalManga(list.map { it.toDomainManga(source.id) })
                    synchronized(found) { found += local.filter { it.id != manga.id && found.none { f -> f.id == it.id } } }
                    put(loading.copy(result = Result.Success(synchronized(found) { found.toList() })))
                }
            }
        }
        if (found.isEmpty()) put(loading.copy(result = Result.Error(localized(DMR.strings.no_related_titles))))
    }

    private fun put(group: Group) = mutableState.update { state ->
        val groups = state.groups.filterNot { it.key == group.key } + group
        // Related titles first, then rows with results, as Android sorts them
        state.copy(
            groups = groups.sortedWith(
                compareBy<Group>({ it.key != "related" }, { (it.result as? Result.Success)?.manga?.isEmpty() ?: true }, { it.title }),
            ),
        )
    }
}

data class RecommendsScreen(val mangaId: Long) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "recommends-$mangaId/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { RecommendsScreenModel(mangaId) }
        val state by model.state.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(SYMR.strings.similar_titles), subtitle = state.title) {}
            RecommendsList(state.groups)
        }
    }
}

// ZOUKAN -->
/** A recommendation's right click: opens its site when it is only a link (Android's WebView). */
internal fun openRecommendation(manga: Manga) {
    if (manga.url.isNotBlank()) runCatching { zoukan.desktop.app.ExternalLinks.browse(manga.url) }
}
// ZOUKAN <--

// SY -->
/**
 * SY's RecommendsScreen with Args.MergedSourceMangas: the common recommendations of a library
 * selection, each service's results ranked by how many of the entries recommend them.
 */
class CommonRecommendsScreenModel(
    results: List<RankedSearchResults>,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : StateScreenModel<List<RecommendsScreenModel.Group>>(emptyList()) {
    init {
        screenModelScope.launch {
            // StaticResultPagingSource: the results as the search ranked them
            val groups = results.mapIndexed { index, data ->
                val ranks = data.results.mapKeys { it.key.url }
                val recSourceId = data.recAssociatedSourceId
                val titles = runCatching {
                    if (recSourceId != null) {
                        // If the recommendation is associated with a source, resolve it
                        withContext(Dispatchers.IO) { networkToLocalManga(data.results.keys.map { it.toDomainManga(recSourceId) }) }
                    } else {
                        // Otherwise the user picks a source through a global search
                        data.results.keys.map { it.toDomainManga(RECOMMENDS_SOURCE) }
                    }.distinctBy { it.url }
                }
                RecommendsScreenModel.Group(
                    key = "common-$index",
                    title = "${data.recSourceName} · ${localized(data.recSourceCategory)}",
                    opensEntry = recSourceId != null,
                    result = titles.fold(
                        { RecommendsScreenModel.Result.Success(it, ranks) },
                        { RecommendsScreenModel.Result.Error(it.message ?: localized(SYMR.strings.rec_no_results)) },
                    ),
                )
            }
            // RecommendsScreenModel.sortComparator: rows with results first, then by name and category
            mutableState.value = groups.sortedWith(
                compareBy({ (it.result as? RecommendsScreenModel.Result.Success)?.manga?.isEmpty() ?: true }, { it.title }),
            )
        }
    }
}

class CommonRecommendsScreen(private val results: List<RankedSearchResults>) : Screen {
    override val key: ScreenKey = "common-recommends-${results.hashCode()}/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { CommonRecommendsScreenModel(results) }
        val groups by model.state.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(SYMR.strings.rec_common_recommendations)) {}
            RecommendsList(groups)
        }
    }
}
// SY <--

/** The rows of recommendations, one for each service and category. */
@Composable
private fun RecommendsList(groups: List<RecommendsScreenModel.Group>) {
    val navigator = LocalNavigator.currentOrThrow
    // ZOUKAN -->
    // A recommendation from a real source's right click adds or removes it (Android's long press)
    val libraryToggle = rememberQuickLibraryToggle()
    QuickLibraryToggleDialogs(libraryToggle)
    // ZOUKAN <--
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(groups, key = { it.key }) { group ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                when (val result = group.result) {
                    is RecommendsScreenModel.Result.Success -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(result.manga, key = { "${it.source}-${it.url}" }) { manga ->
                            MangaGridItem(
                                title = manga.title,
                                cover = Cover.of(manga),
                                onClick = {
                                    navigator.push(
                                        if (group.opensEntry) MangaScreen(manga.id) else GlobalSearchScreen(manga.ogTitle),
                                    )
                                },
                                // ZOUKAN -->
                                // Android's long press: a recommendation from a real source is added to
                                // the library or removed; one without opens its site instead
                                onSecondaryClick = if (group.opensEntry) {
                                    ({ libraryToggle.toggle(manga) })
                                } else {
                                    ({ openRecommendation(manga) })
                                },
                                // ZOUKAN <--
                                // SY's RankedSearchMetadata badge: "+N" entries recommend it
                                badge = listOfNotNull(
                                    result.ranks[manga.url]?.let { "+$it" },
                                    localized(MR.strings.in_library).takeIf { manga.favorite },
                                ).joinToString(" · ").ifEmpty { null },
                                modifier = Modifier.width(140.dp),
                            )
                        }
                    }
                    is RecommendsScreenModel.Result.Error -> Text(
                        result.message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    RecommendsScreenModel.Result.Loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}
