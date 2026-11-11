package zoukan.desktop.ui.common

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/*
 * Lists and grids with a scrollbar, the desktop way to see where you are in a list and to drag
 * through it. Screens import these in place of the foundation ones, which have none.
 */

/** The scrollbar's look: a thin rounded thumb in the theme's text color that darkens under the mouse. */
@Composable
fun scrollbarStyle(): ScrollbarStyle {
    val color = MaterialTheme.colorScheme.onSurface
    return LocalScrollbarStyle.current.copy(
        minimalHeight = 40.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 150,
        unhoverColor = color.copy(alpha = 0.3f),
        hoverColor = color.copy(alpha = 0.6f),
    )
}

/** A foundation LazyColumn with a scrollbar on its right edge, shown when the list is longer than its space. */
@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    // The list takes the box's minimum size, so it fills or wraps exactly as it did without the box
    Box(modifier, propagateMinConstraints = true) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        Box(Modifier.matchParentSize()) {
            VerticalScrollbar(
                rememberScrollbarAdapter(state),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                style = scrollbarStyle(),
            )
        }
    }
}

/** A foundation LazyVerticalGrid with a scrollbar on its right edge. */
@Composable
fun LazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = columns,
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
        Box(Modifier.matchParentSize()) {
            VerticalScrollbar(
                rememberScrollbarAdapter(state),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                style = scrollbarStyle(),
            )
        }
    }
}

/**
 * Scrolls a column and draws a scroll indicator on its right edge while the content is taller than
 * its space. For dialogs and side panes; the indicator shows the position and is not dragged.
 */
fun Modifier.verticalScrollWithBar(state: ScrollState? = null): Modifier = composed {
    val scroll = state ?: rememberScrollState()
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    drawWithContent {
        drawContent()
        val thumb = scrollThumb(scroll.value, scroll.maxValue, size.height, minThumb = 40.dp.toPx(), inset = 4.dp.toPx())
        if (thumb != null) {
            val width = 4.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width - width - 2.dp.toPx(), thumb.first),
                size = Size(width, thumb.second),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }.verticalScroll(scroll)
}

/**
 * Where the scroll indicator's thumb goes: its top and height in a viewport [viewport] tall,
 * scrolled [value] out of [max]. Null when everything fits.
 */
internal fun scrollThumb(value: Int, max: Int, viewport: Float, minThumb: Float, inset: Float): Pair<Float, Float>? {
    if (max <= 0 || max == Int.MAX_VALUE || viewport <= 0f) return null
    val track = viewport - 2 * inset
    if (track <= 0f) return null
    val height = (track * viewport / (viewport + max)).coerceIn(minOf(minThumb, track), track)
    val top = inset + (track - height) * (value.toFloat() / max).coerceIn(0f, 1f)
    return top to height
}
