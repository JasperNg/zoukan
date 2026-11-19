package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.PagedPager.Turn
import zoukan.desktop.ui.reader.ReaderNavigation.Region
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * Tests for the paged reader: pager decisions ([PagedPager]), recomposition stability, zoom/pan
 * controllers ([PageZoom], [PagedZoomController], [WebtoonZoomController]), mouse wheel paging,
 * double-page spreads, scale modes/backgrounds, and animated GIF pages.
 */
class PagedViewerTest {

    @Nested
    inner class PagingAndPageZoom {

        private fun single(count: Int) = ReaderSpreads.build(count, double = false)

        // --- Page turns and the chapter's ends ---

        @Test
        fun `a turn inside the chapter moves the pager, animated only when transitions are on`() {
            PagedPager.turn(to = 2, spreadCount = 5, showPrevious = true, showNext = true, animate = true) shouldBe Turn.ANIMATE
            PagedPager.turn(to = 2, spreadCount = 5, showPrevious = true, showNext = true, animate = false) shouldBe Turn.JUMP
        }

        @Test
        fun `a turn past either end opens the neighbouring chapter`() {
            PagedPager.turn(to = -1, spreadCount = 5, showPrevious = true, showNext = true, animate = true) shouldBe Turn.PREVIOUS_CHAPTER
            PagedPager.turn(to = 7, spreadCount = 5, showPrevious = true, showNext = true, animate = true) shouldBe Turn.NEXT_CHAPTER
        }

        @Test
        fun `a turn onto a shown transition stops there, onto a skipped one opens that chapter`() {
            // Shown: the first page's previous turn and the last page's next turn land on the transitions
            PagedPager.turn(to = 0, spreadCount = 5, showPrevious = true, showNext = true, animate = false) shouldBe Turn.JUMP
            PagedPager.turn(to = 6, spreadCount = 5, showPrevious = true, showNext = true, animate = false) shouldBe Turn.JUMP
            // Skipped ("Always show chapter transition" off, back-to-back chapters)
            PagedPager.turn(to = 0, spreadCount = 5, showPrevious = false, showNext = true, animate = false) shouldBe Turn.PREVIOUS_CHAPTER
            PagedPager.turn(to = 6, spreadCount = 5, showPrevious = true, showNext = false, animate = false) shouldBe Turn.NEXT_CHAPTER
            // A skipped transition on one side does not change the other side
            PagedPager.turn(to = 6, spreadCount = 5, showPrevious = false, showNext = true, animate = true) shouldBe Turn.ANIMATE
        }

        @Test
        fun `a chapter with no pages has only its two transitions`() {
            val spreads = single(0)
            spreads shouldBe emptyList()
            // Index 1 is already the next chapter's transition
            PagedPager.turn(to = 1, spreadCount = 0, showPrevious = true, showNext = true, animate = false) shouldBe Turn.JUMP
            PagedPager.turn(to = 2, spreadCount = 0, showPrevious = true, showNext = true, animate = false) shouldBe Turn.NEXT_CHAPTER
            PagedPager.settled(1, spreads, previousShown = true, nextShown = false) shouldBe PagedPager.Settled(null, openPrevious = false, openNext = true)
            PagedPager.settled(0, spreads, previousShown = true, nextShown = true) shouldBe PagedPager.Settled(null, openPrevious = false, openNext = false)
            // As it is: Home goes to index 1 (the next transition) and End to index 0 (the previous one)
            PagedPager.FIRST_INDEX shouldBe 1
            PagedPager.lastIndex(spreads.size) shouldBe 0
            // A chapter without pages always shows both transitions
            ChapterTransitions.shown(always = false, current = null, other = null, next = true, hasPages = false) shouldBe true
        }

        @Test
        fun `a chapter with one page sits between its transitions`() {
            val spreads = single(1)
            PagedPager.lastIndex(spreads.size) shouldBe PagedPager.FIRST_INDEX
            PagedPager.spreadOf(spreads, 0) shouldBe 0
            PagedPager.turn(to = 0, spreadCount = 1, showPrevious = true, showNext = true, animate = false) shouldBe Turn.JUMP
            PagedPager.turn(to = 2, spreadCount = 1, showPrevious = true, showNext = false, animate = false) shouldBe Turn.NEXT_CHAPTER
            PagedPager.settled(1, spreads, previousShown = false, nextShown = false) shouldBe PagedPager.Settled(Spread(listOf(0)), openPrevious = false, openNext = false)
        }

        @Test
        fun `first and last go to the first and last spread`() {
            val spreads = ReaderSpreads.build(5, double = true)
            // [0], [1, 2], [3, 4]
            PagedPager.FIRST_INDEX shouldBe 1
            PagedPager.lastIndex(spreads.size) shouldBe 3
            spreads[PagedPager.lastIndex(spreads.size) - 1] shouldBe Spread(listOf(3, 4))
        }

        @Test
        fun `settling on a spread reads it, on a skipped transition opens that chapter`() {
            val spreads = ReaderSpreads.build(5, double = true)
            PagedPager.settled(2, spreads, previousShown = true, nextShown = true) shouldBe PagedPager.Settled(Spread(listOf(1, 2)), openPrevious = false, openNext = false)
            PagedPager.settled(0, spreads, previousShown = false, nextShown = true) shouldBe PagedPager.Settled(null, openPrevious = true, openNext = false)
            PagedPager.settled(0, spreads, previousShown = true, nextShown = true) shouldBe PagedPager.Settled(null, openPrevious = false, openNext = false)
            PagedPager.settled(4, spreads, previousShown = true, nextShown = false) shouldBe PagedPager.Settled(null, openPrevious = false, openNext = true)
            PagedPager.settled(4, spreads, previousShown = true, nextShown = true) shouldBe PagedPager.Settled(null, openPrevious = false, openNext = false)
        }

        // --- Spreads and the reading position ---

        @Test
        fun `the page layout setting picks single or double pages`() {
            PagedPager.isDouble(layout = 0, width = 1600, height = 900) shouldBe false
            PagedPager.isDouble(layout = 1, width = 900, height = 1600) shouldBe true
            // Automatic: double only in a window wider than tall
            PagedPager.isDouble(layout = 2, width = 1600, height = 900) shouldBe true
            PagedPager.isDouble(layout = 2, width = 900, height = 1600) shouldBe false
            PagedPager.isDouble(layout = 2, width = 1000, height = 1000) shouldBe false
        }

        @Test
        fun `a page's spread is the first that holds it, and an unknown page goes to the first`() {
            val double = ReaderSpreads.build(5, double = true)
            PagedPager.spreadOf(double, 0) shouldBe 0
            PagedPager.spreadOf(double, 2) shouldBe 1
            PagedPager.spreadOf(double, 4) shouldBe 2
            PagedPager.spreadOf(double, 99) shouldBe 0
            PagedPager.spreadOf(double, -1) shouldBe 0
            // A split wide page opens at its first half
            val split = ReaderSpreads.build(3, double = false, wide = setOf(1), split = true)
            PagedPager.spreadOf(split, 1) shouldBe 1
            split[1].part shouldBe PagePart.FIRST_HALF
        }

        @Test
        fun `the first layout opens at the model's page`() {
            val spreads = ReaderSpreads.build(5, double = true)
            PagedPager.relayoutTarget(null, spreads, heading = 1, layoutSwitched = false) { 3 } shouldBe 3
        }

        @Test
        fun `switching between single and double pages keeps the last page of the spread`() {
            val single = single(5)
            val double = ReaderSpreads.build(5, double = true)
            // Reading page 2 (index 3) in single pages: double pages show [1, 2]
            PagedPager.relayoutTarget(single, double, heading = 3, layoutSwitched = true) { error("not read") } shouldBe 2
            // Reading [3, 4] (index 3) in double pages: single pages show page 4
            PagedPager.relayoutTarget(double, single, heading = 3, layoutSwitched = true) { error("not read") } shouldBe 5
        }

        @Test
        fun `a page that decodes wide keeps the first page of the spread`() {
            val before = ReaderSpreads.build(5, double = true)
            val after = ReaderSpreads.build(5, double = true, wide = setOf(2))
            // [0], [1, 2], [3, 4] becomes [0], [1], [2], [3, 4]: reading [1, 2] stays on page 1
            after shouldBe listOf(Spread(listOf(0)), Spread(listOf(1)), Spread(listOf(2)), Spread(listOf(3, 4)))
            PagedPager.relayoutTarget(before, after, heading = 2, layoutSwitched = false) { error("not read") } shouldBe 2
        }

        @Test
        fun `a transition stays a transition when the spreads change`() {
            val before = ReaderSpreads.build(5, double = true)
            val after = single(5)
            PagedPager.relayoutTarget(before, after, heading = 0, layoutSwitched = true) { error("not read") } shouldBe 0
            PagedPager.relayoutTarget(before, after, heading = 4, layoutSwitched = true) { error("not read") } shouldBe 6
        }

        // --- Taps, in both reading directions ---

        @Test
        fun `left to right, the next zone pans right and turns forward`() {
            PagedPager.tapMove(Region.NEXT, vertical = false, rtl = false) shouldBe PagedPager.TapMove(1, 0, forward = true)
            PagedPager.tapMove(Region.PREV, vertical = false, rtl = false) shouldBe PagedPager.TapMove(-1, 0, forward = false)
            PagedPager.tapMove(Region.RIGHT, vertical = false, rtl = false) shouldBe PagedPager.TapMove(1, 0, forward = true)
            PagedPager.tapMove(Region.LEFT, vertical = false, rtl = false) shouldBe PagedPager.TapMove(-1, 0, forward = false)
        }

        @Test
        fun `right to left, the next zone pans left and the left side turns forward`() {
            PagedPager.tapMove(Region.NEXT, vertical = false, rtl = true) shouldBe PagedPager.TapMove(-1, 0, forward = true)
            PagedPager.tapMove(Region.PREV, vertical = false, rtl = true) shouldBe PagedPager.TapMove(1, 0, forward = false)
            PagedPager.tapMove(Region.RIGHT, vertical = false, rtl = true) shouldBe PagedPager.TapMove(1, 0, forward = false)
            PagedPager.tapMove(Region.LEFT, vertical = false, rtl = true) shouldBe PagedPager.TapMove(-1, 0, forward = true)
        }

        @Test
        fun `vertical, the zones pan down and up, and the sides turn as left to right`() {
            PagedPager.tapMove(Region.NEXT, vertical = true, rtl = false) shouldBe PagedPager.TapMove(0, 1, forward = true)
            PagedPager.tapMove(Region.PREV, vertical = true, rtl = false) shouldBe PagedPager.TapMove(0, -1, forward = false)
            PagedPager.tapMove(Region.RIGHT, vertical = true, rtl = false) shouldBe PagedPager.TapMove(1, 0, forward = true)
            PagedPager.tapMove(Region.LEFT, vertical = true, rtl = false) shouldBe PagedPager.TapMove(-1, 0, forward = false)
        }

        @Test
        fun `the menu zone opens the menu in every direction`() {
            for (vertical in listOf(false, true)) {
                for (rtl in listOf(false, true)) PagedPager.tapMove(Region.MENU, vertical, rtl) shouldBe null
            }
        }

        // --- Double pages and wide pages ---

        @Test
        fun `the first page of a pair sits on the side the reading starts from`() {
            PagedPager.leftFirst(rtl = false, invertDouble = false) shouldBe true
            PagedPager.leftFirst(rtl = true, invertDouble = false) shouldBe false
            // "Invert double pages"
            PagedPager.leftFirst(rtl = false, invertDouble = true) shouldBe false
            PagedPager.leftFirst(rtl = true, invertDouble = true) shouldBe true
        }

        @Test
        fun `the center margin types`() {
            // 0 none, 1 double pages, 2 wide pages, 3 both
            (0..3).map { PagedPager.doubleMargin(it) } shouldBe listOf(false, true, false, true)
            (0..3).map { PagedPager.wideMargin(isWide = true, rotation = 0, part = PagePart.WHOLE, centerMargin = it) } shouldBe listOf(false, false, true, true)
            PagedPager.wideMargin(isWide = false, rotation = 0, part = PagePart.WHOLE, centerMargin = 3) shouldBe false
            PagedPager.wideMargin(isWide = true, rotation = 90, part = PagePart.WHOLE, centerMargin = 3) shouldBe false
            PagedPager.wideMargin(isWide = true, rotation = 0, part = PagePart.FIRST_HALF, centerMargin = 3) shouldBe false
        }

        @Test
        fun `only a wide page that is not split turns, and only with rotate to fit on`() {
            PagedPager.rotation(isWide = false, split = false, rotateWide = true, rotateInvert = false) shouldBe 0
            PagedPager.rotation(isWide = true, split = true, rotateWide = true, rotateInvert = false) shouldBe 0
            PagedPager.rotation(isWide = true, split = false, rotateWide = false, rotateInvert = false) shouldBe 0
            PagedPager.rotation(isWide = true, split = false, rotateWide = true, rotateInvert = false) shouldBe 90
            PagedPager.rotation(isWide = true, split = false, rotateWide = true, rotateInvert = true) shouldBe -90
        }

        // --- Zoom start position ---

        @Test
        fun `a page wider than the window starts at the zoom start position`() {
            // Window 100 wide, page 300 wide: the right edge shows at -200
            PagedPager.startPanX(zoomStart = 1, rtl = false, viewportWidth = 100, contentWidth = 300) shouldBe 0f
            PagedPager.startPanX(zoomStart = 1, rtl = true, viewportWidth = 100, contentWidth = 300) shouldBe -200f
            PagedPager.startPanX(zoomStart = 2, rtl = true, viewportWidth = 100, contentWidth = 300) shouldBe 0f
            PagedPager.startPanX(zoomStart = 3, rtl = false, viewportWidth = 100, contentWidth = 300) shouldBe -200f
            PagedPager.startPanX(zoomStart = 4, rtl = false, viewportWidth = 100, contentWidth = 300) shouldBe -100f
        }

        @Test
        fun `a page that fits, or overflows by one pixel, has no start position`() {
            PagedPager.startPanX(zoomStart = 3, rtl = false, viewportWidth = 100, contentWidth = 80) shouldBe null
            PagedPager.startPanX(zoomStart = 3, rtl = false, viewportWidth = 100, contentWidth = 101) shouldBe null
            PagedPager.startPanX(zoomStart = 3, rtl = false, viewportWidth = 100, contentWidth = 102) shouldBe -2f
        }

        // --- Zoom and pan of a page ---

        private fun zoom(content: IntSize, viewport: IntSize = IntSize(100, 100)) = PageZoom().apply {
            this.viewport = viewport
            contentSize = content
        }

        @Test
        fun `a page smaller than the window is centered, a larger one keeps covering it`() {
            PageZoom.clamp(Offset(30f, 30f), IntSize(100, 100), IntSize(50, 60)) shouldBe Offset(25f, 20f)
            PageZoom.clamp(Offset(30f, -500f), IntSize(100, 100), IntSize(300, 300)) shouldBe Offset(0f, -200f)
            PageZoom.clamp(Offset(-50f, -50f), IntSize(100, 100), IntSize(300, 300)) shouldBe Offset(-50f, -50f)
        }

        @Test
        fun `zoom stays between its limits, and disable zoom in stops it at the fit`() {
            val z = zoom(IntSize(100, 100))
            z.zoomTo(100f)
            z.zoom shouldBe PageZoom.MAX_ZOOM
            z.zoomTo(0.01f)
            z.zoom shouldBe PageZoom.MIN_ZOOM
            z.maxZoom = 1f
            z.zoomTo(2f)
            z.zoom shouldBe 1f
            // A limit below the minimum still allows the minimum
            z.maxZoom = 0.1f
            z.zoomTo(3f)
            z.zoom shouldBe PageZoom.MIN_ZOOM
        }

        @Test
        fun `zoom keeps the point under the anchor in place`() {
            val z = zoom(IntSize(100, 100))
            z.zoomTo(2f, anchor = Offset(20f, 40f))
            // The page's top left moves away from the anchor by the zoom factor
            z.pan shouldBe Offset(20f - 20f * 2, 40f - 40f * 2)
        }

        @Test
        fun `the zoom keys step by a quarter and reset goes back to the fit`() {
            val z = zoom(IntSize(100, 100))
            z.zoomTo(z.zoom * 1.25f)
            z.zoom shouldBe 1.25f
            z.zoomTo(z.zoom / 1.25f)
            z.zoom shouldBe 1f
            z.zoomTo(3f)
            z.pan = Offset(-10f, -10f)
            z.reset()
            z.zoom shouldBe 1f
            z.pan shouldBe Offset.Zero
        }

        @Test
        fun `a double click zooms in to twice the size, and another goes back to the fit`() {
            val z = zoom(IntSize(100, 100))
            z.toggleDoubleTapZoom(Offset(50f, 50f))
            z.zoom shouldBe 2f
            z.toggleDoubleTapZoom(Offset(50f, 50f))
            z.zoom shouldBe 1f
            z.pan shouldBe Offset.Zero
            // A zoom just above the fit counts as the fit
            z.zoomTo(1.01f)
            z.toggleDoubleTapZoom(Offset.Zero)
            z.zoom shouldBe 2f
        }

        @Test
        fun `a pan returns the part that hit the edge`() {
            val z = zoom(IntSize(300, 300))
            z.panBy(Offset(-150f, 0f)) shouldBe Offset.Zero
            z.panBy(Offset(-150f, 0f)) shouldBe Offset(-100f, 0f)
            z.pan shouldBe Offset(-200f, 0f)
        }

        @Test
        fun `down and up pan a tall page and turn only at its edge`() {
            val tall = zoom(IntSize(100, 300))
            tall.panVertically(down = false) shouldBe false
            tall.panVertically(down = true) shouldBe true
            tall.pan.y shouldBe -80f
            tall.panVertically(down = true) shouldBe true
            tall.pan.y shouldBe -160f
            // As it is: the press that reaches the bottom both pans the rest of the way and turns
            tall.panVertically(down = true) shouldBe false
            tall.pan.y shouldBe -200f
            tall.panVertically(down = true) shouldBe false

            val fits = zoom(IntSize(100, 100))
            fits.panVertically(down = true) shouldBe false
            fits.pan shouldBe Offset.Zero
        }

        @Test
        fun `pan wide images when tapping pans towards the tap and turns at the edge`() {
            val wide = zoom(IntSize(300, 100))
            wide.panTowards(dx = 1, dy = 0) shouldBe true
            wide.pan.x shouldBe -80f
            wide.panTowards(dx = 1, dy = 0) shouldBe true
            // A pan that reaches the edge part of the way does not turn yet
            wide.panTowards(dx = 1, dy = 0) shouldBe true
            wide.pan.x shouldBe -200f
            wide.panTowards(dx = 1, dy = 0) shouldBe false
            // Back the other way, then nothing left at the start
            wide.panTowards(dx = -1, dy = 0) shouldBe true
            // A wide page does not pan up or down
            wide.panTowards(dx = 0, dy = 1) shouldBe false

            val fits = zoom(IntSize(100, 100))
            fits.panTowards(dx = 1, dy = 0) shouldBe false
        }
    }

