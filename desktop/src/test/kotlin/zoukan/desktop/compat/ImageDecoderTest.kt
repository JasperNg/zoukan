package zoukan.desktop.compat

import android.graphics.Rect
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import ca.mpreg.imagedecoder.ImageDecoder as FrameDecoder

class ImageDecoderTest {

    /** 100x60, red on the left half and blue on the right, in a 10-pixel white border. */
    private fun png(): ByteArray {
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, 120, 80)
        graphics.color = Color.RED
        graphics.fillRect(10, 10, 50, 60)
        graphics.color = Color.BLUE
        graphics.fillRect(60, 10, 50, 60)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `decodes a region with a sample size`() {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(png())).shouldNotBeNull()
        decoder.width shouldBe 120
        decoder.height shouldBe 80
        val bitmap = decoder.decode(Rect(60, 0, 120, 80), sampleSize = 2).shouldNotBeNull()
        bitmap.width shouldBe 30
        bitmap.height shouldBe 40
        bitmap.getPixel(10, 20) shouldBe Color.BLUE.rgb
        decoder.recycle()
        decoder.decode().shouldBeNull()
    }

    @Test
    fun `crops plain borders`() {
        val decoder = ImageDecoder.newInstance(ByteArrayInputStream(png()), cropBorders = true).shouldNotBeNull()
        decoder.width shouldBe 100
        decoder.height shouldBe 60
        val bitmap = decoder.decode().shouldNotBeNull()
        bitmap.getPixel(0, 0) shouldBe Color.RED.rgb
        bitmap.getPixel(99, 59) shouldBe Color.BLUE.rgb
    }

    @Test
    fun `finds the type and turns down what it cannot read`() {
        ImageDecoder.findType(png())?.format shouldBe Format.Png
        ImageDecoder.newInstance(ByteArrayInputStream(ByteArray(16))).shouldBeNull()
    }

    @Test
    fun `the frame decoder gives RGBA pixels`() {
        FrameDecoder.open(ByteArrayInputStream(png())).use { decoder ->
            decoder.isHdr shouldBe false
            decoder.decodeNext().use { frame ->
                frame.width shouldBe 120
                frame.height shouldBe 80
                val pixel = (20 * 120 + 20) * 4 // inside the red half
                frame.image.get(pixel).toInt() and 0xFF shouldBe 0xFF
                frame.image.get(pixel + 1).toInt() and 0xFF shouldBe 0
                frame.image.get(pixel + 2).toInt() and 0xFF shouldBe 0
                frame.image.get(pixel + 3).toInt() and 0xFF shouldBe 0xFF
            }
        }
    }
}
