package zoukan.desktop.ui.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
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
) {
    val pages = state.pages
    val hasPrevious = state.previousChapter != null
    // Item 0 is the previous chapter transition, then the pages, then the next chapter transition
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.startPage + 1)
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    val gap = if (state.readingMode == ReadingModes.CONTINUOUS_VERTICAL) 16.dp else 0.dp
    val hScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val viewportWidth = maxWidth
        val stripWidth = viewportWidth * (1f - 2f * sidePadding.coerceIn(0, 25) / 100f) * zoom

        actions.down = { scope.launch { listState.animateScrollBy(viewportHeight * 0.8f, tween(200)) } }
        actions.up = { scope.launch { listState.animateScrollBy(-viewportHeight * 0.8f, tween(200)) } }
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
        actions.zoomOut = { zoom = (zoom / 1.2f).coerceAtLeast(0.3f) }
        actions.zoomReset = { zoom = 1f }

        // ZOUKAN -->
        var secondaryAt by remember { mutableStateOf(0L) }

        /** The page at [y] pixels from the top of the strip's window, or null over a chapter transition */
        fun pageAt(y: Float): Int? = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?.index?.minus(1)?.takeIf { it in pages.indices }
        // ZOUKAN <--

        // The current page is the one at the middle of the window
        LaunchedEffect(listState, pages) {
            snapshotFlow {
                val info = listState.layoutInfo
                val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size > middle }?.index
                    ?: listState.firstVisibleItemIndex
            }.distinctUntilChanged().collect { item ->
                val page = (item - 1).coerceIn(0, pages.lastIndex)
                model.onPageChanged(page)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                    val modifiers = event.keyboardModifiers
                    if (modifiers.isCtrlPressed || modifiers.isMetaPressed) {
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        zoom = (zoom * if (change.scrollDelta.y < 0) 1.1f else 1 / 1.1f).coerceIn(0.3f, 3f)
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (System.currentTimeMillis() - secondaryAt >= 1000) model.toggleMenu() },
                        onLongPress = { position -> pageAt(position.y)?.let { model.openPageActions(it) } },
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
                            is ReaderPage.State.Ready -> PageSlices(s.image, Modifier.fillMaxWidth())
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
    }
}
