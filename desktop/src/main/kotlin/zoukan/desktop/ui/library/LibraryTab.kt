package zoukan.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.OnShortcut
import zoukan.desktop.ui.Shortcuts
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.downloads.DownloadsButton
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.stats.StatsScreen
import zoukan.i18n.desktop.DMR

@Composable
fun LibraryTab(model: LibraryScreenModel) {
    val state by model.state.collectAsState()
    val refresh by Injekt.get<LibraryUpdater>().progress.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    var settingsDialog by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // ZOUKAN -->
    var favoritesSyncDialog by remember { mutableStateOf<FavoritesSyncDialog?>(null) }
    val showSyncFavorites = LibrarySync.showFavorites()
    val showSyncLibrary = LibrarySync.showLibrary()
    // ZOUKAN <--
    // The entries a categories or remove dialog is for: the selection, or one entry's context menu
    var categoryDialog by remember { mutableStateOf<Set<Long>?>(null) }
    var removeDialog by remember { mutableStateOf<Set<Long>?>(null) }
    val settings = state.settings
    val visible = state.visible
    // Ctrl/Cmd + A selects everything shown; Escape clears the selection before it means "back"
    OnShortcut { action ->
        when (action) {
            Shortcuts.Action.SelectAll -> {
                model.selectAll()
                true
            }
            Shortcuts.Action.Back -> state.selection.isNotEmpty().also { if (it) model.clearSelection() }
            else -> false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.selection.isNotEmpty()) {
            SelectionBar(
                model,
                count = state.selection.size,
                onCategories = { categoryDialog = state.selection },
                onRemove = { removeDialog = state.selection },
                // ZOUKAN -->
                onMigrate = {
                    // In the library's order, as the Android app's selection
                    val ids = (state.visible.map { it.id }.filter { it in state.selection } + state.selection).distinct()
                    model.clearSelection()
                    navigator.push(MigrationConfigScreen(ids))
                },
                // ZOUKAN <--
            )
        } else {
            ScreenHeader(
                title = localized(MR.strings.label_library),
                subtitle = if (state.loading) null else localized(DMR.strings.entries, state.items.distinctBy { it.id }.size),
                showBack = false,
            ) {
                SearchField(state.query, model::search, localized(DMR.strings.search_library), Modifier.width(280.dp).padding(end = 8.dp))
                TooltipIconButton(
                    Icons.Outlined.FilterList,
                    if (settings.hasFilter) localized(DMR.strings.filter_sort_and_display_filtered) else localized(DMR.strings.filter_sort_and_display),
                    onClick = { settingsDialog = true },
                )
                DownloadsButton()
                // ZOUKAN -->
                TooltipIconButton(Icons.Outlined.QueryStats, localized(MR.strings.label_stats), onClick = { navigator.push(StatsScreen) })
                // ZOUKAN <--
                TooltipIconButton(
                    Icons.Outlined.Refresh,
                    if (state.selectedCategory == null) localized(MR.strings.action_update_library) else localized(MR.strings.action_update_category),
                    onClick = model::refresh,
                    enabled = refresh == null,
                )
                Row {
                    TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { moreMenu = true })
                    DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(localized(DMR.strings.open_a_random_entry)) },
                            onClick = {
                                moreMenu = false
                                model.randomEntry()?.let { navigator.push(MangaScreen(it)) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(MR.strings.action_select_all)) },
                            onClick = {
                                moreMenu = false
                                model.selectAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(MR.strings.action_edit_categories)) },
                            onClick = {
                                moreMenu = false
                                navigator.push(CategoryScreen)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(KMR.strings.option_label_library_update_errors)) },
                            onClick = {
                                moreMenu = false
                                navigator.push(UpdateErrorsScreen)
                            },
                        )
                        // ZOUKAN -->
                        // SY's sync entries, as Android's library overflow
                        if (showSyncFavorites) {
                            DropdownMenuItem(
                                text = { Text(localized(SYMR.strings.sync_favorites)) },
                                onClick = {
                                    moreMenu = false
                                    favoritesSyncDialog = if (LibrarySync.showsNotes()) FavoritesSyncDialog.Notes else FavoritesSyncDialog.Confirm
                                },
                            )
                        }
                        if (showSyncLibrary) {
                            DropdownMenuItem(
                                text = { Text(localized(SYMR.strings.sync_library)) },
                                onClick = {
                                    moreMenu = false
                                    LibrarySync.syncLibrary()
                                },
                            )
                        }
                        // ZOUKAN <--
                    }
                }
            }
        }
        LibraryUpdateProgress()
        val tabs = state.tabs
        if (tabs.size > 1 && settings.categoryTabs) {
            val selectedIndex = tabs.indexOfFirst { it.id == state.selectedCategory } + 1
            fun label(name: String, id: Long?) = if (settings.itemCounts) "$name (${state.countIn(id)})" else name
            PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 16.dp) {
                Tab(selected = selectedIndex == 0, onClick = { model.selectCategory(null) }, text = { Text(label(localized(MR.strings.all), null)) })
                tabs.forEach { category ->
                    Tab(
                        selected = state.selectedCategory == category.id,
                        onClick = { model.selectCategory(category.id) },
                        text = { Text(label(if (category.isSystemCategory && !state.grouped) localized(MR.strings.label_default) else category.name, category.id)) },
                    )
                }
            }
        }
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() -> EmptyScreen(
                localized(DMR.strings.your_library_is_empty_find_something),
                actionLabel = localized(MR.strings.browse),
                onAction = { HomeScreen.selectTab(HomeTab.Browse) },
            )
            visible.isEmpty() -> EmptyScreen(
                if (state.query.isNotBlank()) localized(DMR.strings.nothing_matches, state.query) else localized(DMR.strings.no_entries_match_the_filters),
            )
            else -> LibraryContent(
                items = visible,
                settings = settings,
                selection = state.selection,
                onClick = { item, kind ->
                    when {
                        kind == ClickKind.Range -> model.selectRange(item.id)
                        kind == ClickKind.Toggle || state.selection.isNotEmpty() -> model.toggleSelection(item.id)
                        else -> navigator.push(MangaScreen(item.id))
                    }
                },
                onContinue = { item ->
                    scope.launch {
                        val chapter = model.nextChapter(item.id)
                        if (chapter != null) navigator.push(ReaderScreen(item.id, chapter.id)) else Toasts.show(localized(DMR.strings.no_unread_chapters))
                    }
                },
                contextMenu = { item, menu ->
                    // A right click on a selected entry acts on the whole selection; on any other
                    // entry, on that entry alone, and the selection stays as it was
                    val ids = if (item.id in state.selection) state.selection else setOf(item.id)
                    EntryContextMenu(
                        menu = menu,
                        model = model,
                        item = item,
                        ids = ids,
                        selected = item.id in state.selection,
                        onOpen = { navigator.push(MangaScreen(item.id)) },
                        onContinue = {
                            scope.launch {
                                val chapter = model.nextChapter(item.id)
                                if (chapter != null) navigator.push(ReaderScreen(item.id, chapter.id)) else Toasts.show(localized(DMR.strings.no_unread_chapters))
                            }
                        },
                        onCategories = { categoryDialog = ids },
                        onRemove = { removeDialog = ids },
                        onMigrate = {
                            val ordered = state.visible.map { it.id }.filter { it in ids }.ifEmpty { ids.toList() }
                            if (ids == state.selection) model.clearSelection()
                            navigator.push(MigrationConfigScreen(ordered))
                        },
                    )
                },
            )
        }
    }

    if (settingsDialog) LibrarySettingsDialog(model) { settingsDialog = false }
    categoryDialog?.let { ids ->
        CategoriesDialog(model, ids, state.categories.filter { !it.isSystemCategory }) { categoryDialog = null }
    }
    removeDialog?.let { ids ->
        RemoveDialog(ids.size, onConfirm = { model.removeFromLibrary(it, ids) }) { removeDialog = null }
    }
    // ZOUKAN -->
    when (favoritesSyncDialog) {
        FavoritesSyncDialog.Notes -> SyncFavoritesNotesDialog(
            onDismiss = { favoritesSyncDialog = null },
            onAccept = { favoritesSyncDialog = null },
        )
        FavoritesSyncDialog.Confirm -> SyncFavoritesConfirmDialog { favoritesSyncDialog = null }
        null -> {}
    }
    // ZOUKAN <--
}

