package zoukan.desktop.image

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.PageImage
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * AVIF pages and covers (Atsumaru serves only AVIF) decode on desktop, where Skia and ImageIO
 * alone cannot read them.
 */
class FfmpegImageDecoderTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/images/$name")!!.use { it.readBytes() }

    private fun assertColor(argb: Int, r: Int, g: Int, b: Int) {
        val (ar, ag, ab) = listOf(16, 8, 0).map { (argb shr it) and 0xFF }
        (abs(ar - r) <= 8 && abs(ag - g) <= 8 && abs(ab - b) <= 8) shouldBe true
        (argb ushr 24) shouldBe 0xFF
    }

    @Test
    fun `decodes an AVIF with its size and colors`() {
        val image = FfmpegImageDecoder.decode(fixture("red-32x24.avif"))
        image.width shouldBe 32
        image.height shouldBe 24
        assertColor(image.getRGB(5, 5), 0xFF, 0, 0)

        val subsampled = FfmpegImageDecoder.decode(fixture("blue-16x40.avif"))
        subsampled.width shouldBe 16
        subsampled.height shouldBe 40
        assertColor(subsampled.getRGB(8, 20), 0, 0, 0xFF)
    }

    @Test
    fun `recognizes AVIF and HEIF by their brand only`() {
        FfmpegImageDecoder.handles(fixture("red-32x24.avif")) shouldBe true
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        FfmpegImageDecoder.handles(png) shouldBe false
        FfmpegImageDecoder.handles(ByteArray(4)) shouldBe false
    }

    @Test
    fun `ImageIO reads AVIF through the registered reader, with source regions`() {
        val bytes = fixture("blue-16x40.avif")
        ImageIO.read(ByteArrayInputStream(bytes)) shouldNotBe null

        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            reader.input = input
            reader.getWidth(0) shouldBe 16
            reader.getHeight(0) shouldBe 40
            val part = reader.read(0, reader.defaultReadParam.apply { sourceRegion = Rectangle(0, 10, 16, 20) })
            part.width shouldBe 16
            part.height shouldBe 20
            reader.dispose()
        }
    }

    @Test
    fun `the reader shows AVIF pages`() {
        val page = PageImage.decode(fixture("red-32x24.avif"))
        page.width shouldBe 32
        page.height shouldBe 24
    }

    @Test
    fun `a file that is not an image fails with an error, not a crash`() {
        val bytes = "....ftypavif garbage".toByteArray()
        runCatching { FfmpegImageDecoder.decode(bytes) }.isFailure shouldBe true
    }
}
