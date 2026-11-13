package zoukan.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.collectAsState
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

    // ZOUKAN -->
    /** Android's "Disable zoom in" (paged): the page cannot grow past its fit */
    var maxZoom = MAX_ZOOM

    /** The start position ("Zoom start position") is applied once, when the page first lays out */
    var started = false
    // ZOUKAN <--

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
        val new = value.coerceIn(MIN_ZOOM, maxZoom.coerceAtLeast(MIN_ZOOM))
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
    // ZOUKAN -->
    tapZones: TapZonesOverlayState? = null,
    autoBackground: Boolean = false,
    // ZOUKAN <--
) {
    val pages = state.pages
    // ZOUKAN -->
    // Double pages, as Android's page layout: the first page alone (the cover), then pairs
    val layout by model.preferences.pageLayout().collectAsState()
    val invertDouble by model.preferences.invertDoublePages().collectAsState()
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val double = layout == 1 || (layout == 2 && windowSize.width > windowSize.height)
    val prefs = model.preferences
    val split by prefs.dualPageSplitPaged().collectAsState()
    val splitInvert by prefs.dualPageInvertPaged().collectAsState()
    val rotateWide by prefs.dualPageRotateToFit().collectAsState()
    val rotateInvert by prefs.dualPageRotateToFitInvert().collectAsState()
    val centerMargin by prefs.centerMarginType().collectAsState()
    val navLayout by prefs.navigationModePager().collectAsState()
    val navInvert by prefs.pagerNavInverted().collectAsState()
    val smallerZones by prefs.smallerTapZone().collectAsState()
    val navigateToPan by prefs.navigateToPan().collectAsState()
    val longTap by prefs.readWithLongTap().collectAsState()
    val transitions by prefs.pageTransitionsPager().collectAsState()
    val doubleTapZoom by prefs.pagedDoubleTapZoomEnabled().collectAsState()
    val disableZoomIn by prefs.pagedDisableZoomIn().collectAsState()
    val zoomStart by prefs.zoomStart().collectAsState()
    // Wide pages stand alone in double page mode, and split or turn when those settings are on
    val wide = state.widePages
    val spreads: List<Spread> = remember(pages.size, double, wide, split, state.shiftDoublePages) {
        ReaderSpreads.build(pages.size, double, wide, split, state.shiftDoublePages)
    }
    fun spreadOf(page: Int) = spreads.indexOfFirst { page in it.pages }.coerceAtLeast(0)
    // Pager index 0 and spreads.size + 1 are the chapter transitions
    val pagerState = rememberPagerState(initialPage = spreadOf(state.startPage) + 1) { spreads.size + 2 }
    // ZOUKAN <--
    val scope = rememberCoroutineScope()
    val zooms = remember { mutableMapOf<Int, PageZoom>() }
    val rtl = state.readingMode == ReadingModes.RIGHT_TO_LEFT
    val vertical = state.readingMode == ReadingModes.VERTICAL
    var lastTurn by remember { mutableStateOf(0L) }

    fun current() = zooms.getOrPut(pagerState.currentPage) { PageZoom() }.also { it.maxZoom = if (disableZoomIn) 1f else PageZoom.MAX_ZOOM }

    fun turn(to: Int) {
        lastTurn = System.currentTimeMillis()
        when {
            to < 0 -> model.previousChapter(atEnd = true)
            to > spreads.size + 1 -> model.nextChapter()
            // ZOUKAN -->
            // Android's "Animate page transitions"
            transitions -> scope.launch { pagerState.animateScrollToPage(to) }
            else -> scope.launch { pagerState.scrollToPage(to) }
            // ZOUKAN <--
        }
    }

    // ZOUKAN -->
    /**
     * Android's "Pan wide images when tapping": a zoomed or wide page pans in the direction of
     * the tap first, and turns at its edge. [dx] and [dy] are -1, 0 or 1.
     */
    fun panOrTurn(dx: Int, dy: Int, forward: Boolean) {
        val zoom = current()
        if (navigateToPan && (zoom.overflowsX || zoom.overflowsY)) {
            val step = Offset(-dx * zoom.viewport.width * 0.8f, -dy * zoom.viewport.height * 0.8f)
            val left = zoom.panBy(step)
            if (left != step) return
        }
        if (forward) actions.next() else actions.previous()
    }
    // ZOUKAN <--

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
    actions.smoothScroll = null

    // ZOUKAN -->
    // The spreads the pager was last laid out with, to read its position through them
    val laidOut = remember { arrayOfNulls<List<Spread>>(1) }
    val laidOutDouble = remember { booleanArrayOf(double) }
    // ZOUKAN <--
    LaunchedEffect(pagerState, spreads) {
        // When the layout changes (a setting, the window turning wide, or a page decoding as a
        // wide one), first move to the spread that holds the page the pager shows or is turning
        // to, so the old pager index is never read through the new spreads. The model's current
        // page lags behind a turn until it settles, so a page that decodes mid-turn would
        // otherwise send the pager back.
        // ZOUKAN -->
        val old = laidOut[0]
        laidOut[0] = spreads
        // Switching between single and double pages keeps the reading position, the last page
        // of the spread, as reopening the chapter would. A spread that changes because a page
        // decoded as wide keeps its first page, so no page is skipped.
        val layoutSwitched = laidOutDouble[0] != double
        laidOutDouble[0] = double
        val heading = pagerState.targetPage
        val target = when {
            old == null -> spreadOf(state.currentPage) + 1
            heading <= 0 -> 0
            heading > old.size -> spreads.size + 1
            layoutSwitched -> spreadOf(old[heading - 1].last) + 1
            else -> spreadOf(old[heading - 1].first) + 1
        }
        if (pagerState.currentPage != target || pagerState.targetPage != target) pagerState.scrollToPage(target)
        // ZOUKAN <--
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { index ->
            // Leaving a page resets its zoom, as on Android
            zooms.keys.filter { it != index }.forEach { zooms.remove(it) }
            if (index in 1..spreads.size) spreads[index - 1].let { model.onPageChanged(it.last, first = it.first) }
        }
    }

    // ZOUKAN -->
    // A right-click or long-press opens the shown page's actions; a double page offers both pages
    var secondaryAt by remember { mutableStateOf(0L) }
    // The tap detector lives across compositions, so it reads the spreads and order of the latest one
    val latestSpreads by rememberUpdatedState(spreads)
    val latestLeftFirst by rememberUpdatedState(!rtl != invertDouble)
    val latestNav by rememberUpdatedState(Triple(navLayout, navInvert, smallerZones))
    val latestDoubleTap by rememberUpdatedState(doubleTapZoom && !disableZoomIn)
    val latestLongTap by rememberUpdatedState(longTap)
    fun openPageActions() {
        val spread = latestSpreads.getOrNull(pagerState.currentPage - 1) ?: return
        model.openPageActions(spread.first, spread.pages.getOrNull(1), leftFirst = latestLeftFirst)
    }
    // ZOUKAN <--

    val pageContent: @Composable (Int) -> Unit = { index ->
        when (index) {
            0 -> ChapterTransition(state.chapter, state.previousChapter, next = false, color = foreground, onGo = { model.previousChapter(atEnd = true) })
            spreads.size + 1 -> ChapterTransition(state.chapter, state.nextChapter, next = true, color = foreground, onGo = { model.nextChapter() })
            else -> {
                val spread = spreads[index - 1]
                if (spread.pages.size == 2) {
                    // The first page of a pair sits on the side the reading starts from
                    val leftFirst = !rtl != invertDouble
                    val (left, right) = if (leftFirst) spread.pages[0] to spread.pages[1] else spread.pages[1] to spread.pages[0]
                    DoublePage(
                        left = pages[left],
                        right = pages[right],
                        zoom = zooms.getOrPut(index) { PageZoom() },
                        foreground = foreground,
                        onRetry = { model.loadPage(it, retry = true) },
                        // ZOUKAN -->
                        margin = centerMargin == 1 || centerMargin == 3,
                        // ZOUKAN <--
                    )
                } else {
                    // ZOUKAN -->
                    val isWide = spread.first in wide
                    val rotation = when {
                        !isWide || split || !rotateWide -> 0
                        rotateInvert -> -90
                        else -> 90
                    }
                    // ZOUKAN <--
                    ZoomablePage(
                        page = pages[spread.first],
                        zoom = zooms.getOrPut(index) { PageZoom() },
                        fitMode = fitMode,
                        foreground = foreground,
                        onRetry = { model.loadPage(spread.first, retry = true) },
                        // ZOUKAN -->
                        part = spread.part,
                        leftFirst = ReaderSpreads.leftHalfFirst(rtl, splitInvert),
                        rotation = rotation,
                        wideMargin = isWide && rotation == 0 && spread.part == PagePart.WHOLE && centerMargin >= 2,
                        zoomStart = zoomStart,
                        rtl = rtl,
                        autoBackground = autoBackground,
                        // ZOUKAN <--
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
                    // ZOUKAN -->
                    // Android's tap zones ("Tap zones" and "Invert tap zones")
                    val zones = ReaderNavigation.zones(latestNav.first, vertical, ReaderNavigation.Invert.of(latestNav.second), latestNav.third)
                    val x = position.x / size.width.coerceAtLeast(1)
                    val y = position.y / size.height.coerceAtLeast(1)
                    when (ReaderNavigation.regionAt(x, y, zones)) {
                        ReaderNavigation.Region.NEXT -> panOrTurn(
                            if (vertical) {
                                0
                            } else if (rtl) {
                                -1
                            } else {
                                1
                            },
                            if (vertical) 1 else 0,
                            forward = true,
                        )
                        ReaderNavigation.Region.PREV -> panOrTurn(
                            if (vertical) {
                                0
                            } else if (rtl) {
                                1
                            } else {
                                -1
                            },
                            if (vertical) -1 else 0,
                            forward = false,
                        )
                        ReaderNavigation.Region.RIGHT -> panOrTurn(1, 0, forward = vertical || !rtl)
                        ReaderNavigation.Region.LEFT -> panOrTurn(-1, 0, forward = !vertical && rtl)
                        ReaderNavigation.Region.MENU -> model.toggleMenu()
                    }
                    // ZOUKAN <--
                },
                onDoubleTap = { position ->
                    // ZOUKAN -->
                    if (latestDoubleTap) {
                        val zoom = current()
                        if (zoom.zoom > 1.01f) zoom.reset() else zoom.zoomTo(2f, position)
                    }
                    // ZOUKAN <--
                },
                // ZOUKAN -->
                // Android's "Show actions on long tap"; a right-click always shows them
                onLongPress = { if (latestLongTap) openPageActions() },
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
        // ZOUKAN -->
        if (tapZones != null) {
            TapZonesOverlay(tapZones, ReaderNavigation.zones(navLayout, vertical, ReaderNavigation.Invert.of(navInvert), smallerZones))
        }
        // ZOUKAN <--
    }
}

// ZOUKAN -->
// The tap detector lives across compositions, so it calls the handlers of the latest one: those
// read settings such as "Pan wide images when tapping" and "Disable zoom in"
@Composable
private fun Modifier.pointerInputTaps(
    onTap: (Offset, IntSize) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
): Modifier {
    val latestTap by rememberUpdatedState(onTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    return this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { latestTap(it, size) },
                onDoubleTap = { latestDoubleTap(it) },
                onLongPress = { latestLongPress(it) },
            )
        },
    )
}
// ZOUKAN <--

@Composable
private fun ZoomablePage(
    page: ReaderPage,
    zoom: PageZoom,
    fitMode: Int,
    foreground: Color,
    onRetry: () -> Unit,
    // ZOUKAN -->
    part: PagePart = PagePart.WHOLE,
    leftFirst: Boolean = true,
    rotation: Int = 0,
    wideMargin: Boolean = false,
    zoomStart: Int = 1,
    rtl: Boolean = false,
    autoBackground: Boolean = false,
    // ZOUKAN <--
) {
    val pageState by page.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        when (val s = pageState) {
            is ReaderPage.State.Ready -> {
                val image = s.image
                // ZOUKAN -->
                // The size the page shows at: a half when split, turned when rotated, wider with a center margin
                val gap = if (wideMargin) image.height / 30 else 0
                val shown = ReaderSpreads.shownSize(image, part, rotation, gap)
                // Android's scale types, Stretch and Smart fit included
                val base = FitModes.scale(fitMode, shown.width, shown.height, viewport.width, viewport.height, density.density)
                // Android's Automatic background: the page's own color behind it, in a theme of its own
                if (autoBackground) {
                    image.background?.let { Box(Modifier.fillMaxSize().background(it.brush(viewport.width > viewport.height))) }
                }
                val width = (shown.width * base * zoom.zoom).roundToInt().coerceAtLeast(1)
                val height = (shown.height * base * zoom.zoom).roundToInt().coerceAtLeast(1)
                val content = IntSize(width, height)
                SideEffect {
                    zoom.viewport = viewport
                    zoom.contentSize = content
                    // Android's "Zoom start position" for a page wider than the window
                    if (!zoom.started && viewport.width > 0) {
                        zoom.started = true
                        if (content.width > viewport.width + 1) {
                            val right = (viewport.width - content.width).toFloat()
                            val x = when (zoomStart) {
                                2 -> 0f
                                3 -> right
                                4 -> right / 2f
                                else -> if (rtl) right else 0f
                            }
                            zoom.pan = Offset(x, zoom.pan.y)
                        }
                    }
                }
                val pageModifier = Modifier
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
                    }
                if (part == PagePart.WHOLE && rotation == 0 && gap == 0) {
                    PageSlices(image, pageModifier)
                } else {
                    PageContent(image, part, leftFirst, rotation, pageModifier, gap)
                }
                // ZOUKAN <--
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
    // ZOUKAN -->
    margin: Boolean = false,
    // ZOUKAN <--
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
            // ZOUKAN -->
            // SY's center margin between double pages
            if (margin) androidx.compose.foundation.layout.Spacer(Modifier.width(24.dp))
            // ZOUKAN <--
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
