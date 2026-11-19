package zoukan.desktop.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import zoukan.desktop.app.FitModes
import kotlin.math.roundToInt

/** Shared zoom math and bounds used across paged and webtoon viewers. */
internal object ReaderZoomMath {
    const val DOUBLE_TAP_RESET_THRESHOLD = 1.01f
    const val DOUBLE_TAP_TARGET_ZOOM = 2f

    fun clampZoom(value: Float, minZoom: Float, maxZoom: Float): Float =
        value.coerceIn(minZoom, maxZoom.coerceAtLeast(minZoom))

    fun isZoomedIn(zoom: Float): Boolean = zoom > DOUBLE_TAP_RESET_THRESHOLD

    fun doubleTapTarget(currentZoom: Float, minZoom: Float, maxZoom: Float): Float =
        if (isZoomedIn(currentZoom)) 1f else clampZoom(DOUBLE_TAP_TARGET_ZOOM, minZoom, maxZoom)
}

/**
 * Zoom and pan of one page. [pan] is the top left corner of the page relative to the viewport,
 * in pixels; it is kept inside the bounds by [clamp].
 */
class PageZoom {
    var zoom by mutableFloatStateOf(1f)
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
        val new = ReaderZoomMath.clampZoom(value, MIN_ZOOM, maxZoom)
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

    // ZOUKAN -->
    /**
     * The Down (or Up) key on a page taller than the window: pans by most of the window's height.
     * Returns true when the key was spent on the pan, false at the page's edge (or on a page that
     * fits), where the key turns the page instead.
     */
    internal fun panVertically(down: Boolean): Boolean {
        if (!overflowsY) return false
        val step = viewport.height * 0.8f
        return panBy(Offset(0f, if (down) -step else step)).y == 0f
    }

    /**
     * "Pan wide images when tapping": pans a page larger than the window by most of the window
     * towards [dx], [dy] (-1, 0 or 1). Returns true when it moved at least part of the way, false
     * when the page fits or is already at that edge, where the tap turns the page instead.
     */
    internal fun panTowards(dx: Int, dy: Int): Boolean {
        if (!overflowsX && !overflowsY) return false
        val step = Offset(-dx * viewport.width * 0.8f, -dy * viewport.height * 0.8f)
        return panBy(step) != step
    }

    /** A double-click: back to the fit when zoomed in, otherwise twice the size around [anchor]. */
    internal fun toggleDoubleTapZoom(anchor: Offset) {
        if (ReaderZoomMath.isZoomedIn(zoom)) reset() else zoomTo(ReaderZoomMath.DOUBLE_TAP_TARGET_ZOOM, anchor)
    }
    // ZOUKAN <--

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

/**
 * Coordinates per-page [PageZoom] state, fit-mode scaling, initial pan placement, wheel zoom,
 * and double-tap zoom for [PagedViewer].
 */
internal class PagedZoomController(disableZoomIn: Boolean = false) {
    private val zooms = mutableMapOf<Int, PageZoom>()

    var disableZoomIn: Boolean = disableZoomIn
        set(value) {
            field = value
            val limit = effectiveMaxZoom()
            zooms.values.forEach { it.maxZoom = limit }
        }

    private fun effectiveMaxZoom(): Float = if (disableZoomIn) 1f else PageZoom.MAX_ZOOM

    fun current(pageIndex: Int): PageZoom =
        zooms.getOrPut(pageIndex) { PageZoom() }.also { it.maxZoom = effectiveMaxZoom() }

    /** Leaving a page resets its zoom, as on Android. */
    fun retainOnly(settledPage: Int) {
        zooms.keys.filter { it != settledPage }.forEach { zooms.remove(it) }
    }

    fun zoomIn(pageIndex: Int) {
        current(pageIndex).let { it.zoomTo(it.zoom * KEY_ZOOM_STEP) }
    }

    fun zoomOut(pageIndex: Int) {
        current(pageIndex).let { it.zoomTo(it.zoom / KEY_ZOOM_STEP) }
    }

    fun reset(pageIndex: Int) {
        current(pageIndex).reset()
    }

    fun zoomOf(pageIndex: Int): Float = current(pageIndex).zoom

    fun onWheelZoom(pageIndex: Int, deltaY: Float, anchor: Offset) {
        val pageZoom = current(pageIndex)
        pageZoom.zoomTo(pageZoom.zoom * PagedWheel.zoomFactor(deltaY), anchor)
    }

    fun onDoubleTap(pageIndex: Int, position: Offset, enabled: Boolean) {
        if (!enabled || disableZoomIn) return
        current(pageIndex).toggleDoubleTapZoom(position)
    }

    fun computeFittedSize(
        fitMode: Int,
        shownSize: IntSize,
        viewport: IntSize,
        density: Float,
        zoom: Float,
    ): IntSize {
        val base = FitModes.scale(fitMode, shownSize.width, shownSize.height, viewport.width, viewport.height, density)
        val width = (shownSize.width * base * zoom).roundToInt().coerceAtLeast(1)
        val height = (shownSize.height * base * zoom).roundToInt().coerceAtLeast(1)
        return IntSize(width, height)
    }

    fun attachLayout(
        pageZoom: PageZoom,
        viewport: IntSize,
        contentSize: IntSize,
        zoomStart: Int? = null,
        rtl: Boolean = false,
    ) {
        pageZoom.viewport = viewport
        pageZoom.contentSize = contentSize
        if (zoomStart != null && !pageZoom.started && viewport.width > 0) {
            pageZoom.started = true
            PagedPager.startPanX(zoomStart, rtl, viewport.width, contentSize.width)?.let { x ->
                pageZoom.pan = Offset(x, pageZoom.pan.y)
            }
        }
    }

    internal val trackedPagesCount: Int get() = zooms.size

    companion object {
        const val KEY_ZOOM_STEP = 1.25f
    }
}

/** Coordinates strip zoom state and clamping for [WebtoonViewer]. */
internal class WebtoonZoomController(minZoom: Float = DEFAULT_MIN_ZOOM) {
    var minZoom by mutableFloatStateOf(minZoom)
        private set

    var zoom by mutableFloatStateOf(1f)
        private set

    fun updateMinZoom(newMinZoom: Float) {
        minZoom = newMinZoom
        if (zoom < newMinZoom) zoom = newMinZoom
    }

    fun zoomIn() {
        zoom = ReaderZoomMath.clampZoom(zoom * KEY_ZOOM_STEP, minZoom, MAX_ZOOM)
    }

    fun zoomOut() {
        zoom = ReaderZoomMath.clampZoom(zoom / KEY_ZOOM_STEP, minZoom, MAX_ZOOM)
    }

    fun reset() {
        zoom = 1f
    }

    fun onWheelZoom(deltaY: Float) {
        val factor = if (deltaY < 0) WHEEL_ZOOM_STEP else 1f / WHEEL_ZOOM_STEP
        zoom = ReaderZoomMath.clampZoom(zoom * factor, minZoom, MAX_ZOOM)
    }

    fun onDoubleTap() {
        zoom = ReaderZoomMath.doubleTapTarget(zoom, minZoom, MAX_ZOOM)
    }

    fun stripWidth(viewportWidth: Dp, sidePaddingPercent: Int, smartScale: Float): Dp =
        viewportWidth * (1f - 2f * sidePaddingPercent.coerceIn(0, 25) / 100f) * zoom * smartScale

    companion object {
        const val DEFAULT_MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 3f
        const val KEY_ZOOM_STEP = 1.2f
        const val WHEEL_ZOOM_STEP = 1.1f
    }
}
