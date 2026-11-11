package zoukan.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DisabledByDefault
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.distinctUntilChanged
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.MangaGrid
import zoukan.desktop.ui.common.MangaGridItem
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClick
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.i18n.desktop.DMR

data class SourceScreen(
    val sourceId: Long,
    val latest: Boolean = false,
    val query: String? = null,
    // ZOUKAN -->
    val savedSearch: Long? = null,
    // ZOUKAN <--
) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "source-$sourceId-$latest-$query-$savedSearch/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { SourceScreenModel(sourceId, latest, query, savedSearchId = savedSearch) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        // ZOUKAN -->
        val libraryToggle = rememberQuickLibraryToggle()
        QuickLibraryToggleDialogs(libraryToggle)
        // ZOUKAN <--
        val source = model.source
        var searchText by rememberSaveable { mutableStateOf(query.orEmpty()) }
        var showFilters by rememberSaveable { mutableStateOf(false) }
        // ZOUKAN -->
        val bulk = remember { BulkFavorite() }
        // ZOUKAN <--

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(source?.name ?: localized(DMR.strings.source_not_installed_2), subtitle = source?.let { languageName(it.lang) }) {
                SearchField(
                    searchText,
                    { searchText = it },
                    localized(DMR.strings.search_2, source?.name.orEmpty()),
                    Modifier.width(300.dp),
                    onSearch = { model.search(searchText) },
                )
                if (source != null && source.getFilterList().isNotEmpty()) {
                    TooltipIconButton(Icons.Outlined.FilterList, localized(DMR.strings.filters), onClick = { showFilters = !showFilters })
                }
                // ZOUKAN -->
                if (source != null) BulkSelectButton(bulk)
                // ZOUKAN <--
                if (source != null) {
                    TooltipIconButton(Icons.Outlined.DynamicFeed, localized(DMR.strings.this_sources_feed), onClick = { navigator.push(SourceFeedScreen(source.id)) })
                }
                if (source is HttpSource) {
                    TooltipIconButton(Icons.Outlined.Public, localized(DMR.strings.open_the_site_sign_in_cloudflare), onClick = { model.openWebsite() })
                }
                if (source is ConfigurableSource) {
                    TooltipIconButton(Icons.Outlined.Settings, localized(MR.strings.source_settings), onClick = { navigator.push(SourceSettingsScreen(source.id)) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.listing == SourceScreenModel.Listing.Popular,
                    onClick = { model.setListing(SourceScreenModel.Listing.Popular) },
                    label = { Text(localized(MR.strings.popular)) },
                )
                if (source?.supportsLatest == true) {
                    FilterChip(
                        selected = state.listing == SourceScreenModel.Listing.Latest,
                        onClick = { model.setListing(SourceScreenModel.Listing.Latest) },
                        label = { Text(localized(MR.strings.latest)) },
                    )
                }
                (state.listing as? SourceScreenModel.Listing.Search)?.takeIf { state.savedSearch == null }?.let {
                    FilterChip(selected = true, onClick = {}, label = { Text(if (it.query.isBlank()) localized(DMR.strings.filtered) else "\"${it.query}\"") })
                }
                // ZOUKAN -->
                // Saved searches; right-click one to delete it
                state.savedSearches.forEach { saved ->
                    var confirm by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = state.savedSearch == saved.id,
                        onClick = { model.applySavedSearch(saved) },
                        label = { Text(saved.name) },
                        leadingIcon = { Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp)) },
                        modifier = Modifier.onRightClick { confirm = true },
                    )
                    if (confirm) {
                        AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text(localized(DMR.strings.delete_the_saved_search, saved.name)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirm = false
                                    model.deleteSavedSearch(saved.id)
                                }) { Text(localized(MR.strings.action_delete)) }
                            },
                            dismissButton = { TextButton(onClick = { confirm = false }) { Text(localized(MR.strings.action_cancel)) } },
                        )
                    }
                }
                // ZOUKAN <--
            }
            // ZOUKAN -->
            BulkFavoriteBar(bulk, state.items)
            // ZOUKAN <--
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (source == null) {
                        EmptyScreen(localized(DMR.strings.this_sources_extension_is_not_installed))
                    } else if (state.items.isEmpty() && !state.loading && state.error == null && !state.hasNextPage) {
                        EmptyScreen(localized(DMR.strings.nothing_found))
                    } else if (state.items.isEmpty() && state.error != null) {
                        EmptyScreen(state.error!!, actionLabel = localized(MR.strings.action_retry), onAction = model::retry)
                    } else {
                        val gridState = rememberLazyGridState()
                        LaunchedEffect(gridState, state.listing) {
                            snapshotFlow {
                                val info = gridState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                last >= info.totalItemsCount - 8
                            }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) model.loadNextPage() }
                        }
                        // ZOUKAN -->
                        val sourcePreferences = remember { Injekt.get<eu.kanade.domain.source.service.SourcePreferences>() }
                        val hideInLibrary by sourcePreferences.hideInLibraryItems().changes()
                            .collectAsState(sourcePreferences.hideInLibraryItems().get())
                        val shown = if (hideInLibrary) state.items.filterNot { it.id in state.favorites } else state.items
                        // ZOUKAN <--
                        MangaGrid(state = gridState) {
                            items(/* ZOUKAN --> */shown/* ZOUKAN <-- */, key = { it.id }) { manga ->
                                MangaGridItem(
                                    title = manga.title,
                                    cover = Cover.of(manga),
                                    badge = if (manga.id in state.favorites) localized(MR.strings.in_library) else null,
                                    dimmed = manga.id in state.favorites,
                                    // ZOUKAN -->
                                    onClick = { if (bulk.active) bulk.toggle(manga) else navigator.push(MangaScreen(manga.id)) },
                                    // While selecting, right-click selects; otherwise it adds or removes (Android's long press)
                                    onSecondaryClick = { if (bulk.active) bulk.toggle(manga) else libraryToggle.toggle(manga) },
                                    modifier = Modifier.bulkSelected(bulk.isSelected(manga)),
                                    // ZOUKAN <--
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        state.loading -> CircularProgressIndicator()
                                        state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                                            TextButton(onClick = model::retry) { Text(localized(MR.strings.action_retry)) }
                                        }
                                        state.hasNextPage -> TextButton(onClick = model::loadNextPage) { Text(localized(DMR.strings.load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (showFilters && source != null) {
                    VerticalDivider()
                    FilterPanel(model, state.filterVersion)
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(model: SourceScreenModel, version: Int) {
    Column(Modifier.width(340.dp).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::resetFilters) { Text(localized(MR.strings.action_reset)) }
            // ZOUKAN -->
            var saving by remember { mutableStateOf(false) }
            TextButton(onClick = { saving = true }) { Text(localized(MR.strings.action_save)) }
            if (saving) {
                var name by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { saving = false },
                    title = { Text(localized(DMR.strings.save_this_search)) },
                    text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(localized(MR.strings.name)) }) },
                    confirmButton = {
                        TextButton(enabled = name.isNotBlank(), onClick = {
                            saving = false
                            model.saveSearch(name)
                        }) { Text(localized(MR.strings.action_save)) }
                    },
                    dismissButton = { TextButton(onClick = { saving = false }) { Text(localized(MR.strings.action_cancel)) } },
                )
            }
            // ZOUKAN <--
            Spacer(Modifier.weight(1f))
            Button(onClick = model::applyFilters) { Text(localized(MR.strings.action_filter)) }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(model.filters.toList()) { filter -> FilterItem(filter, version, model::filtersChanged) }
        }
    }
}

