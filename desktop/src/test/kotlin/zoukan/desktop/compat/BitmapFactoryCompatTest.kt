package zoukan.desktop.compat

import android.graphics.BitmapFactory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Android's BitmapFactory returns null for data it cannot decode; extensions rely on that. */
class BitmapFactoryCompatTest {

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, 0xFF00FF00.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `an HTML error page decodes to null`() {
        val html = "<!DOCTYPE html><html><body>403 Forbidden</body></html>".toByteArray()
        BitmapFactory.decodeByteArray(html, 0, html.size) shouldBe null
        BitmapFactory.decodeStream(ByteArrayInputStream(html)) shouldBe null
    }

    @Test
    fun `garbage and empty data decode to null with bounds reset`() {
        val garbage = ByteArray(64) { (it * 31).toByte() }
        val options = BitmapFactory.Options()
        BitmapFactory.decodeByteArray(garbage, 0, garbage.size, options) shouldBe null
        options.outWidth shouldBe -1
        options.outHeight shouldBe -1
        options.outMimeType shouldBe null
        BitmapFactory.decodeByteArray(ByteArray(0), 0, 0) shouldBe null
    }

    @Test
    fun `a truncated PNG decodes to null`() {
        val bytes = png(40, 30)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size / 2) shouldBe null
    }

    @Test
    fun `inSampleSize 2 halves the size`() {
        val bytes = png(40, 30)
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)!!
        bitmap.width shouldBe 20
        bitmap.height shouldBe 15
        options.outWidth shouldBe 20
        options.outHeight shouldBe 15
        // Like Android, a sample size that is not a power of two rounds down
        val three = BitmapFactory.Options().apply { inSampleSize = 3 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, three)!!.width shouldBe 20
    }

    @Test
    fun `inJustDecodeBounds reads the size without a bitmap`() {
        val bytes = png(40, 30)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) shouldBe null
        options.outWidth shouldBe 40
        options.outHeight shouldBe 30
        options.outMimeType shouldBe "image/png"
    }

    @Test
    fun `AVIF decodes through the FFmpeg reader, subsampled too`() {
        val bytes = javaClass.getResourceAsStream("/images/red-32x24.avif")!!.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.width shouldBe 32
        // The FFmpeg reader ignores ImageIO subsampling; the bitmap is scaled down instead
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        val half = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)!!
        half.width shouldBe 16
        half.height shouldBe 12
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) shouldBe null
        bounds.outWidth shouldBe 32
        bounds.outHeight shouldBe 24
    }

    @Test
    fun `decodeFile decodes a file and returns null for a missing one`(@TempDir dir: File) {
        val file = File(dir, "page.png").apply { writeBytes(png(8, 6)) }
        BitmapFactory.decodeFile(file.path)!!.width shouldBe 8
        BitmapFactory.decodeFile(File(dir, "missing.png").path) shouldBe null
    }

    @Test
    fun `decodeStream leaves the caller's stream open`() {
        val stream = object : ByteArrayInputStream(png(4, 4)) {
            var closed = false
            override fun close() {
                closed = true
            }
        }
        BitmapFactory.decodeStream(stream)!!.width shouldBe 4
        stream.closed shouldBe false
    }
}
