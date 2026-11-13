package zoukan.desktop.ui.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.WebtoonScaleTypes
import zoukan.desktop.ui.common.collectAsState

/**
 * Webtoon and continuous vertical modes: every page in one scrolling strip. Webtoon has no gaps
 * between pages; continuous vertical has.
 */
@Composable
fun WebtoonViewer(
    model: ReaderScreenModel,
    state: ReaderScreenModel.State,
    actions: ReaderActions,
    sidePadding: Int,
    background: Color,
    foreground: Color,
    // ZOUKAN -->
    tapZones: TapZonesOverlayState? = null,
    // ZOUKAN <--
) {
    val pages = state.pages
    val hasPrevious = state.previousChapter != null
    // Item 0 is the previous chapter transition, then the pages, then the next chapter transition
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.startPage + 1)
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    val gap = if (state.readingMode == ReadingModes.CONTINUOUS_VERTICAL) 16.dp else 0.dp
    val hScroll = rememberScrollState()
    // ZOUKAN -->
    val prefs = model.preferences
    val navLayout by prefs.navigationModeWebtoon().collectAsState()
    val navInvert by prefs.webtoonNavInverted().collectAsState()
    val smallerZones by prefs.smallerTapZone().collectAsState()
    val transitions by prefs.pageTransitionsWebtoon().collectAsState()
    val doubleTapZoom by prefs.webtoonDoubleTapZoomEnabled().collectAsState()
    val pinchToZoom by prefs.webtoonPinchToZoomEnabled().collectAsState()
    val noZoomOut by prefs.webtoonDisableZoomOut().collectAsState()
    val longTap by prefs.readWithLongTap().collectAsState()
    val split by prefs.dualPageSplitWebtoon().collectAsState()
    val splitInvert by prefs.dualPageInvertWebtoon().collectAsState()
    val rotateWide by prefs.dualPageRotateToFitWebtoon().collectAsState()
    val rotateInvert by prefs.dualPageRotateToFitInvertWebtoon().collectAsState()
    // KMK's "Smart scale on wide screen", for Long strip with gaps only when its own setting is on
    val scaleType by prefs.webtoonScaleType().collectAsState()
    val gapScale by prefs.longStripGapSmartScale().collectAsState()
    val smartScaleOn = state.readingMode != ReadingModes.CONTINUOUS_VERTICAL || gapScale
    val minZoom = if (noZoomOut) 1f else 0.3f
    LaunchedEffect(minZoom) { if (zoom < minZoom) zoom = minZoom }
    // ZOUKAN <--

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val viewportWidth = maxWidth
        // ZOUKAN -->
        val smartScale = if (smartScaleOn) WebtoonScaleTypes.scale(scaleType, constraints.maxWidth.toFloat(), viewportHeight) else 1f
        val stripWidth = viewportWidth * (1f - 2f * sidePadding.coerceIn(0, 25) / 100f) * zoom * smartScale
        // ZOUKAN <--

        // ZOUKAN -->
        // Android's "Animate page transitions" (webtoon)
        fun scroll(by: Float) {
            scope.launch { if (transitions) listState.animateScrollBy(by, tween(200)) else listState.scrollBy(by) }
        }
        actions.down = { scroll(viewportHeight * 0.8f) }
        actions.up = { scroll(-viewportHeight * 0.8f) }
        // SY's smooth auto scroll: a window's height over each interval
        actions.smoothScroll = { millis ->
            scope.launch {
                if (listState.canScrollForward) {
                    listState.animateScrollBy(viewportHeight, tween(millis.toInt(), easing = androidx.compose.animation.core.LinearEasing))
                } else {
                    model.nextChapter()
                }
            }
        }
        // ZOUKAN <--
        actions.next = {
            if (listState.canScrollForward) actions.down() else model.nextChapter()
        }
        actions.previous = {
            if (listState.canScrollBackward) actions.up() else model.previousChapter(atEnd = true)
        }
        actions.first = { scope.launch { listState.scrollToItem(1) } }
        actions.last = { scope.launch { listState.scrollToItem(pages.size) } }
        actions.goTo = { index -> scope.launch { listState.scrollToItem(index + 1) } }
        actions.zoomIn = { zoom = (zoom * 1.2f).coerceAtMost(3f) }
        actions.zoomOut = { zoom = (zoom / 1.2f).coerceAtLeast(minZoom) }
        actions.zoomReset = { zoom = 1f }

        // ZOUKAN -->
        var secondaryAt by remember { mutableStateOf(0L) }
        val latestNav by androidx.compose.runtime.rememberUpdatedState(Triple(navLayout, navInvert, smallerZones))
        val latestLongTap by androidx.compose.runtime.rememberUpdatedState(longTap)

        /** The page at [y] pixels from the top of the strip's window, or null over a chapter transition */
        fun pageAt(y: Float): Int? = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?.index?.minus(1)?.takeIf { it in pages.indices }
        // ZOUKAN <--

        // The current page is the one at the middle of the window
        LaunchedEffect(listState, pages) {
            // ZOUKAN -->
            var lastPage = -1
            snapshotFlow {
                val info = listState.layoutInfo
                val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val item = info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size > middle }?.index
                    ?: listState.firstVisibleItemIndex
                // Every page in the window stays decoded, however far out the strip is zoomed
                val shown = info.visibleItemsInfo.map { it.index - 1 }.filter { it in pages.indices }
                item to if (shown.isEmpty()) IntRange.EMPTY else shown.min()..shown.max()
            }.distinctUntilChanged().collect { (item, visible) ->
                model.onVisiblePages(visible)
                val page = (item - 1).coerceIn(0, pages.lastIndex)
                if (page != lastPage) {
                    lastPage = page
                    model.onPageChanged(page)
                }
            }
            // ZOUKAN <--
        }

        Box(
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    val modifiers = event.keyboardModifiers
                    // The wheel gesture of Android's pinch to zoom (webtoonPinchToZoomEnabled)
                    if ((modifiers.isCtrlPressed || modifiers.isMetaPressed) && pinchToZoom) {
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        zoom = (zoom * if (change.scrollDelta.y < 0) 1.1f else 1 / 1.1f).coerceIn(minZoom, 3f)
                        change.consume()
                    }
                }
                // ZOUKAN -->
                // A right-click or long-press opens the actions of the page under the pointer
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        secondaryAt = System.currentTimeMillis()
                        event.changes.forEach { it.consume() }
                        pageAt(change.position.y)?.let { model.openPageActions(it) }
                    }
                }
                .pointerInput(doubleTapZoom) {
                    detectTapGestures(
                        onTap = { position ->
                            if (System.currentTimeMillis() - secondaryAt < 1000) return@detectTapGestures
                            // Android's tap zones; the webtoon default is L shaped
                            val (layout, invert, smaller) = latestNav
                            val zones = ReaderNavigation.zones(layout, verticalViewer = true, ReaderNavigation.Invert.of(invert), smaller)
                            val x = position.x / size.width.coerceAtLeast(1)
                            val y = position.y / size.height.coerceAtLeast(1)
                            when (ReaderNavigation.regionAt(x, y, zones)) {
                                ReaderNavigation.Region.NEXT, ReaderNavigation.Region.RIGHT -> actions.next()
                                ReaderNavigation.Region.PREV, ReaderNavigation.Region.LEFT -> actions.previous()
                                ReaderNavigation.Region.MENU -> model.toggleMenu()
                            }
                        },
                        onDoubleTap = if (doubleTapZoom) {
                            { zoom = if (zoom > 1.01f) 1f else 2f }
                        } else {
                            null
                        },
                        onLongPress = { position -> if (latestLongTap) pageAt(position.y)?.let { model.openPageActions(it) } },
                    )
                }
                // ZOUKAN <--
                .horizontalScroll(hScroll),
        ) {
            val density = LocalDensity.current
            val width = maxOf(stripWidth, viewportWidth)
            Box(Modifier.width(width).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(stripWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    item(key = "previous") {
                        ChapterTransition(
                            state.chapter,
                            state.previousChapter,
                            next = false,
                            color = foreground,
                            onGo = { model.previousChapter(atEnd = true) },
                            modifier = Modifier.height(if (hasPrevious) 320.dp else 160.dp),
                        )
                    }
                    items(pages.size, key = { "page-$it" }) { index ->
                        val page = pages[index]
                        val pageState by page.state.collectAsState()
                        when (val s = pageState) {
                            // ZOUKAN -->
                            is ReaderPage.State.Ready -> {
                                val image = s.image
                                val wide = image.width > image.height
                                val leftFirst = ReaderSpreads.leftHalfFirst(rtl = false, invert = splitInvert)
                                when {
                                    // Android's "Split wide pages": the two halves one under the other
                                    wide && split -> androidx.compose.foundation.layout.Column {
                                        listOf(PagePart.FIRST_HALF, PagePart.SECOND_HALF).forEach { part ->
                                            val shown = ReaderSpreads.shownSize(image, part, 0)
                                            PageContent(image, part, leftFirst, 0, Modifier.fillMaxWidth().aspectRatio(shown.width / shown.height.coerceAtLeast(1).toFloat()))
                                        }
                                    }
                                    // "Rotate wide pages to fit"
                                    wide && rotateWide -> {
                                        val rotation = if (rotateInvert) -90 else 90
                                        val shown = ReaderSpreads.shownSize(image, PagePart.WHOLE, rotation)
                                        PageContent(image, PagePart.WHOLE, true, rotation, Modifier.fillMaxWidth().aspectRatio(shown.width / shown.height.coerceAtLeast(1).toFloat()))
                                    }
                                    else -> PageSlices(image, Modifier.fillMaxWidth())
                                }
                            }
                            // ZOUKAN <--
                            is ReaderPage.State.Error -> PageError(
                                index + 1,
                                s.message,
                                foreground,
                                onRetry = { model.loadPage(index, retry = true) },
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                            )
                            else -> {
                                LaunchedEffect(page) { model.loadPage(index) }
                                PageLoading(index + 1, foreground, Modifier.fillMaxWidth().aspectRatio(0.7f))
                            }
                        }
                    }
                    item(key = "next") {
                        ChapterTransition(
                            state.chapter,
                            state.nextChapter,
                            next = true,
                            color = foreground,
                            onGo = { model.nextChapter() },
                            modifier = Modifier.fillMaxWidth().height(with(density) { (viewportHeight * 0.6f).toDp() }),
                        )
                    }
                }
            }
        }
        // ZOUKAN -->
        if (tapZones != null) {
            TapZonesOverlay(tapZones, ReaderNavigation.zones(navLayout, verticalViewer = true, ReaderNavigation.Invert.of(navInvert), smallerZones))
        }
        // ZOUKAN <--
    }
}
