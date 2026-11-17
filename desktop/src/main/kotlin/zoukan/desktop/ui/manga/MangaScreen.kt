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
import androidx.compose.runtime.LaunchedEffect
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
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.Download
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.common.ChapterSwipeBox
import zoukan.desktop.ui.common.ChapterSwipeState
import zoukan.desktop.ui.common.ConfirmDestructiveDialog
import zoukan.desktop.ui.common.ContextMenu
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.collectAsState
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
        val manga = state.manga ?: run {
            // An entry that is gone shows why, instead of loading for ever
            val error = state.error
            if (error == null) {
                LoadingScreen()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error) }
            }
            return
        }
        // ZOUKAN -->
        // The one dialog the user opened (edit, cover, clear, a question before deleting, ...)
        var dialog by remember { mutableStateOf<MangaDialog?>(null) }
        MangaDialogHost(dialog, manga, model, isLocal = state.isLocal, onShow = { dialog = it })
        val isMerged = manga.source == exh.source.MERGED_SOURCE_ID
        val search = remember(navigator, state.source) {
            EntryInfoSearch(
                library = { EntrySearch.search(navigator, it, global = false, library = true) },
                global = { EntrySearch.search(navigator, it, global = true) },
                tag = { EntrySearch.searchGenre(navigator, it, state.source) },
            )
        }
        val chapterCallbacks = remember(model, navigator) {
            ChapterListCallbacks(
                onOpen = { navigator.push(ReaderScreen(mangaId, it.id)) },
                onToggleSelect = model::toggleSelection,
                onMarkRead = model::markRead,
                onBookmark = model::setBookmark,
                onMarkPreviousRead = model::markPreviousRead,
                onDownload = model::download,
                onCancelDownload = model::cancelDownload,
                onDeleteDownload = { model.deleteDownloads(listOf(it)) },
                // Deleting the downloads of several chapters at once asks first
                onDeleteSelected = { dialog = MangaDialog.ConfirmDeleteDownloads(it) },
                onClearSelection = model::clearSelection,
            )
        }
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
                            onEditMerged = { dialog = MangaDialog.MergedEntries },
                            onEdit = { dialog = MangaDialog.Edit },
                            onCover = { dialog = MangaDialog.Cover },
                            onFetchInterval = { dialog = MangaDialog.FetchInterval },
                            onCopyLink = { EntryActions.copyLink(manga, state.source) }.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
                            onOpenFolder = {
                                if (!isMerged) {
                                    model.openMangaFolder(state.source, manga)
                                } else {
                                    dialog = MangaDialog.PickMergedEntry(
                                        MergedAction(localized(KMR.strings.action_open_folder)) { entry, source -> model.openMangaFolder(source, entry) },
                                    )
                                }
                            }.takeIf { state.source != null && state.source !is tachiyomi.domain.source.model.StubSource },
                            onClearManga = { dialog = MangaDialog.Clear },
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
                                onCoverClick = { dialog = MangaDialog.Cover },
                                search = search,
                                onCopyLink = { EntryActions.copyLink(manga, state.source) }.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
                                onSourceClick = {
                                    val source = state.source
                                    when {
                                        source == null || source is tachiyomi.domain.source.model.StubSource -> Unit
                                        !isMerged -> EntrySearch.browseSource(navigator, source)
                                        else -> dialog = MangaDialog.PickMergedEntry(
                                            MergedAction(localized(MR.strings.browse)) { _, entrySource ->
                                                EntrySearch.browseSource(navigator, entrySource)
                                            },
                                        )
                                    }
                                },
                            )
                            // ZOUKAN <--
                            VerticalDivider()
                            // Chapters
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.fillMaxSize()) {
                                    ChapterToolbar(state, model, onDeleteSelected = { dialog = MangaDialog.ConfirmDeleteDownloads(it) })
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
                                        ChapterList(
                                            chapters = state.sortedChapters,
                                            downloaded = state.downloaded,
                                            downloads = state.queued,
                                            selected = state.selected,
                                            displayMode = manga.displayMode,
                                            swipeStart = state.swipeToStart,
                                            swipeEnd = state.swipeToEnd,
                                            callbacks = chapterCallbacks,
                                            isLocal = state.isLocal,
                                            showMissing = state.showMissingChapters,
                                            descending = manga.sortDescending(),
                                            selectable = state.chapters,
                                        )
                                        // ZOUKAN <--
                                    }
                                }
                                // ZOUKAN -->
                                // Komikku's Start / Resume button, while there is an unread chapter,
                                // draggable left/right and docking to the dragged side like on Android
                                val next = state.nextChapter
                                ResumeButton(
                                    visible = next != null && state.selected.isEmpty(),
                                    atStart = state.readButtonAtStart,
                                    started = state.hasStarted,
                                    onMove = model::moveReadButton,
                                    onOpen = { next?.let { navigator.push(ReaderScreen(manga.id, it.id)) } },
                                )
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
                    dialog = MangaDialog.MigrateDuplicate(it)
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
private fun ChapterToolbar(state: MangaScreenModel.State, model: MangaScreenModel, onDeleteSelected: (List<Chapter>) -> Unit) {
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
                if (shown == state.chapters.size) localizedPlural(DMR.plurals.chapters, state.chapters.size, state.chapters.size) else localized(DMR.strings.of_chapters, shown, state.chapters.size),
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
                TooltipIconButton(Icons.Outlined.Delete, localized(DMR.strings.delete_downloads), onClick = { onDeleteSelected(selected) })
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
                        Modifier.fillMaxWidth().checkRow(
                            checked = category.id in chosen,
                            onChange = { on -> chosen = if (on) chosen + category.id else chosen - category.id },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowCheckbox(checked = category.id in chosen, onCheckedChange = null)
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
                            Modifier.fillMaxWidth().checkRow(
                                checked = category.id in checked,
                                onChange = { on -> checked = if (on) checked + category.id else checked - category.id },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RowCheckbox(checked = category.id in checked, onCheckedChange = null)
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
