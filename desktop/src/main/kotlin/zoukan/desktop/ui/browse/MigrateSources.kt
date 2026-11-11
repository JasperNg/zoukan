package zoukan.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingDirection
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingMode
import kotlinx.coroutines.flow.map
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.migration.MigrationConfigScreen

/** A source with library entries, for Browse > Migrate */
data class MigrateSourceItem(val id: Long, val name: String, val lang: String, val installed: Boolean, val count: Int)

/**
 * Android's Browse > Migrate (MigrateSourceTab): the sources of the library's entries with how many
 * each has, sorted by name or count, then the entries of one source to migrate.
 */
@Composable
fun MigrateSourcesList() {
    val navigator = LocalNavigator.currentOrThrow
    val preferences = remember { Injekt.get<SourcePreferences>() }
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val mode by preferences.migrationSortingMode().changes().collectAsState(preferences.migrationSortingMode().get())
    val direction by preferences.migrationSortingDirection().changes().collectAsState(preferences.migrationSortingDirection().get())
    var query by rememberSaveable { mutableStateOf("") }
    val counts by remember {
        Injekt.get<GetLibraryManga>().subscribe().map { library ->
            library.distinctBy { it.manga.id }.groupingBy { it.manga.source }.eachCount()
        }
    }.collectAsState(null)

    Column(Modifier.fillMaxSize().testTag("migrate-sources")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it }, "Search sources", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == MigrateSortingMode.ALPHABETICAL,
                onClick = { preferences.migrationSortingMode().set(MigrateSortingMode.ALPHABETICAL) },
                label = { Text("Name") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == MigrateSortingMode.TOTAL,
                onClick = { preferences.migrationSortingMode().set(MigrateSortingMode.TOTAL) },
                label = { Text("Entries") },
            )
            val ascending = direction == MigrateSortingDirection.ASCENDING
            TooltipIconButton(
                if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                if (ascending) "Ascending" else "Descending",
                onClick = {
                    preferences.migrationSortingDirection().set(if (ascending) MigrateSortingDirection.DESCENDING else MigrateSortingDirection.ASCENDING)
                },
            )
        }
        val map = counts ?: return@Column LoadingScreen()
        // As GetSourcesWithFavoriteCount: no local source, sources not installed first
        val items = map.filterKeys { it != LocalSource.ID }.map { (id, count) ->
            val source = sourceManager.get(id)
            val stub = source ?: sourceManager.getOrStub(id)
            MigrateSourceItem(id, stub.name.ifBlank { id.toString() }, stub.lang, source != null, count)
        }
            .filter { item ->
                query.isBlank() || query.split(",").any {
                    val input = it.trim()
                    input.isNotEmpty() && (item.name.contains(input, ignoreCase = true) || item.id.toString() == input)
                }
            }
            .sortedWith(migrateComparator(mode, direction))
        if (items.isEmpty()) {
            EmptyScreen(if (map.isEmpty()) "No entries in the library to migrate." else "No source matches.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { navigator.push(MigrateMangaScreen(item.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (item.installed) languageName(item.lang) else "Not installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = Corners.Large) {
                        Text(item.count.toString(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

/** GetSourcesWithFavoriteCount's order: by name or count, sources not installed first either way */
fun migrateComparator(mode: MigrateSortingMode, direction: MigrateSortingDirection): Comparator<MigrateSourceItem> {
    val byValue: Comparator<MigrateSourceItem> = when (mode) {
        MigrateSortingMode.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        MigrateSortingMode.TOTAL -> compareBy { it.count }
    }
    val ordered = if (direction == MigrateSortingDirection.ASCENDING) byValue else byValue.reversed()
    return compareBy<MigrateSourceItem> { it.installed }.then(ordered)
}

/** Android's MigrateMangaScreen: one source's library entries; pick some and migrate them together. */
data class MigrateMangaScreen(val sourceId: Long) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a disposed screen's key
    override val key: ScreenKey = "migrate-manga-$sourceId/"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val sourceName = remember { Injekt.get<SourceManager>().getOrStub(sourceId).name.ifBlank { sourceId.toString() } }
        val entries by remember {
            Injekt.get<GetFavorites>().subscribe(sourceId).map { list -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }) }
        }.collectAsState(null)
        var selected by remember { mutableStateOf(emptySet<Long>()) }

        Column(Modifier.fillMaxSize().testTag("migrate-manga")) {
            ScreenHeader(sourceName, subtitle = "Pick the entries to migrate") {
                val list = entries.orEmpty()
                TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = { selected = list.mapTo(HashSet()) { it.id } })
                TooltipIconButton(Icons.Outlined.FlipToBack, "Invert the selection", onClick = {
                    selected = list.map { it.id }.filterNot { it in selected }.toSet()
                })
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { navigator.push(MigrationConfigScreen(list.filter { it.id in selected }.map { it.id })) },
                ) { Text(if (selected.isEmpty()) "Migrate" else "Migrate ${selected.size}") }
                Spacer(Modifier.width(8.dp))
            }
            val list = entries ?: return@Column LoadingScreen()
            if (list.isEmpty()) {
                EmptyScreen("No library entries from this source.")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { manga ->
                    MigrateMangaRow(
                        manga = manga,
                        selected = manga.id in selected,
                        onToggle = { selected = if (manga.id in selected) selected - manga.id else selected + manga.id },
                        onOpen = { navigator.push(MangaScreen(manga.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MigrateMangaRow(manga: Manga, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        CoverImage(Cover.of(manga), Modifier.size(width = 40.dp, height = 56.dp).clip(Corners.Medium).coverOutline(Corners.Medium))
        Spacer(Modifier.width(12.dp))
        Text(manga.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TooltipIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open", onClick = onOpen)
    }
}
