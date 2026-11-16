package zoukan.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

@OptIn(ExperimentalTestApi::class)
class ScreenshotsTest {

    @Test
    fun `the screenshot shows a dialog over the window`(): Unit = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Red))
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(100.dp).background(Color.Blue))
                    }
                },
            )
        }
        waitUntil { onAllNodes(isRoot()).fetchSemanticsNodes().size >= 2 }

        val image = captureAllRoots()

        image.type shouldBe BufferedImage.TYPE_INT_ARGB
        image.width shouldBe 400
        image.height shouldBe 300
        // A scrim may darken the window, so compare the dominant channel, not exact colours
        image.colourAt(1, 1) shouldBe "red"
        // The blue box sits at the dialog's centre
        val dialog = onAllNodes(isRoot()).fetchSemanticsNodes()[1].boundsInWindow
        image.colourAt(dialog.center.x.toInt(), dialog.center.y.toInt()) shouldBe "blue"
    }

    /** "red", "green" or "blue" when that channel clearly dominates the pixel, else its hex value. */
    private fun BufferedImage.colourAt(x: Int, y: Int): String {
        val rgb = getRGB(x, y)
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        return when {
            red > 2 * maxOf(green, blue) + 32 -> "red"
            green > 2 * maxOf(red, blue) + 32 -> "green"
            blue > 2 * maxOf(red, green) + 32 -> "blue"
            else -> "%06X at %d,%d".format(rgb and 0xFFFFFF, x, y)
        }
    }
}
