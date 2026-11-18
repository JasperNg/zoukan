package zoukan.desktop.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.PagedPager.Turn
import zoukan.desktop.ui.reader.ReaderNavigation.Region

/**
 * The paged reader's decisions ([PagedPager]) and the zoom of a page ([PageZoom]): page turns in
 * both reading directions, the chapter's ends and transitions, zoom and pan, and double pages.
 * Pager index 0 and spreadCount + 1 are the chapter transitions.
 */
class PagedViewerTest {

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
