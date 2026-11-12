package zoukan.desktop.ui.manga

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.Download
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.common.ChapterSwipeBox
import zoukan.desktop.ui.common.ChapterSwipeState
import zoukan.desktop.ui.common.ContextMenu
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClickAt
import zoukan.desktop.ui.common.rememberContextMenuState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.downloads.DownloadIndicator
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.track.TrackingDialog
import zoukan.i18n.desktop.DMR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

data class MangaScreen(val mangaId: Long) : Screen {
    // Ends with / because Voyager disposes every screen model whose key starts with a
    // disposed screen's key: without it, leaving manga-1 cancelled manga-12's work
    override val key: ScreenKey = "manga-$mangaId/"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { MangaScreenModel(mangaId) }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val manga = state.manga ?: return LoadingScreen()
        var mergedDialog by remember { mutableStateOf(false) }
        if (mergedDialog) MergedEntriesDialog(manga.id, onDismiss = { mergedDialog = false })
        // ZOUKAN -->
        var editDialog by remember { mutableStateOf(false) }
        if (editDialog) EditMangaDialog(manga, onDismiss = { editDialog = false })
        var coverDialog by remember { mutableStateOf(false) }
        if (coverDialog) CoverDialog(manga, onDismiss = { coverDialog = false })
        var intervalDialog by remember { mutableStateOf(false) }
        if (intervalDialog) FetchIntervalDialog(manga, onDismiss = { intervalDialog = false })
        // KMK: a merged entry asks which of its entries Open folder and the source name act on
        var mergedAction by remember { mutableStateOf<MergedAction?>(null) }
        mergedAction?.let { action -> MergedEntryPicker(action, model, onDismiss = { mergedAction = null }) }
        var clearDialog by remember { mutableStateOf(false) }
        if (clearDialog) ClearMangaDialog(onDismissRequest = { clearDialog = false }, onConfirm = model::clearManga)
        val isMerged = manga.source == exh.source.MERGED_SOURCE_ID
        val search = remember(navigator, state.source) {
            EntryInfoSearch(
                library = { EntrySearch.search(navigator, it, global = false, library = true) },
                global = { EntrySearch.search(navigator, it, global = true) },
                tag = { EntrySearch.searchGenre(navigator, it, state.source) },
            )
        }
        var migrateDuplicate by remember { mutableStateOf<tachiyomi.domain.manga.model.Manga?>(null) }
        migrateDuplicate?.let { duplicate ->
            MigrateDialog(duplicate.id, manga, onDismiss = { migrateDuplicate = null }, onMigrated = {})
        }
        // ZOUKAN <--

        // ZOUKAN -->
        // The Resume button docks to whichever side of the chapter list it was dragged to,
        // as on Android, through the Android app's readButtonPosition preference.
        // Android persists FabPosition.Start/End as "Start"/"End" while desktop's Compose
        // material3 spells them "FabPosition.Start"/"FabPosition.End": read both spellings,
        // write Android's, as the preference travels in backups between the apps
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val readButtonPosition = remember { uiPreferences.readButtonPosition() }
        val fabPosition by readButtonPosition.changes().collectAsState(readButtonPosition.get())
        val fabAtStart = fabPosition == "Start" || fabPosition == FabPosition.Start.toString()
        var offsetX by remember { mutableFloatStateOf(0f) }
        var chaptersLayoutSize by remember { mutableStateOf(IntSize.Zero) }
        var chaptersLayoutX by remember { mutableFloatStateOf(0f) }
        var fabSize by remember { mutableStateOf(IntSize.Zero) }
        var fabX by remember { mutableFloatStateOf(0f) }
        // ZOUKAN <--

