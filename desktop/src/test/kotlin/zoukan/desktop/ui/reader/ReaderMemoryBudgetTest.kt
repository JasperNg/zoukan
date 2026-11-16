package zoukan.desktop.ui.reader

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ReaderMemoryBudgetTest {
    @Test
    fun `the decoded pages kept stay under the pixel budget across a chapter`() {
        val pixels = { _: Int -> 9_600_000L }
        for (center in 0 until 30) {
            val kept = PageWindow.kept(30, center, behind = 4, ahead = 12, visible = center..center, pixels = pixels)
            kept.sumOf { pixels(it) } shouldBeLessThanOrEqual PageWindow.BUDGET_PIXELS
            kept shouldContain center
        }
    }

    @Test
    fun `pages on screen are kept even over the budget, and nothing else is`() {
        // Zoomed out on large pages: four pages on screen already pass the budget
        val pixels = { _: Int -> 24_000_000L }
        val visible = 10..13
        val kept = PageWindow.kept(30, center = 10, behind = 4, ahead = 12, visible = visible, pixels = pixels)
        kept shouldBe visible.toSet()
    }

    @Test
    fun `a page far wider than the window is decoded smaller`() {
        val source = BufferedImage(4000, 2000, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(source, "png", out)
        val image = PageImage.decode(out.toByteArray(), maxWidth = 1000)
        image.width shouldBe 1000
        image.height shouldBe 500
    }
}