/** How many unread chapters of each entry a download queues; null for all of them. */
private val downloadChoices = listOf(
    1 to localized(MR.strings.action_next_chapter),
    5 to localized(DMR.strings.next_5_chapters),
    10 to localized(DMR.strings.next_10_chapters),
    25 to localized(DMR.strings.next_25_chapters),
    null to localized(MR.strings.action_display_unread_badge),
)

/** An entry's right-click menu: the selection bar's actions, for [ids]. */
@Composable
private fun ColumnScope.EntryContextMenu(
    menu: ContextMenuState,
    model: LibraryScreenModel,
    item: LibraryScreenModel.Item,
    ids: Set<Long>,
    selected: Boolean,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    onCategories: () -> Unit,
    onRemove: () -> Unit,
    onMigrate: () -> Unit,
) {
    // Download opens its choices in place of the menu, as the selection bar's download button does
    var downloads by remember(menu.isOpen) { mutableStateOf(false) }
    if (downloads) {
        downloadChoices.forEach { (n, label) ->
            ContextMenuItem(menu, label) { model.download(n, ids) }
        }
        return
    }
    if (ids.size == 1) {
        ContextMenuItem(menu, localized(KMR.strings.open), Icons.AutoMirrored.Outlined.OpenInNew, onClick = onOpen)
        if (item.library.unreadCount > 0) ContextMenuItem(menu, localized(DMR.strings.continue_reading), Icons.Outlined.PlayArrow, onClick = onContinue)
    }
    if (selected) {
        ContextMenuItem(menu, localized(DMR.strings.deselect), Icons.Outlined.Deselect) { model.toggleSelection(item.id) }
    } else {
        ContextMenuItem(menu, localized(DMR.strings.select), Icons.Outlined.CheckCircle) { model.toggleSelection(item.id) }
    }
    ContextMenuDivider()
    ContextMenuItem(menu, localized(DMR.strings.change_categories), Icons.AutoMirrored.Outlined.Label, onClick = onCategories)
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_read), Icons.Outlined.DoneAll) { model.markRead(true, ids) }
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_unread), Icons.Outlined.RemoveDone) { model.markRead(false, ids) }
    if (ids.any { id -> model.state.value.items.any { it.id == id && it.manga.source != LocalSource.ID } }) {
        DropdownMenuItem(
            text = { Text(localized(MR.strings.action_download)) },
            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
            onClick = { downloads = true },
        )
        ContextMenuItem(menu, localized(DMR.strings.delete_downloads), Icons.Outlined.BookmarkRemove) { model.deleteDownloads(ids) }
    }
    // ZOUKAN -->
    ContextMenuItem(menu, localized(MR.strings.action_migrate), Icons.Outlined.SwapHoriz, onClick = onMigrate)
    // ZOUKAN <--
    ContextMenuDivider()
    ContextMenuItem(menu, localized(MR.strings.remove_from_library), Icons.Outlined.Delete, onClick = onRemove)
}

