package zoukan.desktop.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.Download
import zoukan.desktop.track.TrackingModel
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.onRightClick
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
        // ZOUKAN <--

        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = manga.title, subtitle = state.source?.toString() ?: "Source not installed") {
                TooltipIconButton(Icons.Default.Refresh, "Refresh", onClick = { model.refresh() }, enabled = !state.refreshing)
                if (state.source is eu.kanade.tachiyomi.source.online.HttpSource) {
                    TooltipIconButton(Icons.Default.Public, "Open in the built-in browser", onClick = model::openInWebView)
                    TooltipIconButton(Icons.Outlined.OpenInBrowser, "Open in your browser", onClick = model::openInBrowser)
                }
                // ZOUKAN -->
                MoreMenu(manga, onEditMerged = { mergedDialog = true }, onEdit = { editDialog = true })
                // ZOUKAN <--
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxSize()) {
                // Details
                Column(
                    Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverImage(
                        Cover.of(manga),
                        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                    )
                    Text(manga.title, style = MaterialTheme.typography.headlineSmall)
                    val people = listOfNotNull(manga.author, manga.artist?.takeIf { it != manga.author })
                        .filter { it.isNotBlank() }
                    if (people.isNotEmpty()) {
                        Text(people.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        listOfNotNull(statusLabel(manga.status), state.source?.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val next = state.nextChapter
                        Button(
                            onClick = { next?.let { navigator.push(ReaderScreen(manga.id, it.id)) } },
                            enabled = next != null,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.hasStarted) "Continue" else "Start reading")
                        }
                        FilledTonalButton(onClick = model::toggleFavorite) {
                            Icon(if (manga.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (manga.favorite) "In library" else "Add to library")
                        }
                    }
                    // ZOUKAN -->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.setTrackingVisible(true) }) {
                            Icon(if (state.trackCount > 0) Icons.Default.Done else Icons.Default.Sync, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.trackCount > 0) "Tracking (${state.trackCount})" else "Tracking")
                        }
                    }
                    // ZOUKAN <--
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    // ZOUKAN -->
                    val meta = state.meta
                    val search: (String) -> Unit = { query -> navigator.push(SourceScreen(manga.source, query = query)) }
                    if (meta != null && hasMetadataDescription(state.source)) {
                        MetadataDescription(meta, openMetadataViewer = { navigator.push(MetadataViewScreen(manga.id, manga.source)) }, search = search)
                    }
                    // ZOUKAN <--
                    manga.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
                    // ZOUKAN -->
                    manga.notes.takeIf { it.isNotBlank() }?.let { notes ->
                        Text("Notes", style = MaterialTheme.typography.titleSmall)
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    // ZOUKAN <--
                    // ZOUKAN -->
                    // Metadata sources show their tags by namespace, each searching the source
                    if (meta != null && meta.tags.isNotEmpty()) {
                        NamespaceTags(meta, manga.source, onClick = search)
                    } else {
                        // ZOUKAN <--
                        manga.genre?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { genres ->
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                genres.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                            }
                        }
                        // ZOUKAN -->
                    }
                    // ZOUKAN <--
                }
                VerticalDivider()
                // Chapters
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ChapterToolbar(state, model)
                    HorizontalDivider()
                    if (state.chapters.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.refreshing) "Loading chapters…" else "No chapters",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.sortedChapters, key = { it.id }) { chapter ->
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
private fun MoreMenu(manga: tachiyomi.domain.manga.model.Manga, onEditMerged: () -> Unit, onEdit: () -> Unit) {
    val navigator = LocalNavigator.currentOrThrow
    var open by remember { mutableStateOf(false) }
    val merged = manga.source == exh.source.MERGED_SOURCE_ID
    Box {
        TooltipIconButton(Icons.Default.MoreVert, "More", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit details, notes and cover") }, onClick = {
                open = false
                onEdit()
            })
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
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.selected.isEmpty()) {
            Text(
                "${state.chapters.size} chapters",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TooltipIconButton(Icons.Default.SwapVert, if (state.descending) "Oldest first" else "Newest first", onClick = model::toggleSort)
            if (!state.isLocal) DownloadMenu(model)
            TooltipIconButton(Icons.Default.SelectAll, "Select all", onClick = model::selectAll)
        } else {
            val selected = state.chapters.filter { it.id in state.selected }
            TooltipIconButton(Icons.Default.Close, "Clear selection", onClick = model::clearSelection)
            Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TooltipIconButton(Icons.Default.DoneAll, "Mark as read", onClick = { model.markRead(selected, true) })
            TooltipIconButton(Icons.Default.RemoveDone, "Mark as unread", onClick = { model.markRead(selected, false) })
            TooltipIconButton(Icons.Default.Bookmark, "Bookmark", onClick = { model.setBookmark(selected, true) })
            TooltipIconButton(Icons.Default.BookmarkBorder, "Remove bookmark", onClick = { model.setBookmark(selected, false) })
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
    Box {
        TooltipIconButton(Icons.Outlined.Download, "Download", onClick = { open = true })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(1, 5, 10, 25).forEach { count ->
                DropdownMenuItem(text = { Text(if (count == 1) "Next chapter" else "Next $count chapters") }, onClick = {
                    open = false
                    model.downloadUnread(count)
                })
            }
            DropdownMenuItem(text = { Text("Unread chapters") }, onClick = {
                open = false
                model.downloadUnread(null)
            })
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CategoryDialog(
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
