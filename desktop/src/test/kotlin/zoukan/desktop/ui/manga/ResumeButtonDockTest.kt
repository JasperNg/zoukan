package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The Resume button docks to the side its center was dropped on, as on Android. */
class ResumeButtonDockTest {

    @Test
    fun `docks to the half the button's center ends on`() {
        // A 200 px button in a 1000 px area: its center crosses the middle at x = 400
        docksAtStart(buttonX = 0f, buttonWidth = 200, areaWidth = 1000) shouldBe true
        docksAtStart(buttonX = 399f, buttonWidth = 200, areaWidth = 1000) shouldBe true
        docksAtStart(buttonX = 400f, buttonWidth = 200, areaWidth = 1000) shouldBe false
        docksAtStart(buttonX = 800f, buttonWidth = 200, areaWidth = 1000) shouldBe false
    }
}
