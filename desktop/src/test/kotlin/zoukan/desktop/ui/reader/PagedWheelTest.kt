package zoukan.desktop.ui.reader

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The paged reader's mouse wheel: a step turns the page, Ctrl/Cmd + wheel zooms. */
class PagedWheelTest {

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
