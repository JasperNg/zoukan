package zoukan.desktop.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.OnShortcut
import zoukan.desktop.ui.Shortcuts
import zoukan.desktop.ui.category.CategoryScreen
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
    var categoryDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
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
                onCategories = { categoryDialog = true },
                onRemove = { removeDialog = true },
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
                title = "Library",
                subtitle = if (state.loading) null else "${state.items.distinctBy { it.id }.size} entries",
                showBack = false,
            ) {
                SearchField(state.query, model::search, "Search library", Modifier.width(280.dp).padding(end = 8.dp))
                TooltipIconButton(
                    Icons.Default.FilterList,
                    if (settings.hasFilter) "Filter, sort and display (filtered)" else "Filter, sort and display",
                    onClick = { settingsDialog = true },
                )
                DownloadsButton()
                // ZOUKAN -->
                TooltipIconButton(Icons.Default.QueryStats, "Statistics", onClick = { navigator.push(StatsScreen) })
                // ZOUKAN <--
                TooltipIconButton(
                    Icons.Default.Refresh,
                    if (state.selectedCategory == null) "Update library" else "Update category",
                    onClick = model::refresh,
                    enabled = refresh == null,
                )
                Row {
                    TooltipIconButton(Icons.Default.MoreVert, "More", onClick = { moreMenu = true })
                    DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open a random entry") },
                            onClick = {
                                moreMenu = false
                                model.randomEntry()?.let { navigator.push(MangaScreen(it)) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Select all") },
                            onClick = {
                                moreMenu = false
                                model.selectAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit categories") },
                            onClick = {
                                moreMenu = false
                                navigator.push(CategoryScreen)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Library update errors") },
                            onClick = {
                                moreMenu = false
                                navigator.push(UpdateErrorsScreen)
                            },
                        )
                        // ZOUKAN -->
                        // SY's sync entries, as Android's library overflow
                        if (showSyncFavorites) {
                            DropdownMenuItem(
                                text = { Text("Sync EH favorites") },
                                onClick = {
                                    moreMenu = false
                                    favoritesSyncDialog = if (LibrarySync.showsNotes()) FavoritesSyncDialog.Notes else FavoritesSyncDialog.Confirm
                                },
                            )
                        }
                        if (showSyncLibrary) {
                            DropdownMenuItem(
                                text = { Text("Sync library") },
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
                Tab(selected = selectedIndex == 0, onClick = { model.selectCategory(null) }, text = { Text(label("All", null)) })
                tabs.forEach { category ->
                    Tab(
                        selected = state.selectedCategory == category.id,
                        onClick = { model.selectCategory(category.id) },
                        text = { Text(label(if (category.isSystemCategory && !state.grouped) "Default" else category.name, category.id)) },
                    )
                }
            }
        }
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() -> EmptyScreen(
                "Your library is empty. Find something to read in Browse and add it to your library.",
                actionLabel = "Browse",
                onAction = { HomeScreen.selectTab(HomeTab.Browse) },
            )
            visible.isEmpty() -> EmptyScreen(
                if (state.query.isNotBlank()) "Nothing matches \"${state.query}\"" else "No entries match the filters",
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
                        if (chapter != null) navigator.push(ReaderScreen(item.id, chapter.id)) else Toasts.show("No unread chapters")
                    }
                },
            )
        }
    }

    if (settingsDialog) LibrarySettingsDialog(model) { settingsDialog = false }
    if (categoryDialog) CategoriesDialog(model, state.categories.filter { !it.isSystemCategory }) { categoryDialog = false }
    if (removeDialog) RemoveDialog(state.selection.size, onConfirm = model::removeFromLibrary) { removeDialog = false }
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
            TooltipIconButton(Icons.Default.Close, "Clear selection", onClick = model::clearSelection)
            Text("$count selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TooltipIconButton(Icons.Default.SelectAll, "Select all", onClick = model::selectAll)
            TooltipIconButton(Icons.Default.FlipToBack, "Invert selection", onClick = model::invertSelection)
            TooltipIconButton(Icons.AutoMirrored.Filled.Label, "Change categories", onClick = onCategories)
            TooltipIconButton(Icons.Default.DoneAll, "Mark as read", onClick = { model.markRead(true) })
            TooltipIconButton(Icons.Default.RemoveDone, "Mark as unread", onClick = { model.markRead(false) })
            Row {
                TooltipIconButton(Icons.Default.Download, "Download", onClick = { downloadMenu = true })
                DropdownMenu(downloadMenu, onDismissRequest = { downloadMenu = false }) {
                    listOf(1 to "Next chapter", 5 to "Next 5 chapters", 10 to "Next 10 chapters", 25 to "Next 25 chapters", null to "Unread chapters")
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
            TooltipIconButton(Icons.Outlined.BookmarkRemove, "Delete downloads", onClick = model::deleteDownloads)
            // ZOUKAN -->
            TooltipIconButton(Icons.Default.SwapHoriz, "Migrate", onClick = onMigrate)
            // ZOUKAN <--
            TooltipIconButton(Icons.Default.Delete, "Remove from library", onClick = onRemove)
        }
    }
}

/** Sets the categories of the selected entries; a dash keeps each entry's own. */
@Composable
private fun CategoriesDialog(model: LibraryScreenModel, categories: List<Category>, onDismiss: () -> Unit) {
    val (all, some) = remember { model.selectionCategories() }
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
        title = { Text("Set categories") },
        text = {
            if (categories.isEmpty()) {
                Text("There are no categories yet. Add them from More > Edit categories.")
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
                model.setCategories(add, remove)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveDialog(count: Int, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteDownloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Remove from library?" else "Remove $count entries from library?") },
        text = {
            Row(Modifier.clickable { deleteDownloads = !deleteDownloads }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteDownloads, onCheckedChange = { deleteDownloads = it })
                Text("Also delete downloaded chapters")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(deleteDownloads)
                onDismiss()
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                if (p.total == 0) "Starting the library update…" else "Updating ${p.done} of ${p.total}: ${p.current.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
