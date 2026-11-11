package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Rectangle

class WindowBoundsTest {

    private val screen = listOf(Rectangle(0, 0, 1920, 1080))

    @Test
    fun `bounds survive saving`() {
        val bounds = WindowBounds(-1200, 40, 1100, 700, maximized = true)
        WindowBounds.decode(bounds.encode()) shouldBe bounds
    }

    @Test
    fun `bad or too small bounds are not used`() {
        WindowBounds.decode("") shouldBe null
        WindowBounds.decode("1,2,3") shouldBe null
        WindowBounds.decode("0,0,wide,700,0") shouldBe null
        WindowBounds.decode("0,0,300,200,0") shouldBe null
    }

    @Test
    fun `a window on a screen that is gone loses its position`() {
        WindowBounds(100, 100, 1280, 860, false).onScreen(screen) shouldBe WindowBounds(100, 100, 1280, 860, false)
        // On a second monitor to the left that is no longer there
        WindowBounds(-1800, 100, 1280, 860, false).onScreen(screen) shouldBe null
        // Only a sliver of the title bar would show
        WindowBounds(1900, 100, 1280, 860, false).onScreen(screen) shouldBe null
        // Title bar above the top of the screen
        WindowBounds(100, -200, 1280, 860, false).onScreen(screen) shouldBe null
    }
}
