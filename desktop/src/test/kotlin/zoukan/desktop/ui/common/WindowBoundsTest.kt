package zoukan.desktop.ui.common

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
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
    fun `the default window fits a small screen`() {
        val state = WindowBounds.initialState("", listOf(Rectangle(0, 0, 1280, 680)))
        (state.size.height.value <= 680f) shouldBe true
        (state.size.width.value <= 1280f) shouldBe true
    }

    @Test
    fun `a window saved on a large monitor is clamped to the laptop screen`() {
        val saved = WindowBounds(0, 0, 2560, 1400, false).encode()
        val state = WindowBounds.initialState(saved, listOf(Rectangle(0, 0, 1366, 728)))
        (state.size.width.value <= 1366f) shouldBe true
        (state.size.height.value <= 728f) shouldBe true
        state.position shouldBe WindowPosition(0.dp, 0.dp)
    }

    @Test
    fun `a maximized window keeps its placement`() {
        val saved = WindowBounds(0, 0, 1280, 860, true).encode()
        WindowBounds.initialState(saved, screen).placement shouldBe WindowPlacement.Maximized
    }

    @Test
    fun `the minimum size never exceeds a small screen`() {
        val small = WindowBounds.minimumSize(Rectangle(0, 0, 853, 480))
        small.width shouldBe 800
        small.height shouldBe 480
        val tiny = WindowBounds.minimumSize(Rectangle(0, 0, 700, 400))
        tiny.width shouldBe 700
        WindowBounds.minimumSize(null) shouldBe java.awt.Dimension(WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT)
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