        CoverBasedTheme(manga) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(title = manga.title, subtitle = state.source?.toString() ?: localized(DMR.strings.source_not_installed_2)) {
                        TooltipIconButton(Icons.Outlined.Refresh, localized(MR.strings.action_webview_refresh), onClick = { model.refresh() }, enabled = !state.refreshing)
                        if (state.source is eu.kanade.tachiyomi.source.online.HttpSource) {
                            TooltipIconButton(Icons.Outlined.OpenInBrowser, localized(DMR.strings.open_in_your_browser), onClick = model::openInBrowser)
                        }
                        // ZOUKAN -->
                        MoreMenu(
                            manga,
                            onEditMerged = { mergedDialog = true },
                            onEdit = { editDialog = true },
                            onCover = { coverDialog = true },
                            onFetchInterval = { intervalDialog = true },
                            onCopyLink = { EntryActions.copyLink(manga, state.source) }.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
                            onOpenFolder = {
                                if (!isMerged) {
                                    model.openMangaFolder(state.source, manga)
                                } else {
                                    mergedAction = MergedAction(localized(KMR.strings.action_open_folder)) { entry, source -> model.openMangaFolder(source, entry) }
                                }
                            }.takeIf { state.source != null && state.source !is tachiyomi.domain.source.model.StubSource },
                            onClearManga = { clearDialog = true },
                        )
                        // ZOUKAN <--
                    }
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    // ZOUKAN -->
                    // Komikku's tablet layout: details take half the width, at most 450dp
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val infoWidth = (maxWidth / 2).coerceAtMost(450.dp)
                        Row(Modifier.fillMaxSize()) {
                            MangaInfoPane(
                                manga = manga,
                                state = state,
                                model = model,
                                onWebView = model::openInWebView.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
                                onSearch = { query -> EntrySearch.search(navigator, query, global = false) },
                                onOpenMetadata = { navigator.push(MetadataViewScreen(manga.id, manga.source)) },
                                modifier = Modifier.width(infoWidth).fillMaxHeight(),
                                onCoverClick = { coverDialog = true },
                                search = search,
                                onCopyLink = { EntryActions.copyLink(manga, state.source) }.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
                                onSourceClick = {
                                    val source = state.source
                                    when {
                                        source == null || source is tachiyomi.domain.source.model.StubSource -> Unit
                                        !isMerged -> EntrySearch.browseSource(navigator, source)
                                        else -> mergedAction = MergedAction(localized(MR.strings.browse)) { _, entrySource ->
                                            EntrySearch.browseSource(navigator, entrySource)
                                        }
                                    }
                                },
                            )
                            // ZOUKAN <--
                            VerticalDivider()
                            // Chapters
                            Box(
                                Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { coordinates ->
                                    chaptersLayoutSize = coordinates.size
                                    chaptersLayoutX = coordinates.positionInWindow().x
                                },
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    ChapterToolbar(state, model)
                                    HorizontalDivider()
                                    if (state.sortedChapters.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                when {
                                                    state.refreshing -> localized(DMR.strings.loading_chapters)
                                                    // ZOUKAN -->
                                                    state.chapters.isNotEmpty() -> localized(DMR.strings.no_chapters_match_the_filters)
                                                    // ZOUKAN <--
                                                    else -> localized(DMR.strings.no_chapters)
                                                },
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        // ZOUKAN -->
                                        val showMissing = showMissingChapters()
                                        val missing = remember(state.sortedChapters, manga.sortDescending(), showMissing) {
                                            if (showMissing) MissingChapters.of(state.sortedChapters, manga.sortDescending()) else MissingChapters.NONE
                                        }
                                        // ZOUKAN <--
                                        // ZOUKAN -->
                                        val libraryPrefs = remember { Injekt.get<LibraryPreferences>() }
                                        val swipeToStart by libraryPrefs.swipeToStartAction().changes()
                                            .collectAsState(libraryPrefs.swipeToStartAction().get())
                                        val swipeToEnd by libraryPrefs.swipeToEndAction().changes()
                                            .collectAsState(libraryPrefs.swipeToEndAction().get())
                                        // ZOUKAN <--
                                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                                            items(state.sortedChapters, key = { it.id }) { chapter ->
                                                // ZOUKAN -->
                                                missing.before[chapter.id]?.let { MissingChaptersRow(it) }
                                                // ZOUKAN <--
                                                // ZOUKAN -->
                                                // Android's chapter swipe actions (Settings > Library > Chapter swipe)
                                                ChapterSwipeBox(
                                                    toStart = swipeToStart,
                                                    toEnd = swipeToEnd,
                                                    state = ChapterSwipeState(
                                                        read = chapter.read,
                                                        bookmark = chapter.bookmark,
                                                        downloaded = if (state.isLocal) null else chapter.id in state.downloaded || chapter.id in state.queued,
                                                    ),
                                                    onAction = { action ->
                                                        when (action) {
                                                            ChapterSwipeAction.ToggleRead -> model.markRead(listOf(chapter), !chapter.read)
                                                            ChapterSwipeAction.ToggleBookmark -> model.setBookmark(listOf(chapter), !chapter.bookmark)
                                                            ChapterSwipeAction.Download -> when {
                                                                chapter.id in state.queued -> model.cancelDownload(chapter)
                                                                chapter.id in state.downloaded -> model.deleteDownloads(listOf(chapter))
                                                                else -> model.download(listOf(chapter))
                                                            }
                                                            ChapterSwipeAction.Disabled -> Unit
                                                        }
                                                    },
                                                ) {
                                                    // ZOUKAN <--
                                                    ChapterRow(
                                                        chapter = chapter,
                                                        displayMode = manga.displayMode,
                                                        selected = chapter.id in state.selected,
                                                        selecting = state.selected.isNotEmpty(),
                                                        onOpen = { navigator.push(ReaderScreen(manga.id, chapter.id)) },
                                                        onToggleSelect = { model.toggleSelection(chapter) },
                                                        onMarkRead = { model.markRead(listOf(chapter), !chapter.read) },
                                                        onBookmark = { model.setBookmark(listOf(chapter), !chapter.bookmark) },
                                                        onMarkPreviousRead = { model.markPreviousRead(chapter) },
                                                        showDownload = !state.isLocal,
                                                        downloaded = chapter.id in state.downloaded,
                                                        download = state.queued[chapter.id],
                                                        onDownload = { model.download(listOf(chapter)) },
                                                        onCancelDownload = { model.cancelDownload(chapter) },
                                                        onDeleteDownload = { model.deleteDownloads(listOf(chapter)) },
                                                        // A right click on a selected chapter acts on the whole selection
                                                        selectionMenu = if (chapter.id in state.selected && state.selected.size > 1) {
                                                            { menu -> ChapterSelectionMenu(menu, state, model) }
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                    // ZOUKAN -->
                                                }
                                                // ZOUKAN <--
                                            }
                                            // ZOUKAN -->
                                            if (missing.end > 0) item(key = "missing-end") { MissingChaptersRow(missing.end) }
                                            // ZOUKAN <--
                                        }
                                    }
                                }
                                // ZOUKAN -->
                                // Komikku's Start / Resume button, while there is an unread chapter,
                                // draggable left/right and docking to the dragged side like on Android
                                val next = state.nextChapter
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = next != null && state.selected.isEmpty(),
                                    enter = fadeIn() + scaleIn(),
                                    exit = fadeOut() + scaleOut(),
                                    modifier = Modifier
                                        .align(if (fabAtStart) Alignment.BottomStart else Alignment.BottomEnd)
                                        .padding(16.dp),
                                ) {
                                    ExtendedFloatingActionButton(
                                        onClick = { next?.let { navigator.push(ReaderScreen(manga.id, it.id)) } },
                                        modifier = Modifier
                                            .offset { IntOffset(offsetX.roundToInt(), 0) }
                                            .onGloballyPositioned { coordinates ->
                                                fabSize = coordinates.size
                                                fabX = coordinates.positionInWindow().x
                                            }
                                            .pointerInput(Unit) {
                                                detectHorizontalDragGestures(
                                                    onDragEnd = {
                                                        // Dock to the side the button's center ended on, as on Android
                                                        if (fabX - chaptersLayoutX + fabSize.width / 2f >= chaptersLayoutSize.width / 2f) {
                                                            readButtonPosition.set("End")
                                                        } else {
                                                            readButtonPosition.set("Start")
                                                        }
                                                        offsetX = 0f
                                                    },
                                                ) { change, dragAmount ->
                                                    change.consume()
                                                    val newOffsetX = offsetX + dragAmount
                                                    if (!newOffsetX.isNaN()) {
                                                        offsetX = newOffsetX
                                                    }
                                                }
                                            },
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, null)
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (state.hasStarted) localized(MR.strings.action_resume) else localized(MR.strings.action_start))
                                    }
                                }
                                // ZOUKAN <--
                            }
                        }
                    }
                }
            }
        }

        if (state.showTracking) {
            val tracking = rememberScreenModel(tag = "tracking") { TrackingModel(manga) }
            TrackingDialog(tracking, onDismiss = { model.setTrackingVisible(false) })
        }

        // ZOUKAN -->
        state.askTrackChapter?.let { chapter ->
            AlertDialog(
                onDismissRequest = { model.answerTrackQuestion(false) },
                title = { Text(localized(DMR.strings.update_tracking)) },
                text = { Text(localized(DMR.strings.set_the_trackers_progress_to_chapter, chapter.toString().removeSuffix(".0"))) },
                confirmButton = { TextButton(onClick = { model.answerTrackQuestion(true) }) { Text(localized(MR.strings.ext_update)) } },
                dismissButton = { TextButton(onClick = { model.answerTrackQuestion(false) }) { Text(localized(MR.strings.action_not_now)) } },
            )
        }
        // ZOUKAN <--

        // ZOUKAN -->
        state.duplicates?.let { duplicates ->
            DuplicateDialog(
                target = manga,
                duplicates = duplicates,
                onAddAnyway = model::addAnyway,
                onOpen = {
                    model.dismissDuplicates()
                    navigator.push(MangaScreen(it.id))
                },
                onMigrate = {
                    model.dismissDuplicates()
                    migrateDuplicate = it
                },
                onDismiss = model::dismissDuplicates,
            )
        }
        // ZOUKAN <--

        state.categoryChoice?.let { categories ->
            CategoryDialog(
                categories = categories,
                onConfirm = model::addToLibrary,
                onDismiss = model::dismissCategoryChoice,
            )
        }
        // ZOUKAN -->
        // Komikku's In-library long press: the entry's categories, checked as they are now
        state.categoryEdit?.let { (categories, chosen) ->
            ChangeCategoryDialog(
                categories = categories,
                chosen = chosen,
                onConfirm = model::setCategories,
                onDismiss = model::dismissCategoryEdit,
            )
        }
        // ZOUKAN <--
    }
}

