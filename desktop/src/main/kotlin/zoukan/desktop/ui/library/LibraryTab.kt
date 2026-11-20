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
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_display_unread_badge
import tachiyomi.i18n.action_download
import tachiyomi.i18n.action_edit_categories
import tachiyomi.i18n.action_mark_as_read
import tachiyomi.i18n.action_mark_as_unread
import tachiyomi.i18n.action_migrate
import tachiyomi.i18n.action_move_category
import tachiyomi.i18n.action_next_chapter
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.action_remove
import tachiyomi.i18n.action_select_all
import tachiyomi.i18n.action_update_category
import tachiyomi.i18n.action_update_library
import tachiyomi.i18n.all
import tachiyomi.i18n.browse
import tachiyomi.i18n.download_bookmarked
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.action_remove_merged
import tachiyomi.i18n.kmk.action_update
import tachiyomi.i18n.kmk.`open`
import tachiyomi.i18n.kmk.option_label_library_update_errors
import tachiyomi.i18n.kmk.updating
import tachiyomi.i18n.label_default
import tachiyomi.i18n.label_library
import tachiyomi.i18n.label_more
import tachiyomi.i18n.label_stats
import tachiyomi.i18n.remove_from_library
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.action_clean_titles
import tachiyomi.i18n.sy.mangadex_add_to_follows
import tachiyomi.i18n.sy.merge
import tachiyomi.i18n.sy.no_valid_entry
import tachiyomi.i18n.sy.rec_search_short
import tachiyomi.i18n.sy.reset_info
import tachiyomi.i18n.sy.sync_favorites
import tachiyomi.i18n.sy.sync_library
import tachiyomi.i18n.update_already_running
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.LocalHomeController
import zoukan.desktop.ui.OnShortcut
import zoukan.desktop.ui.Shortcuts
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.ConfirmDestructiveDialog
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.RowTriStateCheckbox
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.SelectionBar
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.downloads.DownloadsButton
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.pickScreen
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.recs.CommonRecommendsScreen
import zoukan.desktop.ui.recs.RecommendationSearchDialog
import zoukan.desktop.ui.stats.StatsScreen
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.also_delete_downloaded_chapters
import zoukan.i18n.desktop.change_categories
import zoukan.i18n.desktop.continue_reading
import zoukan.i18n.desktop.delete_downloads
import zoukan.i18n.desktop.delete_downloads_cannot_be_undone
import zoukan.i18n.desktop.delete_downloads_of_entries
import zoukan.i18n.desktop.deselect
import zoukan.i18n.desktop.entries
import zoukan.i18n.desktop.filter_sort_and_display
import zoukan.i18n.desktop.filter_sort_and_display_filtered
import zoukan.i18n.desktop.next_10_chapters
import zoukan.i18n.desktop.next_25_chapters
import zoukan.i18n.desktop.next_5_chapters
import zoukan.i18n.desktop.no_entries_match_the_filters
import zoukan.i18n.desktop.no_unread_chapters
import zoukan.i18n.desktop.nothing_matches
import zoukan.i18n.desktop.open_a_random_entry
import zoukan.i18n.desktop.remove_entries_from_library
import zoukan.i18n.desktop.search_library
import zoukan.i18n.desktop.select
import zoukan.i18n.desktop.starting_the_library_update
import zoukan.i18n.desktop.there_are_no_categories_yet_add
import zoukan.i18n.desktop.updating_of
import zoukan.i18n.desktop.your_library_is_empty_find_something
import zoukan.i18n.desktop.remove_from_library as dmr_remove_from_library

