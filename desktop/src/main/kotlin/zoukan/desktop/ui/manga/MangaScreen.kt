package zoukan.desktop.ui.manga

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.Download
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.common.ChapterSwipeBox
import zoukan.desktop.ui.common.ChapterSwipeState
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClick
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.downloads.DownloadIndicator
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.track.TrackingDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
        var migrateDuplicate by remember { mutableStateOf<tachiyomi.domain.manga.model.Manga?>(null) }
        migrateDuplicate?.let { duplicate ->
            MigrateDialog(duplicate.id, manga, onDismiss = { migrateDuplicate = null }, onMigrated = {})
        }
        // ZOUKAN <--

        CoverBasedTheme(manga) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    ScreenHeader(title = manga.title, subtitle = state.source?.toString() ?: "Source not installed") {
                        TooltipIconButton(Icons.Outlined.Refresh, "Refresh", onClick = { model.refresh() }, enabled = !state.refreshing)
                        if (state.source is eu.kanade.tachiyomi.source.online.HttpSource) {
                            TooltipIconButton(Icons.Outlined.OpenInBrowser, "Open in your browser", onClick = model::openInBrowser)
                        }
                        // ZOUKAN -->
                        MoreMenu(
                            manga,
                            onEditMerged = { mergedDialog = true },
                            onEdit = { editDialog = true },
                            onCover = { coverDialog = true },
                            onFetchInterval = { intervalDialog = true },
                            onCopyLink = { EntryActions.copyLink(manga, state.source) }.takeIf { state.source is eu.kanade.tachiyomi.source.online.HttpSource },
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
                                onSearch = { query -> navigator.push(SourceScreen(manga.source, query = query)) },
                                onOpenMetadata = { navigator.push(MetadataViewScreen(manga.id, manga.source)) },
                                modifier = Modifier.width(infoWidth).fillMaxHeight(),
                                onCoverClick = { coverDialog = true },
                            )
                            // ZOUKAN <--
                            VerticalDivider()
                            // Chapters
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.fillMaxSize()) {
                                    ChapterToolbar(state, model)
                                    HorizontalDivider()
                                    if (state.sortedChapters.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(
                                                when {
                                                    state.refreshing -> "Loading chapters…"
                                                    // ZOUKAN -->
                                                    state.chapters.isNotEmpty() -> "No chapters match the filters"
                                                    // ZOUKAN <--
                                                    else -> "No chapters"
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
                                // Komikku's Start / Resume button, while there is an unread chapter
                                val next = state.nextChapter
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = next != null && state.selected.isEmpty(),
                                    enter = fadeIn() + scaleIn(),
                                    exit = fadeOut() + scaleOut(),
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                                ) {
                                    ExtendedFloatingActionButton(onClick = { next?.let { navigator.push(ReaderScreen(manga.id, it.id)) } }) {
                                        Icon(Icons.Filled.PlayArrow, null)
                                        Spacer(Modifier.width(12.dp))
                                        Text(if (state.hasStarted) "Resume" else "Start")
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
                title = { Text("Update tracking?") },
                text = { Text("Set the trackers' progress to chapter ${chapter.toString().removeSuffix(".0")}?") },
                confirmButton = { TextButton(onClick = { model.answerTrackQuestion(true) }) { Text("Update") } },
                dismissButton = { TextButton(onClick = { model.answerTrackQuestion(false) }) { Text("Not now") } },
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
) {
    val navigator = LocalNavigator.currentOrThrow
    var open by remember { mutableStateOf(false) }
    val merged = manga.source == exh.source.MERGED_SOURCE_ID
    Box {
        TooltipIconButton(Icons.Outlined.MoreVert, "More", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit details, notes and cover") }, onClick = {
                open = false
                onEdit()
            })
            DropdownMenuItem(text = { Text("Cover") }, onClick = {
                open = false
                onCover()
            })
            if (manga.favorite) {
                DropdownMenuItem(text = { Text("Update frequency") }, onClick = {
                    open = false
                    onFetchInterval()
                })
            }
            if (onCopyLink != null) {
                DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                    open = false
                    onCopyLink()
                })
            }
            DropdownMenuItem(text = { Text("Similar titles and recommendations") }, onClick = {
                open = false
                navigator.push(zoukan.desktop.ui.recs.RecommendsScreen(manga.id))
            })
            DropdownMenuItem(text = { Text(if (merged) "Merge another entry" else "Merge with another entry") }, onClick = {
                open = false
                navigator.push(pickScreen(manga, merge = true))
            })
            if (merged) {
                DropdownMenuItem(text = { Text("Edit merged entries") }, onClick = {
                    open = false
                    onEditMerged()
                })
            } else if (manga.source != zoukan.desktop.local.LocalSource.ID) {
                DropdownMenuItem(text = { Text("Migrate to another source") }, onClick = {
                    open = false
                    navigator.push(pickScreen(manga, merge = false))
                })
            }
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
                if (shown == state.chapters.size) "${state.chapters.size} chapters" else "$shown of ${state.chapters.size} chapters",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            var settings by remember { mutableStateOf(false) }
            // Tinted while a filter hides chapters, as on Android
            val tint = if (state.filterActive) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) {
                TooltipIconButton(
                    Icons.Outlined.FilterList,
                    if (state.filterActive) "Filter and sort (filters on)" else "Filter and sort",
                    onClick = { settings = true },
                )
            }
            if (settings) ChapterSettingsDialog(state, model, onDismiss = { settings = false })
            // ZOUKAN <--
            if (!state.isLocal) DownloadMenu(model)
            TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = model::selectAll)
        } else {
            val selected = state.chapters.filter { it.id in state.selected }
            TooltipIconButton(Icons.Outlined.Close, "Clear selection", onClick = model::clearSelection)
            Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TooltipIconButton(Icons.Outlined.DoneAll, "Mark as read", onClick = { model.markRead(selected, true) })
            TooltipIconButton(Icons.Outlined.RemoveDone, "Mark as unread", onClick = { model.markRead(selected, false) })
            TooltipIconButton(Icons.Outlined.BookmarkAdd, "Bookmark", onClick = { model.setBookmark(selected, true) })
            TooltipIconButton(Icons.Outlined.BookmarkRemove, "Remove bookmark", onClick = { model.setBookmark(selected, false) })
            if (!state.isLocal) {
                TooltipIconButton(Icons.Outlined.Download, "Download", onClick = { model.download(selected) })
                TooltipIconButton(Icons.Outlined.Delete, "Delete downloads", onClick = { model.deleteDownloads(selected) })
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
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
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .clickable { if (selecting) onToggleSelect() else onOpen() }
                .onRightClick { menu = true }
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
                    Text(chapter.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val details = listOfNotNull(
                    chapter.dateUpload.takeIf { SyncChapters.hasSourceDate(chapter) }?.let(zoukan.desktop.ui.common.Dates::relative),
                    chapter.scanlator?.takeIf { it.isNotBlank() },
                    if (!chapter.read && chapter.lastPageRead > 0) "Page ${chapter.lastPageRead + 1}" else null,
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
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Read") }, onClick = {
                menu = false
                onOpen()
            })
            DropdownMenuItem(
                text = { Text(if (chapter.read) "Mark as unread" else "Mark as read") },
                onClick = {
                    menu = false
                    onMarkRead()
                },
            )
            DropdownMenuItem(text = { Text("Mark previous as read") }, onClick = {
                menu = false
                onMarkPreviousRead()
            })
            DropdownMenuItem(
                text = { Text(if (chapter.bookmark) "Remove bookmark" else "Bookmark") },
                onClick = {
                    menu = false
                    onBookmark()
                },
            )
            if (showDownload) {
                when {
                    download != null -> DropdownMenuItem(text = { Text("Cancel download") }, onClick = {
                        menu = false
                        onCancelDownload()
                    })
                    downloaded -> DropdownMenuItem(text = { Text("Delete download") }, onClick = {
                        menu = false
                        onDeleteDownload()
                    })
                    else -> DropdownMenuItem(text = { Text("Download") }, onClick = {
                        menu = false
                        onDownload()
                    })
                }
            }
            DropdownMenuItem(text = { Text("Select") }, onClick = {
                menu = false
                onToggleSelect()
            })
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
        TooltipIconButton(Icons.Outlined.Download, "Download", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(1, 5, 10, 25).forEach { count ->
                DropdownMenuItem(text = { Text(if (count == 1) "Next chapter" else "Next $count chapters") }, onClick = {
                    open = false
                    model.downloadUnread(count)
                })
            }
            // ZOUKAN -->
            DropdownMenuItem(text = { Text("Custom…") }, onClick = {
                open = false
                custom = true
            })
            // ZOUKAN <--
            DropdownMenuItem(text = { Text("Unread chapters") }, onClick = {
                open = false
                model.downloadUnread(null)
            })
            // ZOUKAN -->
            DropdownMenuItem(text = { Text("Bookmarked chapters") }, onClick = {
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
        title = { Text("Add to categories") },
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
        confirmButton = { TextButton(onClick = { onConfirm(chosen.toList()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** In the date format of Settings > Appearance, as on Android */
fun formatDate(epochMillis: Long): String = zoukan.desktop.ui.common.Dates.format(epochMillis)

fun statusLabel(status: Long): String? = when (status.toInt()) {
    SManga.ONGOING -> "Ongoing"
    SManga.COMPLETED -> "Completed"
    SManga.LICENSED -> "Licensed"
    SManga.PUBLISHING_FINISHED -> "Publishing finished"
    SManga.CANCELLED -> "Cancelled"
    SManga.ON_HIATUS -> "On hiatus"
    else -> null
}
