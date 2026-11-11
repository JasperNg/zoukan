package zoukan.desktop.ui.reader

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zoom and pan of one page. [pan] is the top left corner of the page relative to the viewport,
 * in pixels; it is kept inside the bounds by [clamp].
 */
class PageZoom {
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    var contentSize by mutableStateOf(IntSize.Zero)
    var viewport by mutableStateOf(IntSize.Zero)

    val overflowsX get() = contentSize.width > viewport.width + 1
    val overflowsY get() = contentSize.height > viewport.height + 1

    fun clamp(offset: Offset): Offset = clamp(offset, viewport, contentSize)

    /** Pans by [delta] and returns the part that hit an edge. */
    fun panBy(delta: Offset): Offset {
        val target = clamp(pan) + delta
        val clamped = clamp(target)
        pan = clamped
        return target - clamped
    }

    fun zoomTo(value: Float, anchor: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        val new = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = new / zoom
        // Keep the point under the anchor where it is
        val current = clamp(pan)
        pan = anchor - (anchor - current) * factor
        zoom = new
    }

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    companion object {
        /** Centers content smaller than the viewport; keeps larger content covering it. Offset.Zero is the top left. */
        fun clamp(offset: Offset, viewport: IntSize, content: IntSize): Offset {
            val x = if (content.width > viewport.width + 1) {
                offset.x.coerceIn((viewport.width - content.width).toFloat(), 0f)
            } else {
                (viewport.width - content.width) / 2f
            }
            val y = if (content.height > viewport.height + 1) {
                offset.y.coerceIn((viewport.height - content.height).toFloat(), 0f)
            } else {
                (viewport.height - content.height) / 2f
            }
            return Offset(x, y)
        }

        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f
    }
}

