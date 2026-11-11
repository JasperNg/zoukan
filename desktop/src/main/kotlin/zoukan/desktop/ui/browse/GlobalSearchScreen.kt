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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
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
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.PickDialog
import zoukan.desktop.ui.manga.SearchAgainField
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationPicks

class GlobalSearchScreenModel(
    val query: String,
    // ZOUKAN -->
    /** A source to leave out: the one an entry migrates away from */
    excludeSource: Long? = null,
    // ZOUKAN <--
    sourceManager: SourceManager = Injekt.get(),
    preferences: DesktopPreferences = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : StateScreenModel<Map<Long, GlobalSearchScreenModel.Result>>(emptyMap()) {

    sealed interface Result {
        data object Loading : Result
        data class Success(val manga: List<Manga>) : Result
        data class Error(val message: String) : Result
    }

    val sources: List<CatalogueSource> = run {
        val pinned = preferences.pinnedSources().get()
        sourceManager.getAll().filterIsInstance<CatalogueSource>()
            // ZOUKAN -->
            .filter { it.id != MERGED_SOURCE_ID && it.id != excludeSource }
            // ZOUKAN <--
            .sortedWith(compareBy({ it.id.toString() !in pinned }, { it.name.lowercase() }))
    }

    init {
        mutableState.value = sources.associate { it.id to Result.Loading }
        val permits = Semaphore(5)
        sources.forEach { source ->
            screenModelScope.launch {
                val result = permits.withPermit {
                    try {
                        val page = withContext(Dispatchers.IO) {
                            withTimeout(60_000) { source.getSearchManga(1, query, source.getFilterList()) }
                        }
                        val manga = withContext(Dispatchers.IO) {
                            networkToLocalManga(page.mangas.map { it.toDomainManga(source.id) })
                        }
                        Result.Success(manga)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Result.Error(e.message ?: e::class.simpleName.orEmpty())
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
        val model = rememberScreenModel { GlobalSearchScreenModel(query, excludeSource = purpose.excludedSource()) }
        val results by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        // ZOUKAN -->
        var picked by remember { mutableStateOf<Manga?>(null) }
        picked?.let { manga -> PickDialog(purpose, manga, onDismiss = { picked = null }) }
        // ZOUKAN <--
        // Sources with results first, then the ones still searching, then empty and failed ones
        val ordered = model.sources.sortedBy {
            when (val r = results[it.id]) {
                is GlobalSearchScreenModel.Result.Success -> if (r.manga.isNotEmpty()) 0 else 2
                GlobalSearchScreenModel.Result.Loading, null -> 1
                is GlobalSearchScreenModel.Result.Error -> 3
            }
        }
        Column(Modifier.fillMaxSize()) {
            // ZOUKAN -->
            ScreenHeader(
                when (purpose) {
                    SearchPurpose.Browse -> "Search: $query"
                    is SearchPurpose.Merge -> "Merge with: $query"
                    is SearchPurpose.Migrate, is SearchPurpose.MigrateInList -> "Migrate to: $query"
                },
                subtitle = when (purpose) {
                    SearchPurpose.Browse -> "${model.sources.size} sources"
                    else -> "Pick an entry in one of ${model.sources.size} sources"
                },
            ) {
                if (purpose != SearchPurpose.Browse) {
                    SearchAgainField(query) { navigator.replace(GlobalSearchScreen(it, purpose)) }
                }
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
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                        }
                        when (val result = results[source.id]) {
                            is GlobalSearchScreenModel.Result.Success -> if (result.manga.isEmpty()) {
                                Text("No results", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(result.manga, key = { it.id }) { manga ->
                                        MangaGridItem(
                                            title = manga.title,
                                            cover = Cover.of(manga),
                                            onClick = {
                                                // ZOUKAN -->
                                                when (purpose) {
                                                    SearchPurpose.Browse -> navigator.push(MangaScreen(manga.id))
                                                    is SearchPurpose.MigrateInList -> {
                                                        MigrationPicks.pick(purpose.mangaId, manga.id)
                                                        navigator.pop()
                                                    }
                                                    else -> picked = manga
                                                }
                                                // ZOUKAN <--
                                            },
                                            badge = if (manga.favorite) "In library" else null,
                                            modifier = Modifier.width(140.dp),
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
