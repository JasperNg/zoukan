package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.ReaderNavigation.Invert
import zoukan.desktop.ui.reader.ReaderNavigation.Region

class ReaderNavigationTest {
    private fun at(layout: Int, x: Float, y: Float, vertical: Boolean = false, invert: Invert = Invert.NONE, smaller: Boolean = false) =
        ReaderNavigation.regionAt(x, y, ReaderNavigation.zones(layout, vertical, invert, smaller))

    @Test
    fun `default is right and left in the horizontal modes and L shaped in the vertical ones`() {
        at(ReaderNavigation.DEFAULT, 0.1f, 0.5f) shouldBe Region.LEFT
        at(ReaderNavigation.DEFAULT, 0.9f, 0.5f) shouldBe Region.RIGHT
        at(ReaderNavigation.DEFAULT, 0.5f, 0.5f) shouldBe Region.MENU
        at(ReaderNavigation.DEFAULT, 0.5f, 0.1f, vertical = true) shouldBe Region.PREV
        at(ReaderNavigation.DEFAULT, 0.5f, 0.9f, vertical = true) shouldBe Region.NEXT
    }

    @Test
    fun `L shaped, Kindle-ish and Edge match Android's maps`() {
        at(ReaderNavigation.L_SHAPED, 0.1f, 0.5f) shouldBe Region.PREV
        at(ReaderNavigation.L_SHAPED, 0.9f, 0.5f) shouldBe Region.NEXT
        at(ReaderNavigation.L_SHAPED, 0.5f, 0.5f) shouldBe Region.MENU
        at(ReaderNavigation.KINDLISH, 0.5f, 0.1f) shouldBe Region.MENU
        at(ReaderNavigation.KINDLISH, 0.1f, 0.5f) shouldBe Region.PREV
        at(ReaderNavigation.KINDLISH, 0.6f, 0.6f) shouldBe Region.NEXT
        at(ReaderNavigation.EDGE, 0.1f, 0.5f) shouldBe Region.NEXT
        at(ReaderNavigation.EDGE, 0.5f, 0.9f) shouldBe Region.PREV
        at(ReaderNavigation.EDGE, 0.5f, 0.5f) shouldBe Region.MENU
        at(ReaderNavigation.DISABLED, 0.1f, 0.5f) shouldBe Region.MENU
    }

    @Test
    fun `inverting mirrors the zones`() {
        at(ReaderNavigation.L_SHAPED, 0.5f, 0.1f, invert = Invert.VERTICAL) shouldBe Region.NEXT
        at(ReaderNavigation.L_SHAPED, 0.1f, 0.5f, invert = Invert.HORIZONTAL) shouldBe Region.NEXT
        at(ReaderNavigation.RIGHT_AND_LEFT, 0.1f, 0.5f, invert = Invert.BOTH) shouldBe Region.RIGHT
        Invert.of("nonsense") shouldBe Invert.NONE
    }

    @Test
    fun `smaller tap zones leave more of the middle to the menu`() {
        at(ReaderNavigation.RIGHT_AND_LEFT, 0.3f, 0.5f) shouldBe Region.LEFT
        at(ReaderNavigation.RIGHT_AND_LEFT, 0.3f, 0.5f, smaller = true) shouldBe Region.MENU
    }

    @Test
    fun `the horizontal keys flip with right to left and with the inverted tap zones`() {
        ReaderNavigation.flipped(rtl = false) shouldBe false
        ReaderNavigation.flipped(rtl = true) shouldBe true
        ReaderNavigation.flipped(rtl = false, invert = Invert.HORIZONTAL) shouldBe true
        ReaderNavigation.flipped(rtl = true, invert = Invert.VERTICAL) shouldBe true
        ReaderNavigation.flipped(rtl = false, invert = Invert.BOTH) shouldBe true
        // An inverted flip undoes right to left: the directions follow the taps either way
        ReaderNavigation.flipped(rtl = true, invert = Invert.HORIZONTAL) shouldBe false
    }

    @Test
    fun `the vertical keys flip with the vertically inverted tap zones`() {
        ReaderNavigation.verticalFlipped() shouldBe false
        ReaderNavigation.verticalFlipped(invert = Invert.VERTICAL) shouldBe true
        ReaderNavigation.verticalFlipped(invert = Invert.BOTH) shouldBe true
        ReaderNavigation.verticalFlipped(invert = Invert.HORIZONTAL) shouldBe false
        ReaderNavigation.verticalFlipped(invert = Invert.NONE) shouldBe false
    }
}