@Composable
fun PagedViewer(
    model: ReaderScreenModel,
    state: ReaderScreenModel.State,
    actions: ReaderActions,
    fitMode: Int,
    background: Color,
    foreground: Color,
) {
    val pages = state.pages
    // ZOUKAN -->
    // Double pages, as Android's page layout: the first page alone (the cover), then pairs
    val layout by model.preferences.pageLayout().changes().collectAsState(model.preferences.pageLayout().get())
    val invertDouble by model.preferences.invertDoublePages().changes().collectAsState(model.preferences.invertDoublePages().get())
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val double = layout == 1 || (layout == 2 && windowSize.width > windowSize.height)
    val spreads: List<List<Int>> = remember(pages.size, double) {
        if (!double || pages.size < 3) {
            pages.indices.map { listOf(it) }
        } else {
            listOf(listOf(0)) + (1 until pages.size step 2).map { i -> listOfNotNull(i, (i + 1).takeIf { it < pages.size }) }
        }
    }
    fun spreadOf(page: Int) = spreads.indexOfFirst { page in it }.coerceAtLeast(0)
    // Pager index 0 and spreads.size + 1 are the chapter transitions
    val pagerState = rememberPagerState(initialPage = spreadOf(state.startPage) + 1) { spreads.size + 2 }
    // ZOUKAN <--
    val scope = rememberCoroutineScope()
    val zooms = remember { mutableMapOf<Int, PageZoom>() }
    val rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT
    val vertical = state.readingMode == ReadingModes.VERTICAL
    var lastTurn by remember { mutableStateOf(0L) }

    fun current() = zooms.getOrPut(pagerState.currentPage) { PageZoom() }

    fun turn(to: Int) {
        lastTurn = System.currentTimeMillis()
        when {
            to < 0 -> model.previousChapter(atEnd = true)
            to > spreads.size + 1 -> model.nextChapter()
            else -> scope.launch { pagerState.animateScrollToPage(to) }
        }
    }

    actions.next = { turn(pagerState.currentPage + 1) }
    actions.previous = { turn(pagerState.currentPage - 1) }
    actions.down = {
        val zoom = current()
        if (zoom.overflowsY && zoom.panBy(Offset(0f, -zoom.viewport.height * 0.8f)).y == 0f) Unit else actions.next()
    }
    actions.up = {
        val zoom = current()
        if (zoom.overflowsY && zoom.panBy(Offset(0f, zoom.viewport.height * 0.8f)).y == 0f) Unit else actions.previous()
    }
    actions.first = { scope.launch { pagerState.scrollToPage(1) } }
    actions.last = { scope.launch { pagerState.scrollToPage(spreads.size) } }
    actions.goTo = { index -> scope.launch { pagerState.scrollToPage(spreadOf(index) + 1) } }
    actions.zoomIn = { current().let { it.zoomTo(it.zoom * 1.25f) } }
    actions.zoomOut = { current().let { it.zoomTo(it.zoom / 1.25f) } }
    actions.zoomReset = { current().reset() }

    LaunchedEffect(pagerState, spreads) {
        // When the layout changes (a setting, or the window turning wide), first move to the spread
        // that holds the current page, so the old pager index is never read through the new spreads
        val target = spreadOf(state.currentPage) + 1
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
            // Leaving a page resets its zoom, as on Android
            zooms.keys.filter { it != index }.forEach { zooms.remove(it) }
            if (index in 1..spreads.size) spreads[index - 1].let { model.onPageChanged(it.last(), first = it.first()) }
        }
    }

    // ZOUKAN -->
    // A right-click or long-press opens the shown page's actions; a double page offers both pages
    var secondaryAt by remember { mutableStateOf(0L) }
    // The tap detector lives across compositions, so it reads the spreads and order of the latest one
    val latestSpreads by rememberUpdatedState(spreads)
    val latestLeftFirst by rememberUpdatedState(!rtl != invertDouble)
    fun openPageActions() {
        val spread = latestSpreads.getOrNull(pagerState.currentPage - 1) ?: return
        model.openPageActions(spread.first(), spread.getOrNull(1), leftFirst = latestLeftFirst)
    }
    // ZOUKAN <--

    val pageContent: @Composable (Int) -> Unit = { index ->
        when (index) {
            0 -> ChapterTransition(state.chapter, state.previousChapter, next = false, color = foreground, onGo = { model.previousChapter(atEnd = true) })
            spreads.size + 1 -> ChapterTransition(state.chapter, state.nextChapter, next = true, color = foreground, onGo = { model.nextChapter() })
            else -> {
                val spread = spreads[index - 1]
                if (spread.size == 2) {
                    // The first page of a pair sits on the side the reading starts from
                    val leftFirst = !rtl != invertDouble
                    val (left, right) = if (leftFirst) spread[0] to spread[1] else spread[1] to spread[0]
                    DoublePage(
                        left = pages[left],
                        right = pages[right],
                        zoom = zooms.getOrPut(index) { PageZoom() },
                        foreground = foreground,
                        onRetry = { model.loadPage(it, retry = true) },
                    )
                } else {
                    ZoomablePage(
                        page = pages[spread[0]],
                        zoom = zooms.getOrPut(index) { PageZoom() },
                        fitMode = fitMode,
                        foreground = foreground,
                        onRetry = { model.loadPage(spread[0], retry = true) },
                    )
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta
                val zoom = current()
                val modifiers = event.keyboardModifiers
                if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                    zoom.zoomTo(zoom.zoom * if (delta.y < 0) 1.15f else 1 / 1.15f, change.position)
                    change.consume()
                    return@onPointerEvent
                }
                // A tall or zoomed page scrolls first; the wheel turns the page at its edge
                if (zoom.overflowsY || zoom.overflowsX) {
                    val leftover = zoom.panBy(Offset(-delta.x * 80f, -delta.y * 80f))
                    if (leftover == Offset.Zero) {
                        lastTurn = System.currentTimeMillis()
                        change.consume()
                        return@onPointerEvent
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastTurn < 300 || abs(delta.y) < 0.1f) return@onPointerEvent
                if (delta.y > 0) actions.next() else actions.previous()
                change.consume()
            }
            // ZOUKAN -->
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                if (event.buttons.isSecondaryPressed) {
                    secondaryAt = System.currentTimeMillis()
                    event.changes.forEach { it.consume() }
                    openPageActions()
                }
            }
            // ZOUKAN <--
            .pointerInputTaps(
                onTap = { position, size ->
                    // ZOUKAN -->
                    // The tap that ends a right-click is not a page turn
                    if (System.currentTimeMillis() - secondaryAt < 1000) return@pointerInputTaps
                    // ZOUKAN <--
                    val third = size.width / 3f
                    when {
                        position.x < third -> if (rtl) actions.next() else actions.previous()
                        position.x > size.width - third -> if (rtl) actions.previous() else actions.next()
                        else -> model.toggleMenu()
                    }
                },
                onDoubleTap = { position ->
                    val zoom = current()
                    if (zoom.zoom > 1.01f) zoom.reset() else zoom.zoomTo(2f, position)
                },
                // ZOUKAN -->
                onLongPress = { openPageActions() },
                // ZOUKAN <--
            ),
    ) {
        if (vertical) {
            VerticalPager(pagerState, userScrollEnabled = false, beyondViewportPageCount = 1, key = { it }) { pageContent(it) }
        } else {
            HorizontalPager(
                pagerState,
                reverseLayout = rtl,
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
                key = { it },
            ) { pageContent(it) }
        }
    }
}