@Composable
private fun SelectionBar(
    model: LibraryScreenModel,
    count: Int,
    onCategories: () -> Unit,
    onRemove: () -> Unit,
    // ZOUKAN -->
    onMigrate: () -> Unit,
    // ZOUKAN <--
) {
    var downloadMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(Icons.Outlined.Close, localized(DMR.strings.clear_selection), onClick = model::clearSelection)
            Text(localized(DMR.strings.selected, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TooltipIconButton(Icons.Outlined.SelectAll, localized(MR.strings.action_select_all), onClick = model::selectAll)
            TooltipIconButton(Icons.Outlined.FlipToBack, localized(DMR.strings.invert_selection), onClick = model::invertSelection)
            TooltipIconButton(Icons.AutoMirrored.Outlined.Label, localized(DMR.strings.change_categories), onClick = onCategories)
            TooltipIconButton(Icons.Outlined.DoneAll, localized(MR.strings.action_mark_as_read), onClick = { model.markRead(true) })
            TooltipIconButton(Icons.Outlined.RemoveDone, localized(MR.strings.action_mark_as_unread), onClick = { model.markRead(false) })
            Row {
                TooltipIconButton(Icons.Outlined.Download, localized(MR.strings.action_download), onClick = { downloadMenu = true })
                DropdownMenu(downloadMenu, onDismissRequest = { downloadMenu = false }) {
                    downloadChoices
                        .forEach { (n, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    downloadMenu = false
                                    model.download(n)
                                },
                            )
                        }
                }
            }
            TooltipIconButton(Icons.Outlined.BookmarkRemove, localized(DMR.strings.delete_downloads), onClick = model::deleteDownloads)
            // ZOUKAN -->
            TooltipIconButton(Icons.Outlined.SwapHoriz, localized(MR.strings.action_migrate), onClick = onMigrate)
            // ZOUKAN <--
            TooltipIconButton(Icons.Outlined.Delete, localized(MR.strings.remove_from_library), onClick = onRemove)
        }
    }
}

