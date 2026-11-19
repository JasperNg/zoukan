package zoukan.desktop.ui.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.moreActions
import zoukan.i18n.desktop.DMR

/**
 * A strip page that is queued or loading. It keeps the shape the page decoded to before (also after
 * its bitmap was released), so scrolling back up does not shift the strip when it decodes again; the
 * raw image shape is kept even where a split or rotated wide page shows another one.
 */
@Composable
internal fun WebtoonPagePlaceholder(page: ReaderPage, number: Int, color: Color) {
    PageLoading(number, color, Modifier.fillMaxWidth().aspectRatio(page.placeholderAspect).testTag("page-placeholder"))
}

/**
 * Webtoon and continuous vertical modes: every page in one scrolling strip. Webtoon has no gaps
 * between pages; continuous vertical has.
 */
@Composable
fun WebtoonViewer(
    model: ReaderScreenModel,
    content: ViewerContent,
    actions: ReaderActions,
    sidePadding: Int,
    background: Color,
    foreground: Color,
    // ZOUKAN -->
    tapZones: TapZonesOverlayState? = null,
    config: WebtoonReaderConfig? = null,
    // ZOUKAN <--
) {
    val pages = content.pages
    val hasPrevious = content.previousChapter != null
    // Item 0 is the previous chapter transition, then the pages, then the next chapter transition
    // ZOUKAN -->
    // With "Always show chapter transition" off, the one between back-to-back chapters is not shown,
    // so the strip ends with a small next chapter button (an empty item before the first page), and
    // the indices stay; keys, taps and auto scroll go on to that chapter directly
    val cfg = config ?: WebtoonReaderConfig.collect(model.preferences)
    val showPrevious = ChapterTransitions.shown(cfg.alwaysShowChapterTransition, content.chapter, content.previousChapter, next = false, hasPages = pages.isNotEmpty())
    val showNext = ChapterTransitions.shown(cfg.alwaysShowChapterTransition, content.chapter, content.nextChapter, next = true, hasPages = pages.isNotEmpty())
    // ZOUKAN <--
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = content.startPage + 1)
    val scope = rememberCoroutineScope()
    val zoomController = remember { WebtoonZoomController() }
    val gap = if (content.readingMode == ReadingModes.CONTINUOUS_VERTICAL) 16.dp else 0.dp
    val hScroll = rememberScrollState()
    // ZOUKAN -->
    // KMK's "Smart scale on wide screen", for Long strip with gaps only when its own setting is on
    val smartScaleOn = content.readingMode != ReadingModes.CONTINUOUS_VERTICAL || cfg.longStripGapSmartScale
    val reduceMotion = zoukan.desktop.ui.common.LocalReducedMotion.current
    val minZoom = if (cfg.disableZoomOut) 1f else WebtoonZoomController.DEFAULT_MIN_ZOOM
    LaunchedEffect(minZoom) { zoomController.updateMinZoom(minZoom) }
    // ZOUKAN <--

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val viewportWidth = maxWidth
        // ZOUKAN -->
        SideEffect { model.onViewport(constraints.maxWidth) }
        // ZOUKAN <--
        // ZOUKAN -->
        val smartScale = if (smartScaleOn) WebtoonScaleTypes.scale(cfg.scaleType, constraints.maxWidth.toFloat(), viewportHeight) else 1f
        val stripWidth = zoomController.stripWidth(viewportWidth, sidePadding, smartScale)
        // ZOUKAN <--

        // ZOUKAN -->
        // Android's "Animate page transitions" (webtoon)
        fun scroll(by: Float) {
            scope.launch { if (cfg.pageTransitions && !reduceMotion) listState.animateScrollBy(by, tween(200)) else listState.scrollBy(by) }
        }
        // Set after every successful composition, not during it
        SideEffect {
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
            actions.next = {
                if (listState.canScrollForward) actions.down() else model.nextChapter()
            }
            actions.previous = {
                if (listState.canScrollBackward) actions.up() else model.previousChapter(atEnd = true)
            }
            actions.first = { scope.launch { listState.scrollToItem(1) } }
            actions.last = { scope.launch { listState.scrollToItem(pages.size) } }
            actions.goTo = { index -> scope.launch { listState.scrollToItem(index + 1) } }
            actions.zoomIn = zoomController::zoomIn
            actions.zoomOut = zoomController::zoomOut
            actions.zoomReset = zoomController::reset
            actions.zoom = { zoomController.zoom }
        }
        // ZOUKAN <--

        // ZOUKAN -->
        var secondaryAt by remember { mutableStateOf(0L) }
        val latestNav by rememberUpdatedState(Triple(cfg.navigationMode, cfg.navInverted, cfg.smallerTapZone))
        val latestLongTap by rememberUpdatedState(cfg.readWithLongTap)

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
                    if ((modifiers.isCtrlPressed || modifiers.isMetaPressed) && cfg.pinchToZoomEnabled) {
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        zoomController.onWheelZoom(change.scrollDelta.y)
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
                // The right-click's actions, for the page in the middle of the window, for screen
                // readers, keyboard users and the button crawl
                .moreActions {
                    val info = listState.layoutInfo
                    pageAt((info.viewportStartOffset + info.viewportEndOffset) / 2f)?.let { model.openPageActions(it) }
                }
                .pointerInput(cfg.doubleTapZoomEnabled) {
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
                        onDoubleTap = if (cfg.doubleTapZoomEnabled) {
                            { zoomController.onDoubleTap() }
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
                        if (showPrevious) {
                            ChapterTransition(
                                content.chapter,
                                content.previousChapter,
                                next = false,
                                color = foreground,
                                onGo = { model.previousChapter(atEnd = true) },
                                modifier = Modifier.height(if (hasPrevious) 320.dp else 160.dp),
                            )
                        } else {
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                    items(pages.size, key = { "page-$it" }) { index ->
                        val page = pages[index]
                        val pageState by page.state.collectAsState()
                        when (val s = pageState) {
                            // ZOUKAN -->
                            is ReaderPage.State.Ready -> {
                                val image = s.image
                                val wide = image.width > image.height
                                val leftFirst = ReaderSpreads.leftHalfFirst(rtl = false, invert = cfg.dualPageInvert)
                                when {
                                    // Android's "Split wide pages": the two halves one under the other
                                    wide && cfg.dualPageSplit -> androidx.compose.foundation.layout.Column {
                                        listOf(PagePart.FIRST_HALF, PagePart.SECOND_HALF).forEach { part ->
                                            val shown = ReaderSpreads.shownSize(image, part, 0)
                                            PageContent(image, part, leftFirst, 0, Modifier.fillMaxWidth().aspectRatio(shown.width / shown.height.coerceAtLeast(1).toFloat()))
                                        }
                                    }
                                    // "Rotate wide pages to fit"
                                    wide && cfg.dualPageRotateToFit -> {
                                        val rotation = if (cfg.dualPageRotateToFitInvert) -90 else 90
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
                                modifier = Modifier.fillMaxWidth().aspectRatio(page.placeholderAspect),
                            )
                            else -> {
                                LaunchedEffect(page) { model.loadPage(index) }
                                WebtoonPagePlaceholder(page, index + 1, foreground)
                            }
                        }
                    }
                    item(key = "next") {
                        if (showNext) {
                            ChapterTransition(
                                content.chapter,
                                content.nextChapter,
                                next = true,
                                color = foreground,
                                onGo = { model.nextChapter() },
                                modifier = Modifier.fillMaxWidth().height(with(density) { (viewportHeight * 0.6f).toDp() }),
                            )
                        } else if (content.nextChapter != null) {
                            // A wheel or trackpad scroll stops at the strip's end: keep a way on there
                            Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                                FilledTonalButton(onClick = { model.nextChapter() }) {
                                    Text(localized(DMR.strings.next_chapter_button))
                                }
                            }
                        } else {
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
        // ZOUKAN -->
        if (tapZones != null) {
            TapZonesOverlay(tapZones, ReaderNavigation.zones(cfg.navigationMode, verticalViewer = true, ReaderNavigation.Invert.of(cfg.navInverted), cfg.smallerTapZone), vertical = true)
        }
        // ZOUKAN <--
    }
}