    /**
     * Turning a page in [PagedViewer] updates the reading position without recomposing [PagedViewer]
     * when [ViewerContent] inputs have not changed.
     */
    @OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
    @Nested
    inner class Recomposition {

        private val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        private val app = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val models = mutableListOf<ReaderScreenModel>()

        @BeforeEach
        fun setUp() {
            Dispatchers.setMain(main)
        }

        @AfterEach
        fun tearDown() {
            try {
                models.forEach { it.onDispose() }
                runBlocking {
                    withTimeout(5_000) {
                        models.forEach { model ->
                            val job = model.screenModelScope.coroutineContext[Job]!!
                            job.cancelChildren()
                            job.children.toList().joinAll()
                        }
                        app.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
                models.clear()
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
        }

        private fun pngBytes(rgb: Int): ByteArray = ByteArrayOutputStream().also { out ->
            val img = BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 60) for (x in 0 until 40) img.setRGB(x, y, rgb)
            ImageIO.write(img, "png", out)
        }.toByteArray()

        @Composable
        private fun TrackedPagedViewer(
            model: ReaderScreenModel,
            content: ViewerContent,
            actions: ReaderActions,
            compositions: AtomicInteger,
        ) {
            SideEffect { compositions.incrementAndGet() }
            PagedViewer(
                model = model,
                content = content,
                actions = actions,
                fitMode = FitModes.FIT_SCREEN,
                background = Color.Black,
                foreground = Color.White,
            )
        }

        @Test
        fun `turning a page does not recompose PagedViewer when ViewerContent is unchanged`() =
            runDesktopComposeUiTest(width = 800, height = 600, effectContext = e2eEffectContext()) {
                val store = TestPreferenceStore()
                val prefs = DesktopPreferences(store).apply {
                    pageLayout().set(0)
                    pageTransitionsPager().set(false)
                    showNavigationOverlayNewUser().set(false)
                    defaultReadingMode().set(ReadingModes.RIGHT_TO_LEFT)
                }
                val manga = Manga.create().copy(id = 1, source = 4242, initialized = true)
                val chapter = Chapter.create().copy(id = 10, mangaId = 1, name = "Chapter 1", url = "/c/1")
                val pages = listOf(
                    Page(0, imageUrl = "https://example.invalid/1.png"),
                    Page(1, imageUrl = "https://example.invalid/2.png"),
                    Page(2, imageUrl = "https://example.invalid/3.png"),
                )
                val bytes = listOf(pngBytes(0xFF0000), pngBytes(0x00FF00), pngBytes(0x0000FF))
                val source = mockk<HttpSource>(relaxed = true) {
                    coEvery { getPageList(any()) } returns pages
                    coEvery { getImage(any()) } answers {
                        val page = firstArg<Page>()
                        Response.Builder()
                            .request(Request.Builder().url(page.imageUrl!!).build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(bytes[page.index].toResponseBody("image/png".toMediaType()))
                            .build()
                    }
                }
                val model = ReaderScreenModel(
                    mangaId = manga.id,
                    initialChapterId = chapter.id,
                    getManga = mockk { coEvery { await(manga.id) } returns manga },
                    getChapters = mockk { coEvery { await(manga.id) } returns listOf(chapter) },
                    sourceManager = mockk { every { get(any<Long>()) } returns source },
                    updateChapter = mockk(relaxed = true),
                    upsertHistory = mockk(relaxed = true),
                    mangaRepository = mockk(relaxed = true),
                    downloadManager = mockk<DownloadManager>(relaxed = true) {
                        every { findChapter(any(), any()) } returns null
                        every { isDownloaded(any(), any()) } returns false
                    },
                    cbzPassword = mockk(relaxed = true),
                    downloadPreferences = DownloadPreferences(store),
                    libraryPreferences = LibraryPreferences(store),
                    preferences = prefs,
                    incognitoPolicy = lazyOf(mockk { every { isOn(any()) } returns true }),
                    syncManager = lazyOf(mockk(relaxed = true)),
                    trackChapters = lazyOf(mockk(relaxed = true)),
                    appScope = app,
                ).also { models += it }

                runBlocking { withTimeout(5_000) { model.state.first { it.pages.size == 3 && !it.loading } } }

                val actions = ReaderActions()
                val viewerCompositions = AtomicInteger(0)
                var latestPageText = ""
                setContent {
                    val state by model.state.collectAsState()
                    latestPageText = state.pageNumber
                    val content = remember(
                        state.pages,
                        state.chapter,
                        state.previousChapter,
                        state.nextChapter,
                        state.readingMode,
                        state.startPage,
                        state.widePages,
                        state.shiftDoublePages,
                        state.generation,
                    ) {
                        ViewerContent(
                            pages = state.pages,
                            chapter = state.chapter,
                            previousChapter = state.previousChapter,
                            nextChapter = state.nextChapter,
                            readingMode = state.readingMode,
                            startPage = state.startPage,
                            widePages = state.widePages,
                            shiftDoublePages = state.shiftDoublePages,
                            generation = state.generation,
                        )
                    }
                    TrackedPagedViewer(model, content, actions, viewerCompositions)
                }

                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() &&
                        model.state.value.pages[0].state.value is ReaderPage.State.Ready
                }
                waitForIdle()
                val beforeTurn = viewerCompositions.get()
                beforeTurn shouldBeGreaterThan 0

                runOnUiThread { actions.next() }
                waitUntil(timeoutMillis = 5_000) { model.state.value.currentPage == 1 }
                waitForIdle()
                latestPageText shouldBe "2 / 3"
                viewerCompositions.get() shouldBe beforeTurn

                // Verify the counter does increment when ViewerContent actually changes
                model.toggleShiftDoublePages()
                waitUntil(timeoutMillis = 5_000) { viewerCompositions.get() > beforeTurn }
            }
    }

    @Nested
    inner class ZoomController {

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

    /** The paged reader's mouse wheel: a step turns the page, Ctrl/Cmd + wheel zooms. */
    @Nested
    inner class MouseWheel {

        @Test
        fun `the wheel turns a page and ctrl or cmd plus the wheel zooms`() {
            PagedWheel.turn(deltaY = 1f, sinceLastTurnMs = 1_000) shouldBe 1
            PagedWheel.turn(deltaY = -1f, sinceLastTurnMs = 1_000) shouldBe -1
            // One flick does not skip pages, and a tiny step is no turn
            PagedWheel.turn(deltaY = 1f, sinceLastTurnMs = 100) shouldBe 0
            PagedWheel.turn(deltaY = 0.05f, sinceLastTurnMs = 1_000) shouldBe 0

            PagedWheel.zooms(ctrl = true, meta = false) shouldBe true
            PagedWheel.zooms(ctrl = false, meta = true) shouldBe true
            PagedWheel.zooms(ctrl = false, meta = false) shouldBe false
            PagedWheel.zoomFactor(-1f) shouldBeGreaterThan 1f
            PagedWheel.zoomFactor(1f) shouldBeLessThan 1f
        }
    }

    @Nested
    inner class Spreads {

        private fun pages(spreads: List<Spread>) = spreads.map { it.pages }

        @Test
        fun `single pages one by one`() {
            pages(ReaderSpreads.build(3, double = false)) shouldBe listOf(listOf(0), listOf(1), listOf(2))
        }

        @Test
        fun `double pages keep the cover alone, and shifting pairs from the first page`() {
            pages(ReaderSpreads.build(6, double = true)) shouldBe listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5))
            pages(ReaderSpreads.build(6, double = true, shift = true)) shouldBe listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5))
        }