// [version] changes whenever a filter's state does: Filter.state is not Compose state, so the
// parameter is what makes the items redraw
@Composable
private fun FilterItem(filter: Filter<*>, version: Int, onChange: () -> Unit) {
    when (filter) {
        is Filter.Header -> Text(
            filter.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        is Filter.Separator -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is Filter.CheckBox -> Row(
            Modifier.fillMaxWidth().toggleable(filter.state, onValueChange = {
                filter.state = it
                onChange()
            })
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = filter.state, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(filter.name)
        }
        is Filter.TriState -> Row(
            Modifier.fillMaxWidth().clickable {
                filter.state = (filter.state + 1) % 3
                onChange()
            }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, tint) = when (filter.state) {
                Filter.TriState.STATE_INCLUDE -> Icons.Default.CheckBox to MaterialTheme.colorScheme.primary
                Filter.TriState.STATE_EXCLUDE -> Icons.Default.DisabledByDefault to MaterialTheme.colorScheme.error
                else -> Icons.Default.CheckBoxOutlineBlank to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Text(filter.name)
        }
        is Filter.Text -> OutlinedTextField(
            value = filter.state,
            onValueChange = {
                filter.state = it
                onChange()
            },
            label = { Text(filter.name) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        is Filter.Select<*> -> {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${filter.name}: ${filter.values.getOrNull(filter.state)}", modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    filter.values.forEachIndexed { i, value ->
                        DropdownMenuItem(text = { Text(value.toString()) }, onClick = {
                            filter.state = i
                            open = false
                            onChange()
                        })
                    }
                }
            }
        }
        is Filter.Sort -> {
            var expanded by remember { mutableStateOf(true) }
            GroupHeader(filter.name, expanded) { expanded = !expanded }
            if (expanded) {
                filter.values.forEachIndexed { i, value ->
                    val selection = filter.state
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            filter.state = Filter.Sort.Selection(i, if (selection?.index == i) !selection.ascending else false)
                            onChange()
                        }.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(32.dp)) {
                            if (selection?.index == i) {
                                Icon(
                                    if (selection.ascending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(value)
                    }
                }
            }
        }
        is Filter.Group<*> -> {
            var expanded by remember { mutableStateOf(false) }
            GroupHeader(filter.name, expanded) { expanded = !expanded }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp)) {
                    filter.state.filterIsInstance<Filter<*>>().forEach { FilterItem(it, version, onChange) }
                }
            }
        }
        is Filter.AutoComplete -> OutlinedTextField(
            value = filter.state.joinToString(", "),
            onValueChange = { text ->
                filter.state = text.split(',').map(String::trim).filter(String::isNotEmpty)
                onChange()
            },
            label = { Text(filter.name) },
            placeholder = { Text(filter.hint) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun GroupHeader(name: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
}
