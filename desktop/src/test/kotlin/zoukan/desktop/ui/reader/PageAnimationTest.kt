package zoukan.desktop.ui.reader

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/** Animated GIF pages play in the reader, as on Android; still pages stay one bitmap. */
class PageAnimationTest {

    /** A GIF of [colors] frames, each 8x6 and [delayCs] hundredths of a second, looping forever. */
    private fun gif(colors: List<Color>, delayCs: Int = 5): ByteArray {
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
        val page = PageImage.decode(gif(listOf(Color.RED, Color.GREEN, Color.BLUE)))
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
        val page = PageImage.decode(gif(listOf(Color.RED)))
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