// ZOUKAN -->
@Composable
private fun MoreMenu(
    manga: tachiyomi.domain.manga.model.Manga,
    onEditMerged: () -> Unit,
    onEdit: () -> Unit,
    onCover: () -> Unit,
    onFetchInterval: () -> Unit,
    onCopyLink: (() -> Unit)?,
    onOpenFolder: (() -> Unit)?,
    onClearManga: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    var open by remember { mutableStateOf(false) }
    val merged = manga.source == exh.source.MERGED_SOURCE_ID
    Box {
        TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(localized(DMR.strings.edit_details_notes_and_cover)) }, onClick = {
                open = false
                onEdit()
            })
            DropdownMenuItem(text = { Text(localized(MR.strings.manga_cover)) }, onClick = {
                open = false
                onCover()
            })
            if (manga.favorite) {
                DropdownMenuItem(text = { Text(localized(DMR.strings.update_frequency)) }, onClick = {
                    open = false
                    onFetchInterval()
                })
            }
            if (onCopyLink != null) {
                DropdownMenuItem(text = { Text(localized(MR.strings.action_copy_link)) }, onClick = {
                    open = false
                    onCopyLink()
                })
            }
            DropdownMenuItem(text = { Text(localized(DMR.strings.similar_titles_and_recommendations)) }, onClick = {
                open = false
                navigator.push(zoukan.desktop.ui.recs.RecommendsScreen(manga.id))
            })
            DropdownMenuItem(text = { Text(if (merged) localized(DMR.strings.merge_another_entry) else localized(DMR.strings.merge_with_another_entry)) }, onClick = {
                open = false
                navigator.push(pickScreen(manga, merge = true))
            })
            if (merged) {
                DropdownMenuItem(text = { Text(localized(DMR.strings.edit_merged_entries)) }, onClick = {
                    open = false
                    onEditMerged()
                })
            } else if (manga.source != zoukan.desktop.local.LocalSource.ID) {
                DropdownMenuItem(text = { Text(localized(DMR.strings.migrate_to_another_source)) }, onClick = {
                    open = false
                    navigator.push(pickScreen(manga, merge = false))
                })
            }
            // KMK: Open folder (the entry's download folder) and Clear manga
            if (onOpenFolder != null) {
                DropdownMenuItem(text = { Text(localized(KMR.strings.action_open_folder)) }, onClick = {
                    open = false
                    onOpenFolder()
                })
            }
            DropdownMenuItem(text = { Text(localized(KMR.strings.action_clear_manga)) }, onClick = {
                open = false
                onClearManga()
            })
        }
    }
}
// ZOUKAN <--

