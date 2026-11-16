package zoukan.desktop.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.formatChapterNumber
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.SyncChapters
import zoukan.desktop.download.Download
import zoukan.desktop.ui.common.ChapterSwipeBox
import zoukan.desktop.ui.common.ChapterSwipeState
import zoukan.desktop.ui.common.ContextMenu
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.chapterStateDescription
import zoukan.desktop.ui.common.onRightClickAt
import zoukan.desktop.ui.common.rememberContextMenuState
import zoukan.desktop.ui.downloads.DownloadIndicator
import zoukan.i18n.desktop.DMR

// ZOUKAN -->
/** What the chapter list does with a click, a swipe or a menu item: plain lambdas, no screen model. */
internal class ChapterListCallbacks(
    val onOpen: (Chapter) -> Unit,
    val onToggleSelect: (Chapter) -> Unit,
    val onMarkRead: (List<Chapter>, Boolean) -> Unit,
    val onBookmark: (List<Chapter>, Boolean) -> Unit,
    val onMarkPreviousRead: (Chapter) -> Unit,
    val onDownload: (List<Chapter>) -> Unit,
    val onCancelDownload: (Chapter) -> Unit,
    /** One chapter's download, from its own menu, indicator or swipe: no question asked */
    val onDeleteDownload: (Chapter) -> Unit,
    /** The downloads of a selection of chapters: the caller asks before deleting */
    val onDeleteSelected: (List<Chapter>) -> Unit,
    val onClearSelection: () -> Unit,
)

/**
 * The entry screen's chapter list: a row per chapter with its swipe actions, the missing-chapter
 * markers between rows and a right-click menu. It renders from plain values, so it needs neither
 * the screen model nor Injekt.
 *
 * [chapters] are the rows shown, in order. [downloaded] holds the ids of the downloaded chapters,
 * [downloads] the queued downloads by chapter id. [selectable] is every chapter a selection may
 * hold, which a selection's menu acts on, also when a filter hides some of them.
 */
@Composable
internal fun ChapterList(
    chapters: List<Chapter>,
    downloaded: Set<Long>,
    downloads: Map<Long, Download>,
    selected: Set<Long>,
    displayMode: Long,
    swipeStart: ChapterSwipeAction,
    swipeEnd: ChapterSwipeAction,
    callbacks: ChapterListCallbacks,
    modifier: Modifier = Modifier,
    isLocal: Boolean = false,
    showMissing: Boolean = false,
    descending: Boolean = false,
    selectable: List<Chapter> = chapters,
) {
    val missing = remember(chapters, descending, showMissing) {
        if (showMissing) MissingChapters.of(chapters, descending) else MissingChapters.NONE
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(chapters, key = { it.id }) { chapter ->
            missing.before[chapter.id]?.let { MissingChaptersRow(it) }
            // Android's chapter swipe actions (Settings > Library > Chapter swipe)
            ChapterSwipeBox(
                toStart = swipeStart,
                toEnd = swipeEnd,
                state = ChapterSwipeState(
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    downloaded = if (isLocal) null else chapter.id in downloaded || chapter.id in downloads,
                ),
                onAction = { action ->
                    when (action) {
                        ChapterSwipeAction.ToggleRead -> callbacks.onMarkRead(listOf(chapter), !chapter.read)
                        ChapterSwipeAction.ToggleBookmark -> callbacks.onBookmark(listOf(chapter), !chapter.bookmark)
                        ChapterSwipeAction.Download -> when {
                            chapter.id in downloads -> callbacks.onCancelDownload(chapter)
                            chapter.id in downloaded -> callbacks.onDeleteDownload(chapter)
                            else -> callbacks.onDownload(listOf(chapter))
                        }
                        ChapterSwipeAction.Disabled -> Unit
                    }
                },
            ) {
                ChapterRow(
                    chapter = chapter,
                    displayMode = displayMode,
                    selected = chapter.id in selected,
                    selecting = selected.isNotEmpty(),
                    onOpen = { callbacks.onOpen(chapter) },
                    onToggleSelect = { callbacks.onToggleSelect(chapter) },
                    onMarkRead = { callbacks.onMarkRead(listOf(chapter), !chapter.read) },
                    onBookmark = { callbacks.onBookmark(listOf(chapter), !chapter.bookmark) },
                    onMarkPreviousRead = { callbacks.onMarkPreviousRead(chapter) },
                    showDownload = !isLocal,
                    downloaded = chapter.id in downloaded,
                    download = downloads[chapter.id],
                    onDownload = { callbacks.onDownload(listOf(chapter)) },
                    onCancelDownload = { callbacks.onCancelDownload(chapter) },
                    onDeleteDownload = { callbacks.onDeleteDownload(chapter) },
                    // A right click on a selected chapter acts on the whole selection
                    selectionMenu = if (chapter.id in selected && selected.size > 1) {
                        { menu -> ChapterSelectionMenu(menu, selectable.filter { it.id in selected }, isLocal, callbacks) }
                    } else {
                        null
                    },
                )
            }
        }
        if (missing.end > 0) item(key = "missing-end") { MissingChaptersRow(missing.end) }
    }
}
// ZOUKAN <--

/** A selected chapter's right-click menu: the selection toolbar's actions. */
@Composable
private fun ColumnScope.ChapterSelectionMenu(
    menu: ContextMenuState,
    selected: List<Chapter>,
    isLocal: Boolean,
    callbacks: ChapterListCallbacks,
) {
    ContextMenuItem(menu, localized(DMR.strings.clear_selection), Icons.Outlined.Close, onClick = callbacks.onClearSelection)
    ContextMenuDivider()
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_read), Icons.Outlined.DoneAll) { callbacks.onMarkRead(selected, true) }
    ContextMenuItem(menu, localized(MR.strings.action_mark_as_unread), Icons.Outlined.RemoveDone) { callbacks.onMarkRead(selected, false) }
    ContextMenuItem(menu, localized(DMR.strings.bookmark), Icons.Outlined.BookmarkAdd) { callbacks.onBookmark(selected, true) }
    ContextMenuItem(menu, localized(DMR.strings.remove_bookmark), Icons.Outlined.BookmarkRemove) { callbacks.onBookmark(selected, false) }
    if (!isLocal) {
        ContextMenuItem(menu, localized(MR.strings.action_download), Icons.Outlined.Download) { callbacks.onDownload(selected) }
        ContextMenuItem(menu, localized(DMR.strings.delete_downloads), Icons.Outlined.Delete) { callbacks.onDeleteSelected(selected) }
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
                .semantics { chapterStateDescription(chapter.read, chapter.bookmark)?.let { stateDescription = it } }
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
                            localized(DMR.strings.chapter_state_bookmarked),
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