        @Test
        fun `wide pages stand alone in double page mode`() {
            pages(ReaderSpreads.build(6, double = true, wide = setOf(2))) shouldBe
                listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5))
        }

        @Test
        fun `split wide pages become two halves`() {
            val spreads = ReaderSpreads.build(3, double = false, wide = setOf(1), split = true)
            spreads.map { it.pages to it.part } shouldBe listOf(
                listOf(0) to PagePart.WHOLE,
                listOf(1) to PagePart.FIRST_HALF,
                listOf(1) to PagePart.SECOND_HALF,
                listOf(2) to PagePart.WHOLE,
            )
        }

        @Test
        fun `the half read first follows the reading direction`() {
            ReaderSpreads.leftHalfFirst(rtl = false, invert = false) shouldBe true
            ReaderSpreads.leftHalfFirst(rtl = true, invert = false) shouldBe false
            ReaderSpreads.leftHalfFirst(rtl = true, invert = true) shouldBe true
        }
    }

    @Nested
    inner class ScaleAndBackground {

        private fun store() = DesktopPreferenceStore(JavaSharedPreferences("reader_scale_test_${System.nanoTime()}"))

        @Test
        fun `scale types match Android's numbering and SubsamplingScaleImageView's scales`() {
            // A 1000 x 2000 page in a 1600 x 1000 window
            fun scale(mode: Int) = FitModes.scale(mode, 1000, 2000, 1600, 1000, original = 1.5f)
            scale(FitModes.FIT_SCREEN) shouldBe 0.5f
            scale(FitModes.STRETCH) shouldBe 1.6f
            scale(FitModes.FIT_WIDTH) shouldBe 1.6f
            scale(FitModes.FIT_HEIGHT) shouldBe 0.5f
            scale(FitModes.ORIGINAL) shouldBe 1.5f
            // Smart fit: a tall page fits the width, a wide one the height
            scale(FitModes.SMART_FIT) shouldBe 1.6f
            FitModes.scale(FitModes.SMART_FIT, 2000, 1000, 1600, 1000, original = 1f) shouldBe 1f
            FitModes.all shouldBe listOf(1, 2, 3, 4, 5, 6)
        }

        @Test
        fun `the old desktop fit mode moves to imageScaleType once`() {
            val store = store()
            store.getInt("zoukan_reader_fit_mode", 0).set(2)
            val preferences = DesktopPreferences(store)
            preferences.imageScaleType().get() shouldBe FitModes.FIT_HEIGHT
            store.getInt("zoukan_reader_fit_mode", -1).isSet() shouldBe false
            DesktopPreferences(store).imageScaleType().get() shouldBe FitModes.FIT_HEIGHT
            DesktopPreferences(store()).imageScaleType().get() shouldBe FitModes.FIT_SCREEN
        }

        @Test
        fun `webtoon smart scale narrows the strip to the ratio on a wider window`() {
            WebtoonScaleTypes.scale(WebtoonScaleTypes.FIT, 1600f, 900f) shouldBe 1f
            // 16:9 in a 1600 x 900 window: a 506.25 wide strip
            WebtoonScaleTypes.scale("R16_9", 1600f, 900f) shouldBe 900f * 9f / 16f / 1600f
            // A window narrower than the ratio is left alone
            WebtoonScaleTypes.scale("R4_3", 600f, 900f) shouldBe 1f
        }

        @Test
        fun `automatic background reads the page's margins`() {
            val black = 0xFF000000.toInt()
            val white = 0xFFFFFFFF.toInt()
            val red = 0xFFC02020.toInt()
            PageBackground.of(400, 600) { _, _ -> white }.portrait shouldBe listOf(white)
            PageBackground.of(400, 600) { _, _ -> black }.portrait shouldBe listOf(black)
            // A plain colored page takes its color
            PageBackground.of(400, 600) { _, _ -> red }.portrait shouldBe listOf(red)
            // Black on top, white below: a gradient in a tall window, one color in a wide one
            val split = PageBackground.of(400, 600) { _, y -> if (y < 300) black else white }
            split.portrait shouldBe listOf(black, black, white, white)
            split.landscape.size shouldBe 1
            // Too small to tell
            PageBackground.of(20, 20) { _, _ -> black }.portrait shouldBe listOf(white)
        }

        @Test
        fun `tap zones overlay shows on start only when asked, and when the zones change`() {
            val zones = ReaderNavigation.zones(ReaderNavigation.DEFAULT, verticalViewer = false)
            val other = ReaderNavigation.zones(ReaderNavigation.L_SHAPED, verticalViewer = false)
            val store = store()
            val preferences = DesktopPreferences(store)
            // The first reader ever shows them, as Android does for a new user
            TapZonesOverlayState(preferences).apply { onZones(zones) }.visible shouldBe true
            // After that only with "Show tap zones overlay"
            val quiet = TapZonesOverlayState(preferences).apply { onZones(zones) }
            quiet.visible shouldBe false
            quiet.onZones(zones)
            quiet.visible shouldBe false
            quiet.onZones(other)
            quiet.visible shouldBe true
            quiet.hide()
            quiet.visible shouldBe false
            preferences.showNavigationOverlayOnStart().set(true)
            TapZonesOverlayState(preferences).apply { onZones(zones) }.visible shouldBe true
            // Disabled tap zones never show
            TapZonesOverlayState(preferences).apply { onZones(emptyList()) }.visible shouldBe false
        }
    }

    /** Animated GIF pages play in the reader, as on Android; still pages stay one bitmap. */
    @Nested
    inner class AnimatedPages {

        /** A GIF of [colors] frames, each 8x6 and [delayCs] hundredths of a second, looping forever. */
        private fun gif(colors: List<java.awt.Color>, delayCs: Int = 5): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                colors.forEachIndexed { i, color ->
                    val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.color = color
                    g.fillRect(0, 0, 8, 6)
                    g.dispose()
                    val type = ImageTypeSpecifier.createFromRenderedImage(image)
                    val metadata = writer.getDefaultImageMetadata(type, writer.defaultWriteParam)
                    val format = metadata.nativeMetadataFormatName
                    val root = metadata.getAsTree(format) as IIOMetadataNode
                    val control = IIOMetadataNode("GraphicControlExtension").apply {
                        setAttribute("disposalMethod", "none")
                        setAttribute("userInputFlag", "FALSE")
                        setAttribute("transparentColorFlag", "FALSE")
                        setAttribute("delayTime", delayCs.toString())
                        setAttribute("transparentColorIndex", "0")
                    }
                    root.appendChild(control)
                    if (i == 0) {
                        val loop = IIOMetadataNode("ApplicationExtension").apply {
                            setAttribute("applicationID", "NETSCAPE")
                            setAttribute("authenticationCode", "2.0")
                            userObject = byteArrayOf(1, 0, 0)
                        }
                        root.appendChild(IIOMetadataNode("ApplicationExtensions").apply { appendChild(loop) })
                    }
                    metadata.setFromTree(format, root)
                    writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
                }
                writer.endWriteSequence()
            }
            writer.dispose()
            return out.toByteArray()
        }

        private fun PageImage.pixel(x: Int, y: Int): Int {
            val pixels = IntArray(1)
            slices.single().readPixels(pixels, x, y, 1, 1)
            return pixels[0] and 0xFFFFFF
        }

        @Test
        fun `an animated GIF page plays its frames in order and loops`() {
            val page = PageImage.decode(gif(listOf(java.awt.Color.RED, java.awt.Color.GREEN, java.awt.Color.BLUE)))
            val animation = page.animation.shouldNotBeNull()
            animation.frameCount shouldBe 3
            page.width shouldBe 8
            page.height shouldBe 6
            page.pixel(2, 2) shouldBe 0xFF0000

            val first = page.slices.single()
            animation.advance() shouldBe true
            animation.frameIndex shouldBe 1
            page.pixel(2, 2) shouldBe 0x00FF00
            // A new frame is a new value, so what draws it redraws
            (page.slices.single() !== first) shouldBe true

            animation.advance() shouldBe true
            page.pixel(2, 2) shouldBe 0x0000FF
            // Loops forever: back to the first frame
            animation.advance() shouldBe true
            animation.frameIndex shouldBe 0
            page.pixel(2, 2) shouldBe 0xFF0000
        }

        @Test
        fun `a one-frame GIF is a still page`() {
            val page = PageImage.decode(gif(listOf(java.awt.Color.RED)))
            page.animation.shouldBeNull()
            page.slices.size shouldBe 1
        }

        @Test
        fun `frame timing follows the durations and the repetition count`() {
            val once = FrameTimeline(intArrayOf(50, 0, 200), repetitions = 0)
            once.delayMillis(0) shouldBe 50
            // Durations of 10 ms or less are slowed to 100 ms, as browsers and Android do
            once.delayMillis(1) shouldBe 100
            once.delayMillis(2) shouldBe 200
            once.next(0, 0) shouldBe (1 to 0)
            once.next(2, 0).shouldBeNull()

            val twice = FrameTimeline(intArrayOf(50, 50), repetitions = 1)
            twice.next(1, 0) shouldBe (0 to 1)
            twice.next(1, 1).shouldBeNull()

            val forever = FrameTimeline(intArrayOf(50, 50), repetitions = -1)
            forever.next(1, 1000) shouldBe (0 to 1001)
        }

        @Test
        fun `frames build on the held frame only when Skia allows it`() {
            val required = intArrayOf(-1, 0, 1, 1)
            val restoresPrevious = booleanArrayOf(false, false, true, false)
            // An independent frame decodes from scratch
            FrameTimeline.priorFrame(0, 3, required, restoresPrevious) shouldBe -1
            FrameTimeline.priorFrame(1, 0, required, restoresPrevious) shouldBe 0
            FrameTimeline.priorFrame(2, 1, required, restoresPrevious) shouldBe 1
            // The held frame restores the one before it: not a base to draw on
            FrameTimeline.priorFrame(3, 2, required, restoresPrevious) shouldBe -1
            // Held frame before the required one, or after the frame itself
            FrameTimeline.priorFrame(2, 0, required, restoresPrevious) shouldBe -1
            FrameTimeline.priorFrame(1, 3, required, restoresPrevious) shouldBe -1
            FrameTimeline.priorFrame(1, -1, required, restoresPrevious) shouldBe -1
        }
    }
}