@Composable
fun LibraryTab(model: LibraryScreenModel) {
    val state by model.state.collectAsState()
    // Only whether an update runs, so each progress tick doesn't recompose the whole library
    val updating by rememberLibraryUpdateRunning()
    val navigator = LocalNavigator.currentOrThrow
    val home = LocalHomeController.current
    val scope = rememberCoroutineScope()
    var moreMenu by remember { mutableStateOf(false) }
    // ZOUKAN -->
    val showSyncFavorites = LibrarySync.showFavorites()
    val showSyncLibrary = LibrarySync.showLibrary()
    // ZOUKAN <--
    val settings = state.settings
    // The overflow-menu flags, found once per selection instead of on every recomposition
    val selectionFlags by remember(state.selection, state.items) { derivedStateOf { model.selectionFlags(state) } }
    val canDownload = remember(state.selection, state.items) { state.selectionCanDownload }
    val hasRemote = remember(state.selection, state.items) { state.selectionHasRemote }
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
            LibrarySelectionBar(
                model,
                count = state.selection.size,
                onCategories = { model.openDialog(LibraryDialog.Categories(state.selection)) },
                onRemove = { model.openDialog(LibraryDialog.Remove(state.selection)) },
                // ZOUKAN -->
                onDeleteDownloads = { model.openDialog(LibraryDialog.DeleteDownloads(state.selection)) }.takeIf { hasRemote },
                // ZOUKAN <--
                showDownload = canDownload,
                // ZOUKAN -->
                onMigrate = {
                    // In the library's order, as the Android app's selection
                    val ids = (state.visible.map { it.id }.filter { it in state.selection } + state.selection).distinct()
                    model.clearSelection()
                    navigator.push(MigrationConfigScreen(ids))
                },
                // ZOUKAN <--
                // KMK -->
                onMerge = {
                    val selectedManga = model.selectedManga()
                    if (selectedManga.size == 1) {
                        // Invoke merging for this manga
                        model.clearSelection()
                        navigator.push(pickScreen(selectedManga.first(), merge = true))
                    } else if (selectedManga.isNotEmpty()) {
                        // Invoke multiple merge
                        model.clearSelection()
                        // Android's snackbar then asks whether to remove the merged entries, and opens the merge
                        model.mergeMultiple(selectedManga)
                    } else {
                        model.clearSelection()
                        Toasts.show(localized(SYMR.strings.no_valid_entry))
                    }
                },
                onSelectionUpdate = {
                    val started = model.updateSelectedManga()
                    if (started) model.clearSelection()
                    Toasts.show(localized(if (started) KMR.strings.updating else MR.strings.update_already_running))
                },
                // KMK <--
                // SY -->
                onCleanTitles = model::cleanTitles.takeIf { selectionFlags.cleanTitles },
                onCollectRecommendations = { model.openDialog(LibraryDialog.Recommendations(model.selectedManga())) }.takeIf { state.selection.size > 1 },
                onAddToMangaDex = model::syncMangaToDex.takeIf { selectionFlags.addToMangadex },
                onResetInfo = model::resetInfo.takeIf { selectionFlags.resetInfo },
                // SY <--
            )
        } else {
            ScreenHeader(
                title = localized(MR.strings.label_library),
                subtitle = if (state.loading) null else localizedPlural(DMR.plurals.entries, state.entryCount, state.entryCount),
                showBack = false,
            ) {
                SearchField(state.query, model::search, localized(DMR.strings.search_library), Modifier.width(280.dp).padding(end = 8.dp))
                TooltipIconButton(
                    Icons.Outlined.FilterList,
                    if (settings.hasFilter) localized(DMR.strings.filter_sort_and_display_filtered) else localized(DMR.strings.filter_sort_and_display),
                    onClick = { model.openDialog(LibraryDialog.Settings) },
                )
                DownloadsButton()
                // ZOUKAN -->
                TooltipIconButton(Icons.Outlined.QueryStats, localized(MR.strings.label_stats), onClick = { navigator.push(StatsScreen) })
                // ZOUKAN <--
                TooltipIconButton(
                    Icons.Outlined.Refresh,
                    if (state.selectedCategory == null) localized(MR.strings.action_update_library) else localized(MR.strings.action_update_category),
                    onClick = model::refresh,
                    enabled = !updating,
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
                                    model.openDialog(
                                        if (LibrarySync.showsNotes()) LibraryDialog.FavoritesSyncNotes else LibraryDialog.FavoritesSyncConfirm,
                                    )
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
                onAction = { home?.selectTab(HomeTab.Browse) ?: HomeScreen.selectTab(HomeTab.Browse) },
            )
            // ZOUKAN -->
            visible.isEmpty() -> Column(Modifier.fillMaxSize()) {
                // As Android's LibraryPager: the global search above the empty results
                if (state.query.isNotEmpty()) {
                    GlobalSearchItem(state.query, { navigator.push(GlobalSearchScreen(state.query)) }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                }
                EmptyScreen(
                    if (state.query.isNotBlank()) localized(DMR.strings.nothing_matches, state.query) else localized(DMR.strings.no_entries_match_the_filters),
                )
            }
            // ZOUKAN <--
            else -> LibraryContent(
                // ZOUKAN -->
                searchQuery = state.query,
                onGlobalSearch = { navigator.push(GlobalSearchScreen(state.query)) },
                // ZOUKAN <--
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
                        onCategories = { model.openDialog(LibraryDialog.Categories(ids)) },
                        onRemove = { model.openDialog(LibraryDialog.Remove(ids)) },
                        onDeleteDownloads = { model.openDialog(LibraryDialog.DeleteDownloads(ids)) },
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

    when (val dialog = state.dialog) {
        null -> Unit
        LibraryDialog.Settings -> LibrarySettingsDialog(model) { model.dismissDialog() }
        is LibraryDialog.Categories -> CategoriesDialog(model, dialog.ids, state.categories.filter { !it.isSystemCategory }) { model.dismissDialog() }
        is LibraryDialog.Remove -> RemoveDialog(dialog.ids.size, onConfirm = { model.removeFromLibrary(it, dialog.ids) }) { model.dismissDialog() }
        // ZOUKAN -->
        is LibraryDialog.DeleteDownloads -> LibraryDeleteDownloadsDialog(
            dialog.ids.size,
            onConfirm = { model.deleteDownloads(dialog.ids) },
            onDismiss = { model.dismissDialog() },
        )
        LibraryDialog.FavoritesSyncNotes -> SyncFavoritesNotesDialog(
            onDismiss = { model.dismissDialog() },
            onAccept = { model.dismissDialog() },
        )
        LibraryDialog.FavoritesSyncConfirm -> SyncFavoritesConfirmDialog { model.dismissDialog() }
        // ZOUKAN <--
        // KMK -->
        is LibraryDialog.RemoveMerged -> {
            val close = { remove: Boolean ->
                if (remove) model.removeMangas(dialog.mergingMangas)
                model.dismissDialog()
                navigator.push(MangaScreen(dialog.mergedMangaId))
            }
            AlertDialog(
                onDismissRequest = { close(false) },
                text = { Text(localized(KMR.strings.action_remove_merged)) },
                confirmButton = { TextButton(onClick = { close(true) }) { Text(localized(MR.strings.action_remove)) } },
                dismissButton = { TextButton(onClick = { close(false) }) { Text(localized(MR.strings.action_cancel)) } },
            )
        }
        // KMK <--
        // SY -->
        is LibraryDialog.Recommendations -> RecommendationSearchDialog(
            onDismissRequest = { model.dismissDialog() },
            onSearchRequest = {
                model.dismissDialog()
                model.clearSelection()
                // The screen runs the search and shows its progress
                navigator.push(CommonRecommendsScreen(dialog.manga.map { it.id }))
            },
        )
        // SY <--
    }
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
    onDeleteDownloads: () -> Unit,
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
    val remoteIds = remember(model.state.value.items) {
        model.state.value.items.filterNot { it.manga.source == LocalSource.ID }.mapTo(HashSet()) { it.id }
    }
    // Download only when no entry is from the local source, as Android's selection bar
    if (ids.all { it in remoteIds }) {
        DropdownMenuItem(
            text = { Text(localized(MR.strings.action_download)) },
            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
            onClick = { downloads = true },
        )
    }
    if (ids.any { it in remoteIds }) {
        ContextMenuItem(menu, localized(DMR.strings.delete_downloads), Icons.Outlined.FolderDelete, onClick = onDeleteDownloads)
    }
    // ZOUKAN -->
    ContextMenuItem(menu, localized(MR.strings.action_migrate), Icons.Outlined.SwapHoriz, onClick = onMigrate)
    // ZOUKAN <--
    ContextMenuDivider()
    ContextMenuItem(menu, localized(MR.strings.remove_from_library), Icons.Outlined.Delete, onClick = onRemove)
}

@Composable
private fun LibrarySelectionBar(
    model: LibraryScreenModel,
    count: Int,
    onCategories: () -> Unit,
    onRemove: () -> Unit,
    // ZOUKAN -->
    onDeleteDownloads: (() -> Unit)?,
    onMigrate: () -> Unit,
    // ZOUKAN <--
    /** False for a selection with a local-source entry, as Android's LibraryTab */
    showDownload: Boolean,
    // KMK -->
    onMerge: () -> Unit,
    onSelectionUpdate: () -> Unit,
    // KMK <--
    // SY -->
    onCleanTitles: (() -> Unit)?,
    onCollectRecommendations: (() -> Unit)?,
    onAddToMangaDex: (() -> Unit)?,
    onResetInfo: (() -> Unit)?,
    // SY <--
) {
    var downloadMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    SelectionBar(count, onClear = model::clearSelection, onSelectAll = model::selectAll, onInvert = model::invertSelection) {
        run {
            TooltipIconButton(Icons.AutoMirrored.Outlined.Label, localized(DMR.strings.change_categories), onClick = onCategories)
            TooltipIconButton(Icons.Outlined.DoneAll, localized(MR.strings.action_mark_as_read), onClick = { model.markRead(true) })
            TooltipIconButton(Icons.Outlined.RemoveDone, localized(MR.strings.action_mark_as_unread), onClick = { model.markRead(false) })
            if (showDownload) {
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
                        DropdownMenuItem(
                            text = { Text(localized(MR.strings.download_bookmarked)) },
                            onClick = {
                                downloadMenu = false
                                model.downloadBookmarked()
                            },
                        )
                    }
                }
            }
            if (onDeleteDownloads != null) {
                TooltipIconButton(Icons.Outlined.FolderDelete, localized(DMR.strings.delete_downloads), onClick = onDeleteDownloads)
            }
            // KMK -->
            TooltipIconButton(Icons.Outlined.Refresh, localized(KMR.strings.action_update), onClick = onSelectionUpdate)
            // KMK <--
            // ZOUKAN -->
            TooltipIconButton(Icons.Outlined.SwapHoriz, localized(MR.strings.action_migrate), onClick = onMigrate)
            // ZOUKAN <--
            TooltipIconButton(Icons.Outlined.Delete, localized(MR.strings.remove_from_library), onClick = onRemove)
            Row {
                TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { overflowMenu = true })
                DropdownMenu(overflowMenu, onDismissRequest = { overflowMenu = false }) {
                    val close = { action: () -> Unit ->
                        overflowMenu = false
                        action()
                    }
                    // KMK -->
                    DropdownMenuItem(text = { Text(localized(SYMR.strings.merge)) }, onClick = { close(onMerge) })
                    if (onCleanTitles != null) {
                        DropdownMenuItem(text = { Text(localized(SYMR.strings.action_clean_titles)) }, onClick = { close(onCleanTitles) })
                    }
                    if (onCollectRecommendations != null) {
                        DropdownMenuItem(text = { Text(localized(SYMR.strings.rec_search_short)) }, onClick = { close(onCollectRecommendations) })
                    }
                    if (onAddToMangaDex != null) {
                        DropdownMenuItem(text = { Text(localized(SYMR.strings.mangadex_add_to_follows)) }, onClick = { close(onAddToMangaDex) })
                    }
                    if (onResetInfo != null) {
                        DropdownMenuItem(text = { Text(localized(SYMR.strings.reset_info)) }, onClick = { close(onResetInfo) })
                    }
                    // KMK <--
                }
            }
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
                        Row(
                            Modifier.fillMaxWidth().triStateToggleable(state = value, role = Role.Checkbox, onClick = next),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowTriStateCheckbox(value, onClick = null)
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
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

// ZOUKAN -->
/** Asks before the downloaded chapters of [entryCount] library entries are deleted. */
@Composable
internal fun LibraryDeleteDownloadsDialog(entryCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDestructiveDialog(
        title = localizedPlural(DMR.plurals.delete_downloads_of_entries, entryCount, entryCount),
        text = localized(DMR.strings.delete_downloads_cannot_be_undone),
        confirmLabel = localized(DMR.strings.delete_downloads),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
// ZOUKAN <--

@Composable
private fun RemoveDialog(count: Int, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteDownloads by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) localized(DMR.strings.dmr_remove_from_library) else localized(DMR.strings.remove_entries_from_library, count)) },
        text = {
            Row(Modifier.checkRow(deleteDownloads) { deleteDownloads = it }, verticalAlignment = Alignment.CenterVertically) {
                RowCheckbox(deleteDownloads, onCheckedChange = null)
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

/** Whether a library update runs, without recomposing the caller on every progress tick. */
@Composable
internal fun rememberLibraryUpdateRunning(
    progress: StateFlow<LibraryUpdater.Progress?> = remember { Injekt.get<LibraryUpdater>().progress },
): State<Boolean> {
    val state = progress.collectAsState()
    return remember(state) { derivedStateOf { state.value != null } }
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