private fun Modifier.pointerInputTaps(
    onTap: (Offset, IntSize) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    // ZOUKAN -->
    onLongPress: (Offset) -> Unit,
    // ZOUKAN <--
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap(it, size) },
            onDoubleTap = onDoubleTap,
            // ZOUKAN -->
            onLongPress = onLongPress,
            // ZOUKAN <--
        )
    },
)

@Composable
private fun ZoomablePage(
    page: ReaderPage,
    zoom: PageZoom,
    fitMode: Int,
    foreground: Color,
    onRetry: () -> Unit,
) {
    val pageState by page.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        when (val s = pageState) {
            is ReaderPage.State.Ready -> {
                val image = s.image
                val base = when (fitMode) {
                    FitModes.FIT_WIDTH -> viewport.width / image.width.toFloat()
                    FitModes.FIT_HEIGHT -> viewport.height / image.height.toFloat()
                    FitModes.ORIGINAL -> density.density
                    else -> minOf(viewport.width / image.width.toFloat(), viewport.height / image.height.toFloat())
                }
                val width = (image.width * base * zoom.zoom).roundToInt().coerceAtLeast(1)
                val height = (image.height * base * zoom.zoom).roundToInt().coerceAtLeast(1)
                val content = IntSize(width, height)
                SideEffect {
                    zoom.viewport = viewport
                    zoom.contentSize = content
                }
                PageSlices(
                    image,
                    Modifier
                        .layout { measurable, _ ->
                            val placeable = measurable.measure(Constraints.fixed(width, height))
                            layout(viewport.width, viewport.height) {
                                // A new page (pan zero) starts at its top
                                val at = PageZoom.clamp(zoom.pan, viewport, content)
                                placeable.place(at.x.roundToInt(), at.y.roundToInt())
                            }
                        }
                        .pointerInput(zoom) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                zoom.panBy(drag)
                            }
                        },
                )
            }
            is ReaderPage.State.Error -> PageError(page.index + 1, s.message, foreground, onRetry, Modifier.fillMaxSize())
            else -> PageLoading(page.index + 1, foreground, Modifier.fillMaxSize())
        }
    }
}

/** Two pages side by side, meeting in the middle, each fit into half the screen. Zoom scales both. */
@Composable
private fun DoublePage(
    left: ReaderPage,
    right: ReaderPage,
    zoom: PageZoom,
    foreground: Color,
    onRetry: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        val content = IntSize((viewport.width * zoom.zoom).roundToInt(), (viewport.height * zoom.zoom).roundToInt())
        SideEffect {
            zoom.viewport = viewport
            zoom.contentSize = content
        }
        androidx.compose.foundation.layout.Row(
            Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed(content.width, content.height))
                    layout(viewport.width, viewport.height) {
                        val at = PageZoom.clamp(zoom.pan, viewport, content)
                        placeable.place(at.x.roundToInt(), at.y.roundToInt())
                    }
                }
                .pointerInput(zoom) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        zoom.panBy(drag)
                    }
                },
        ) {
            HalfPage(left, androidx.compose.ui.Alignment.CenterEnd, foreground, onRetry, Modifier.weight(1f))
            HalfPage(right, androidx.compose.ui.Alignment.CenterStart, foreground, onRetry, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HalfPage(
    page: ReaderPage,
    alignment: androidx.compose.ui.Alignment,
    foreground: Color,
    onRetry: (Int) -> Unit,
    modifier: Modifier,
) {
    val pageState by page.state.collectAsState()
    Box(modifier.fillMaxHeight(), contentAlignment = alignment) {
        when (val s = pageState) {
            is ReaderPage.State.Ready -> {
                val ratio = s.image.aspectRatio
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = alignment) {
                    val fitWidth = maxHeight * ratio <= maxWidth
                    PageSlices(
                        s.image,
                        if (fitWidth) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio),
                    )
                }
            }
            is ReaderPage.State.Error -> PageError(page.index + 1, s.message, foreground, { onRetry(page.index) }, Modifier.fillMaxSize())
            else -> PageLoading(page.index + 1, foreground, Modifier.fillMaxSize())
        }
    }
}