@Composable
private fun ChapterToolbar(state: MangaScreenModel.State, model: MangaScreenModel) {
    // Ctrl/Cmd + A selects every chapter; Escape clears the selection before it means "back"
    zoukan.desktop.ui.OnShortcut { action ->
        when (action) {
            zoukan.desktop.ui.Shortcuts.Action.SelectAll -> {
                model.selectAll()
                true
            }
            zoukan.desktop.ui.Shortcuts.Action.Back -> state.selected.isNotEmpty().also { if (it) model.clearSelection() }
            else -> false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selected.isEmpty()) {
            // ZOUKAN -->
            val shown = state.sortedChapters.size
            Text(
                if (shown == state.chapters.size) localized(DMR.strings.chapters, state.chapters.size) else localized(DMR.strings.of_chapters, shown, state.chapters.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            var settings by remember { mutableStateOf(false) }
            // Tinted while a filter hides chapters, as on Android
            val tint = if (state.filterActive) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) {
                TooltipIconButton(
                    Icons.Outlined.FilterList,
                    if (state.filterActive) localized(DMR.strings.filter_and_sort_filters_on) else localized(DMR.strings.filter_and_sort),
                    onClick = { settings = true },
                )
            }
            if (settings) ChapterSettingsDialog(state, model, onDismiss = { settings = false })
            // ZOUKAN <--
            if (!state.isLocal) DownloadMenu(model)
            TooltipIconButton(Icons.Outlined.SelectAll, localized(MR.strings.action_select_all), onClick = model::selectAll)
        } else {
            val selected = state.chapters.filter { it.id in state.selected }
            TooltipIconButton(Icons.Outlined.Close, localized(DMR.strings.clear_selection), onClick = model::clearSelection)
            Text(localized(DMR.strings.selected, selected.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TooltipIconButton(Icons.Outlined.DoneAll, localized(MR.strings.action_mark_as_read), onClick = { model.markRead(selected, true) })
            TooltipIconButton(Icons.Outlined.RemoveDone, localized(MR.strings.action_mark_as_unread), onClick = { model.markRead(selected, false) })
            TooltipIconButton(Icons.Outlined.BookmarkAdd, localized(DMR.strings.bookmark), onClick = { model.setBookmark(selected, true) })
            TooltipIconButton(Icons.Outlined.BookmarkRemove, localized(DMR.strings.remove_bookmark), onClick = { model.setBookmark(selected, false) })
            if (!state.isLocal) {
                TooltipIconButton(Icons.Outlined.Download, localized(MR.strings.action_download), onClick = { model.download(selected) })
                TooltipIconButton(Icons.Outlined.Delete, localized(DMR.strings.delete_downloads), onClick = { model.deleteDownloads(selected) })
            }
        }
    }
}

/** A selected chapter's right-click menu: the selection toolbar's actions. */
@Composable
private fun ColumnScope.ChapterSelectionMenu(menu: ContextMenuState, state: MangaScreenModel.State, model: MangaScreenModel) {
    val selected = state.chapters.filter { it.id in state.selected }
    ContextMenuItem(menu, localized(DMR.strings.clear_selection), Icons.Outlined.Close, onClick = model::clearSelection)
    ContextMenuDivider()
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_read), Icons.Outlined.DoneAll) { model.markRead(selected, true) }
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_unread), Icons.Outlined.RemoveDone) { model.markRead(selected, false) }
    ContextMenuItem(menu, localized(DMR.strings.bookmark), Icons.Outlined.BookmarkAdd) { model.setBookmark(selected, true) }
    ContextMenuItem(menu, localized(DMR.strings.remove_bookmark), Icons.Outlined.BookmarkRemove) { model.setBookmark(selected, false) }
    if (!state.isLocal) {
        ContextMenuItem(menu, localized(MR.strings.action_download), Icons.Outlined.Download) { model.download(selected) }
        ContextMenuItem(menu, localized(DMR.strings.delete_downloads), Icons.Outlined.Delete) { model.deleteDownloads(selected) }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    displayMode: Long,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onMarkRead: () -> Unit,
    onBookmark: () -> Unit,
    onMarkPreviousRead: () -> Unit,
    showDownload: Boolean,
    downloaded: Boolean,
    download: Download?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    selectionMenu: (@Composable ColumnScope.(ContextMenuState) -> Unit)?,
) {
    val menu = rememberContextMenuState()
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .clickable { if (selecting) onToggleSelect() else onOpen() }
                .onRightClickAt(menu::open)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).alpha(if (chapter.read) 0.5f else 1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chapter.bookmark) {
                        Icon(
                            Icons.Default.Bookmark,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp).padding(end = 2.dp),
                        )
                    }
                    // ZOUKAN -->
                    // The chapter settings' Display tab, as Android's chapter list
                    Text(
                        if (displayMode == Manga.CHAPTER_DISPLAY_NUMBER) {
                            localized(MR.strings.display_mode_chapter, formatChapterNumber(chapter.chapterNumber))
                        } else {
                            chapter.name
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // ZOUKAN <--
                }
                val details = listOfNotNull(
                    chapter.dateUpload.takeIf { SyncChapters.hasSourceDate(chapter) }?.let(zoukan.desktop.ui.common.Dates::relative),
                    chapter.scanlator?.takeIf { it.isNotBlank() },
                    if (!chapter.read && chapter.lastPageRead > 0) localized(DMR.strings.page, chapter.lastPageRead + 1) else null,
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showDownload) {
                DownloadIndicator(
                    downloaded = downloaded,
                    download = download,
                    onDownload = onDownload,
                    onCancel = onCancelDownload,
                    onDelete = onDeleteDownload,
                )
            }
        }
        ContextMenu(menu) {
            if (selectionMenu != null) {
                selectionMenu(menu)
                return@ContextMenu
            }
            ContextMenuItem(menu, localized(MR.strings.label_read_chapters), Icons.AutoMirrored.Outlined.MenuBook, onClick = onOpen)
            if (selected) {
                ContextMenuItem(menu, localized(DMR.strings.deselect), Icons.Outlined.Deselect, onClick = onToggleSelect)
            } else {
                ContextMenuItem(menu, localized(DMR.strings.select), Icons.Outlined.CheckCircle, onClick = onToggleSelect)
            }
            ContextMenuDivider()
            if (chapter.read) {
                ContextMenuItem(menu, localized(MR.strings.action_mark_as_unread), Icons.Outlined.RemoveDone, onClick = onMarkRead)
            } else {
                ContextMenuItem(menu, localized(MR.strings.action_mark_as_read), Icons.Outlined.DoneAll, onClick = onMarkRead)
            }
            ContextMenuItem(menu, localized(MR.strings.action_mark_previous_as_read), Icons.Outlined.DoneAll, onClick = onMarkPreviousRead)
            if (chapter.bookmark) {
                ContextMenuItem(menu, localized(DMR.strings.remove_bookmark), Icons.Outlined.BookmarkRemove, onClick = onBookmark)
            } else {
                ContextMenuItem(menu, localized(DMR.strings.bookmark), Icons.Outlined.BookmarkAdd, onClick = onBookmark)
            }
            if (showDownload) {
                when {
                    download != null -> ContextMenuItem(menu, localized(DMR.strings.cancel_download), Icons.Outlined.Close, onClick = onCancelDownload)
                    downloaded -> ContextMenuItem(menu, localized(DMR.strings.delete_download), Icons.Outlined.Delete, onClick = onDeleteDownload)
                    else -> ContextMenuItem(menu, localized(MR.strings.action_download), Icons.Outlined.Download, onClick = onDownload)
                }
            }
        }
    }
}

