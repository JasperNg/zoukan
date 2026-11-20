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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingDirection
import eu.kanade.domain.source.service.SourcePreferences.MigrateSortingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_asc
import tachiyomi.i18n.action_copy_to_clipboard
import tachiyomi.i18n.action_desc
import tachiyomi.i18n.action_migrate
import tachiyomi.i18n.action_select_all
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.`open`
import tachiyomi.i18n.label_titles_section
import tachiyomi.i18n.name
import tachiyomi.i18n.not_installed
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.common.onRightClick
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.copyToClipboard
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.find_a_source_by_name
import zoukan.i18n.desktop.invert_the_selection
import zoukan.i18n.desktop.migrate
import zoukan.i18n.desktop.no_entries_in_the_library_to
import zoukan.i18n.desktop.no_library_entries_from_this_source
import zoukan.i18n.desktop.no_source_matches
import zoukan.i18n.desktop.pick_the_entries_to_migrate

/** A source with library entries, for Browse > Migrate */
data class MigrateSourceItem(val id: Long, val name: String, val lang: String, val installed: Boolean, val count: Int)

/**
 * Android's Browse > Migrate (MigrateSourceTab): the sources of the library's entries with how many
 * each has, sorted by name or count, then the entries of one source to migrate.
 */
@Composable
fun MigrateSourcesList(model: MigrateSourcesModel) {
    val navigator = LocalNavigator.currentOrThrow
    val state by model.state.collectAsState()
    val mode by model.mode.collectAsState()
    val direction by model.direction.collectAsState()

    Column(Modifier.fillMaxSize().testTag("migrate-sources")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(state.query, model::search, localized(DMR.strings.find_a_source_by_name), Modifier.weight(1f), filter = true)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == MigrateSortingMode.ALPHABETICAL,
                onClick = { model.setMode(MigrateSortingMode.ALPHABETICAL) },
                label = { Text(localized(MR.strings.name)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == MigrateSortingMode.TOTAL,
                onClick = { model.setMode(MigrateSortingMode.TOTAL) },
                label = { Text(localized(MR.strings.label_titles_section)) },
            )
            val ascending = direction == MigrateSortingDirection.ASCENDING
            TooltipIconButton(
                if (ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                if (ascending) localized(MR.strings.action_asc) else localized(MR.strings.action_desc),
                onClick = model::toggleDirection,
            )
        }
        val items = state.items ?: return@Column LoadingScreen()
        if (items.isEmpty()) {
            EmptyScreen(if (state.libraryEmpty) localized(DMR.strings.no_entries_in_the_library_to) else localized(DMR.strings.no_source_matches))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(items, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { navigator.push(MigrateMangaScreen(item.id)) }
                        // ZOUKAN -->
                        // Android's long press on a migration source copies its ID
                        .onRightClick(localized(MR.strings.action_copy_to_clipboard)) { copyToClipboard(item.id.toString()) }
                        // ZOUKAN <--
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (item.installed) languageName(item.lang) else localized(MR.strings.not_installed),
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

/**
 * The state of Browse > Migrate: the sources of the library's entries with their counts, in the order
 * and narrowed as the preferences and the search say. [State.items] is null until the library has loaded.
 */
class MigrateSourcesModel(
    getLibraryManga: GetLibraryManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<MigrateSourcesModel.State>(State()) {

    data class State(val items: List<MigrateSourceItem>? = null, val libraryEmpty: Boolean = false, val query: String = "")

    private val query = MutableStateFlow("")

    /** The sort mode and direction as the preferences hold them. */
    val mode: StateFlow<MigrateSortingMode> = preferences.migrationSortingMode().stateIn(screenModelScope)
    val direction: StateFlow<MigrateSortingDirection> = preferences.migrationSortingDirection().stateIn(screenModelScope)

    init {
        val libraryCounts = getLibraryManga.subscribe().map { library ->
            library.distinctBy { it.manga.id }.groupingBy { it.manga.source }.eachCount()
        }
        screenModelScope.launch {
            combine(libraryCounts, mode, direction, query) { counts, mode, direction, query ->
                // As GetSourcesWithFavoriteCount: no local source, sources not installed first
                counts.isEmpty() to migrateSourceItems(counts, sourceManager, query, mode, direction)
            }
                .flowOn(Dispatchers.Default)
                .collect { (libraryEmpty, items) -> mutableState.update { it.copy(items = items, libraryEmpty = libraryEmpty) } }
        }
    }

    fun search(query: String) {
        // The text field shows this at once; the list follows
        mutableState.update { it.copy(query = query) }
        this.query.value = query
    }

    fun setMode(mode: MigrateSortingMode) {
        preferences.migrationSortingMode().set(mode)
    }

    fun toggleDirection() {
        val ascending = preferences.migrationSortingDirection().get() == MigrateSortingDirection.ASCENDING
        preferences.migrationSortingDirection().set(if (ascending) MigrateSortingDirection.DESCENDING else MigrateSortingDirection.ASCENDING)
    }
}

/**
 * The list Browse > Migrate shows: [counts] (library entries per source id) without the local source,
 * narrowed by [query] (comma-separated names or ids), in [mode] and [direction] order.
 */
internal fun migrateSourceItems(
    counts: Map<Long, Int>,
    sourceManager: SourceManager,
    query: String,
    mode: MigrateSortingMode,
    direction: MigrateSortingDirection,
): List<MigrateSourceItem> = counts.filterKeys { it != LocalSource.ID }.map { (id, count) ->
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
            ScreenHeader(sourceName, subtitle = localized(DMR.strings.pick_the_entries_to_migrate)) {
                val list = entries.orEmpty()
                TooltipIconButton(Icons.Outlined.SelectAll, localized(MR.strings.action_select_all), onClick = { selected = list.mapTo(HashSet()) { it.id } })
                TooltipIconButton(Icons.Outlined.FlipToBack, localized(DMR.strings.invert_the_selection), onClick = {
                    selected = list.map { it.id }.filterNot { it in selected }.toSet()
                })
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { navigator.push(MigrationConfigScreen(list.filter { it.id in selected }.map { it.id })) },
                ) { Text(if (selected.isEmpty()) localized(MR.strings.action_migrate) else localized(DMR.strings.migrate, selected.size)) }
                Spacer(Modifier.width(8.dp))
            }
            val list = entries ?: return@Column LoadingScreen()
            if (list.isEmpty()) {
                EmptyScreen(localized(DMR.strings.no_library_entries_from_this_source))
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
        Modifier.fillMaxWidth().checkRow(selected) { onToggle() }
            // ZOUKAN -->
            // Android's long press on an entry selects it
            .onRightClick(onToggle)
            // ZOUKAN <--
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowCheckbox(selected, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        CoverImage(Cover.of(manga), Modifier.size(width = 40.dp, height = 56.dp).clip(Corners.Medium).coverOutline(Corners.Medium))
        Spacer(Modifier.width(12.dp))
        Text(manga.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TooltipIconButton(Icons.AutoMirrored.Outlined.OpenInNew, localized(KMR.strings.open), onClick = onOpen)
    }
}
