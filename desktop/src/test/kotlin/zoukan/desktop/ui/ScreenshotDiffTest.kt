package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

class ScreenshotDiffTest {
    private fun image(width: Int = 400, height: Int = 300, color: Color = Color(200, 210, 220)): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = color
            g.fillRect(0, 0, width, height)
            g.dispose()
        }

    private fun BufferedImage.block(x: Int, y: Int, size: Int, color: Color) {
        val g = createGraphics()
        g.color = color
        g.fillRect(x, y, size, size)
        g.dispose()
    }

    @Test
    fun `identical images pass`() {
        val result = ScreenshotDiff.compare(image(), image(), masks = emptyList())
        result.passed shouldBe true
        result.changedPixels shouldBe 0
        result.diff shouldBe null
    }

    @Test
    fun `one pixel off by 20 is within the changed share`() {
        val actual = image().apply { setRGB(5, 5, Color(220, 210, 220).rgb) }
        val result = ScreenshotDiff.compare(image(), actual, masks = emptyList())
        result.changedPixels shouldBe 1
        result.passed shouldBe true
    }

    @Test
    fun `a difference within the channel tolerance is not a change`() {
        val actual = image(color = Color(205, 215, 225))
        val result = ScreenshotDiff.compare(image(), actual, masks = emptyList())
        result.changedPixels shouldBe 0
        result.passed shouldBe true
    }

    @Test
    fun `a changed block fails and the diff image is red there`() {
        val actual = image().apply { block(100, 100, 50, Color(10, 10, 10)) }
        val result = ScreenshotDiff.compare(image(), actual, masks = emptyList())
        result.passed shouldBe false
        result.changedPixels shouldBe 2500
        result.diff?.getRGB(120, 120) shouldBe 0xFFFF0000.toInt()
        // Outside the block it is the dimmed actual, not red
        (result.diff?.getRGB(10, 10) == 0xFFFF0000.toInt()) shouldBe false
    }

    @Test
    fun `the same block inside a mask passes`() {
        val actual = image().apply { block(100, 100, 50, Color(10, 10, 10)) }
        val result = ScreenshotDiff.compare(image(), actual, masks = listOf(Rectangle(90, 90, 80, 80)))
        result.passed shouldBe true
        result.changedPixels shouldBe 0
    }

    @Test
    fun `a mask outside the image is ignored`() {
        val actual = image().apply { block(100, 100, 50, Color(10, 10, 10)) }
        val result = ScreenshotDiff.compare(image(), actual, masks = listOf(Rectangle(1000, 1000, 50, 50)))
        result.passed shouldBe false
    }

    @Test
    fun `different sizes fail with a size message`() {
        val result = ScreenshotDiff.compare(image(), image(width = 401), masks = emptyList())
        result.passed shouldBe false
        result.message shouldContain "size differs"
        result.message shouldContain "400x300"
        result.message shouldContain "401x300"
    }

    @Test
    fun `a failure writes the expected, actual and diff images`() {
        val dir = kotlin.io.path.createTempDirectory("shot-diff").toFile()
        try {
            val expected = image()
            val actual = image().apply { block(0, 0, 50, Color.BLACK) }
            val result = ScreenshotDiff.compare(expected, actual, masks = emptyList())
            ScreenshotDiff.writeFailure(dir, expected, actual, result)
            listOf("expected.png", "actual.png", "diff.png").forEach { java.io.File(dir, it).isFile shouldBe true }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a curated key that was never taken is a finding`() {
        val dir = kotlin.io.path.createTempDirectory("shot-run").toFile()
        try {
            val run = ScreenshotRun(verify = false, os = ScreenshotRun.LINUX, moduleDir = dir)
            run.check(image(), "1280x820/light", "tab:Library", masks = emptyList())
            val missing = run.notTaken(listOf("tab:Library", "tab:Updates"), listOf("1280x820/light", "1280x820/dark"))
            missing.map { "${it.variant} ${it.key}" } shouldBe
                listOf("1280x820/light tab:Updates", "1280x820/dark tab:Library", "1280x820/dark tab:Updates")
        } finally {
            dir.deleteRecursively()
        }
    }
}
