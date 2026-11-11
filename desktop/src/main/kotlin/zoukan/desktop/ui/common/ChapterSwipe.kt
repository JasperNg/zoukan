package zoukan.desktop.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a chapter row's swipe acts on: its read, bookmark and download state. */
data class ChapterSwipeState(
    val read: Boolean,
    val bookmark: Boolean,
    /** Downloaded or queued; null when the chapter cannot be downloaded (local source) */
    val downloaded: Boolean?,
)

/** Share of the row's width a swipe has to travel to act, as Android's SwipeToDismiss threshold. */
private const val THRESHOLD = 0.25f

/**
 * A chapter row that runs Android's chapter swipe actions (Settings > Library > Chapter swipe):
 * dragged towards the start runs [toStart], towards the end runs [toEnd]. The row slides with the
 * pointer, shows the action under it, and springs back once let go. Mouse drags and touch screens
 * both swipe; clicks still reach [content].
 */
@Composable
fun ChapterSwipeBox(
    toStart: ChapterSwipeAction,
    toEnd: ChapterSwipeAction,
    state: ChapterSwipeState,
    onAction: (ChapterSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val start = toStart.takeIf { it.isAvailable(state) }
    val end = toEnd.takeIf { it.isAvailable(state) }
    if (start == null && end == null) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val onActionState by rememberUpdatedState(onAction)
    val drag = rememberDraggableState { delta ->
        val next = offset.value + delta
        // Only the directions with an action move
        val allowed = when {
            next < 0f -> start != null
            next > 0f -> end != null
            else -> true
        }
        if (allowed) scope.launch { offset.snapTo(next.coerceIn(-width.toFloat(), width.toFloat())) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStopped = {
                    val value = offset.value
                    if (width > 0 && abs(value) >= width * THRESHOLD) {
                        (if (value < 0) start else end)?.let { onActionState(it) }
                    }
                    offset.animateTo(0f)
                },
            ),
    ) {
        val value = offset.value
        val shown = when {
            value < 0f -> start
            value > 0f -> end
            else -> null
        }
        if (shown != null) {
            val reached = width > 0 && abs(value) >= width * THRESHOLD
            Box(
                Modifier.matchParentSize()
                    .background(if (reached) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (value < 0f) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(shown.icon(state), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Box(Modifier.offset { IntOffset(value.roundToInt(), 0) }.background(MaterialTheme.colorScheme.surface)) {
            content()
        }
    }
}

private fun ChapterSwipeAction.isAvailable(state: ChapterSwipeState): Boolean = when (this) {
    ChapterSwipeAction.Disabled -> false
    ChapterSwipeAction.Download -> state.downloaded != null
    else -> true
}

private fun ChapterSwipeAction.icon(state: ChapterSwipeState): ImageVector = when (this) {
    ChapterSwipeAction.ToggleRead -> if (state.read) Icons.Outlined.RemoveDone else Icons.Outlined.Done
    ChapterSwipeAction.ToggleBookmark -> if (state.bookmark) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd
    ChapterSwipeAction.Download -> if (state.downloaded == true) Icons.Outlined.Delete else Icons.Outlined.Download
    ChapterSwipeAction.Disabled -> Icons.Outlined.FileDownloadOff
}

/** The names Android's Settings > Library > Chapter swipe lists. */
val CHAPTER_SWIPE_ACTIONS: List<Pair<ChapterSwipeAction, String>> = listOf(
    ChapterSwipeAction.Disabled to localized(MR.strings.disabled),
    ChapterSwipeAction.ToggleBookmark to localized(DMR.strings.toggle_bookmark),
    ChapterSwipeAction.ToggleRead to localized(DMR.strings.toggle_read),
    ChapterSwipeAction.Download to localized(MR.strings.action_download),
)
