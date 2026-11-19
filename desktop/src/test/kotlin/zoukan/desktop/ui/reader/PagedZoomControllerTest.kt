package zoukan.desktop.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.app.FitModes

class PagedZoomControllerTest {

    @Test
    fun `pages keep independent zoom state until retainOnly clears other pages`() {
        val controller = PagedZoomController()
        controller.zoomIn(1)
        controller.zoomIn(2)
        controller.zoomIn(2)

        controller.zoomOf(1) shouldBe PagedZoomController.KEY_ZOOM_STEP
        controller.zoomOf(2) shouldBe (PagedZoomController.KEY_ZOOM_STEP * PagedZoomController.KEY_ZOOM_STEP)
        controller.trackedPagesCount shouldBe 2

        controller.retainOnly(settledPage = 2)
        controller.trackedPagesCount shouldBe 1
        controller.zoomOf(1) shouldBe 1f
        controller.zoomOf(2) shouldBe (PagedZoomController.KEY_ZOOM_STEP * PagedZoomController.KEY_ZOOM_STEP)
    }

    @Test
    fun `disableZoomIn clamps zoom to 1f and blocks double-tap zoom`() {
        val controller = PagedZoomController(disableZoomIn = true)
        controller.zoomIn(1)
        controller.zoomOf(1) shouldBe 1f

        controller.onDoubleTap(1, Offset(50f, 50f), enabled = true)
        controller.zoomOf(1) shouldBe 1f

        controller.disableZoomIn = false
        controller.onDoubleTap(1, Offset(50f, 50f), enabled = false)
        controller.zoomOf(1) shouldBe 1f

        controller.onDoubleTap(1, Offset(50f, 50f), enabled = true)
        controller.zoomOf(1) shouldBe 2f
        controller.onDoubleTap(1, Offset(50f, 50f), enabled = true)
        controller.zoomOf(1) shouldBe 1f
    }

    @Test
    fun `wheel zoom keeps point under cursor anchored and reset restores 1f`() {
        val controller = PagedZoomController()
        val pageZoom = controller.current(1)
        controller.attachLayout(pageZoom, viewport = IntSize(200, 200), contentSize = IntSize(200, 200))

        controller.onWheelZoom(1, deltaY = -1f, anchor = Offset(40f, 60f))
        controller.zoomOf(1) shouldBe PagedWheel.zoomFactor(-1f)
        val factor = PagedWheel.zoomFactor(-1f)
        pageZoom.pan.x.shouldBeWithinPercentageOf(40f - 40f * factor, 0.01)
        pageZoom.pan.y.shouldBeWithinPercentageOf(60f - 60f * factor, 0.01)

        controller.reset(1)
        controller.zoomOf(1) shouldBe 1f
        pageZoom.pan shouldBe Offset.Zero
    }

    @Test
    fun `computeFittedSize respects FitModes and zoom multiplier`() {
        val controller = PagedZoomController()
        val fitScreen = controller.computeFittedSize(
            fitMode = FitModes.FIT_SCREEN,
            shownSize = IntSize(1000, 2000),
            viewport = IntSize(800, 600),
            density = 1f,
            zoom = 1f,
        )
        fitScreen shouldBe IntSize(300, 600)

        val fitWidthZoomed = controller.computeFittedSize(
            fitMode = FitModes.FIT_WIDTH,
            shownSize = IntSize(1000, 2000),
            viewport = IntSize(800, 600),
            density = 1f,
            zoom = 2f,
        )
        fitWidthZoomed shouldBe IntSize(1600, 3200)
    }

    @Test
    fun `attachLayout applies zoomStart pan only once`() {
        val controller = PagedZoomController()
        val pageZoom = controller.current(1)

        // First layout in RTL with Automatic zoomStart (1) starts at the right edge (-200)
        controller.attachLayout(
            pageZoom = pageZoom,
            viewport = IntSize(100, 100),
            contentSize = IntSize(300, 100),
            zoomStart = 1,
            rtl = true,
        )
        pageZoom.pan shouldBe Offset(-200f, 0f)
        pageZoom.started shouldBe true

        // Subsequent recompositions must preserve user pan instead of resetting to zoomStart
        pageZoom.pan = Offset(-50f, 0f)
        controller.attachLayout(
            pageZoom = pageZoom,
            viewport = IntSize(100, 100),
            contentSize = IntSize(300, 100),
            zoomStart = 1,
            rtl = true,
        )
        pageZoom.pan shouldBe Offset(-50f, 0f)
    }

    @Test
    fun `WebtoonZoomController shares double-tap math and enforces minZoom`() {
        val webtoon = WebtoonZoomController()
        webtoon.zoom shouldBe 1f

        webtoon.onDoubleTap()
        webtoon.zoom shouldBe 2f
        webtoon.onDoubleTap()
        webtoon.zoom shouldBe 1f

        webtoon.onWheelZoom(deltaY = 1f)
        webtoon.zoom shouldBe (1f / WebtoonZoomController.WHEEL_ZOOM_STEP)

        // Enabling disableZoomOut clamps zoomed-out strip back to 1f
        webtoon.updateMinZoom(1f)
        webtoon.zoom shouldBe 1f
        webtoon.zoomOut()
        webtoon.zoom shouldBe 1f

        webtoon.zoomIn()
        webtoon.zoom shouldBe WebtoonZoomController.KEY_ZOOM_STEP
        webtoon.stripWidth(viewportWidth = 1000.dp, sidePaddingPercent = 10, smartScale = 0.5f) shouldBe
            (1000.dp * 0.8f * WebtoonZoomController.KEY_ZOOM_STEP * 0.5f)

        webtoon.reset()
        webtoon.zoom shouldBe 1f
    }
}