/** Sets the categories of the selected entries; a dash keeps each entry's own. */
@Composable
private fun CategoriesDialog(model: LibraryScreenModel, ids: Set<Long>, categories: List<Category>, onDismiss: () -> Unit) {
    val (all, some) = remember { model.selectionCategories(ids) }
    val states = remember {
        androidx.compose.runtime.mutableStateMapOf<Long, ToggleableState>().apply {
            categories.forEach { c ->
                put(
                    c.id,
                    when (c.id) {
                        in all -> ToggleableState.On
                        in some -> ToggleableState.Indeterminate
                        else -> ToggleableState.Off
                    },
                )
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.action_move_category)) },
        text = {
            if (categories.isEmpty()) {
                Text(localized(DMR.strings.there_are_no_categories_yet_add))
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                    categories.forEach { c ->
                        val value = states[c.id] ?: ToggleableState.Off
                        val next = {
                            states[c.id] = if (value == ToggleableState.On) ToggleableState.Off else ToggleableState.On
                        }
                        Row(Modifier.fillMaxWidth().clickable(onClick = next), verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(value, onClick = next)
                            Text(c.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val add = states.filterValues { it == ToggleableState.On }.keys
                val remove = states.filterValues { it == ToggleableState.Off }.keys
                model.setCategories(add, remove, ids)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun RemoveDialog(count: Int, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteDownloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) localized(DMR.strings.remove_from_library) else localized(DMR.strings.remove_entries_from_library, count)) },
        text = {
            Row(Modifier.clickable { deleteDownloads = !deleteDownloads }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteDownloads, onCheckedChange = { deleteDownloads = it })
                Text(localized(DMR.strings.also_delete_downloaded_chapters))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(deleteDownloads)
                onDismiss()
            }) { Text(localized(MR.strings.action_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** The running library update's progress, under a screen's header. */
@Composable
fun LibraryUpdateProgress() {
    val updater = remember { Injekt.get<LibraryUpdater>() }
    val progress by updater.progress.collectAsState()
    progress?.let { p ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (p.total == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                if (p.total == 0) localized(DMR.strings.starting_the_library_update) else localized(DMR.strings.updating_of, p.done, p.total, p.current.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