@Composable
private fun DownloadMenu(model: MangaScreenModel) {
    var open by remember { mutableStateOf(false) }
    // ZOUKAN -->
    var custom by remember { mutableStateOf(false) }
    if (custom) CustomDownloadDialog(onConfirm = { model.downloadUnread(it) }, onDismiss = { custom = false })
    // ZOUKAN <--
    Box {
        TooltipIconButton(Icons.Outlined.Download, localized(MR.strings.action_download), onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(1, 5, 10, 25).forEach { count ->
                DropdownMenuItem(text = { Text(if (count == 1) localized(MR.strings.action_next_chapter) else localized(DMR.strings.next_chapters, count)) }, onClick = {
                    open = false
                    model.downloadUnread(count)
                })
            }
            // ZOUKAN -->
            DropdownMenuItem(text = { Text(localized(DMR.strings.custom)) }, onClick = {
                open = false
                custom = true
            })
            // ZOUKAN <--
            DropdownMenuItem(text = { Text(localized(MR.strings.action_display_unread_badge)) }, onClick = {
                open = false
                model.downloadUnread(null)
            })
            // ZOUKAN -->
            DropdownMenuItem(text = { Text(localized(DMR.strings.bookmarked_chapters)) }, onClick = {
                open = false
                model.downloadBookmarked()
            })
            // ZOUKAN <--
        }
    }
}

@Composable
internal fun CategoryDialog(
    categories: List<tachiyomi.domain.category.model.Category>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.add_to_categories)) },
        text = {
            Column {
                categories.forEach { category ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = category.id in chosen,
                            onValueChange = { on -> chosen = if (on) chosen + category.id else chosen - category.id },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = category.id in chosen, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(category.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen.toList()) }) { Text(localized(MR.strings.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

// ZOUKAN -->
/** Android's ChangeCategory dialog: all categories, the entry's own checked, OK applies them. */
@Composable
internal fun ChangeCategoryDialog(
    categories: List<tachiyomi.domain.category.model.Category>,
    chosen: Set<Long>,
    onConfirm: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.action_move_category)) },
        text = {
            if (categories.isEmpty()) {
                Text(localized(DMR.strings.there_are_no_categories_yet_add))
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                    categories.forEach { category ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = category.id in checked,
                                onValueChange = { on -> checked = if (on) checked + category.id else checked - category.id },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = category.id in checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(checked) }) { Text(localized(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
// ZOUKAN <--

private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** In the date format of Settings > Appearance, as on Android */
fun formatDate(epochMillis: Long): String = zoukan.desktop.ui.common.Dates.format(epochMillis)

fun statusLabel(status: Long): String? = when (status.toInt()) {
    SManga.ONGOING -> localized(MR.strings.ongoing)
    SManga.COMPLETED -> localized(MR.strings.completed)
    SManga.LICENSED -> localized(MR.strings.licensed)
    SManga.PUBLISHING_FINISHED -> localized(MR.strings.publishing_finished)
    SManga.CANCELLED -> localized(MR.strings.cancelled)
    SManga.ON_HIATUS -> localized(MR.strings.on_hiatus)
    else -> null
}
