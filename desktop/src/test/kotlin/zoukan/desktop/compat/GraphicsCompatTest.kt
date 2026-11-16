package zoukan.desktop.compat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.IntBuffer
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import kotlin.math.abs

/**
 * Extensions descramble pages and draw onto them with android.graphics; these check the AWT-backed
 * compatibility classes give the pixels Android would.
 */
class GraphicsCompatTest {

    private fun bitmapOf(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    @Test
    fun `drawBitmap from a src Rect to a dst RectF moves tiles pixel for pixel`() {
        // An 8x8 page in four 4x4 tiles, each pixel distinct
        val scrambled = bitmapOf(8, 8) { x, y -> (0xFF shl 24) or (x shl 16) or (y shl 8) or (x * 8 + y) }
        val result = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Tile i of the scrambled page goes to tile 3 - i, as a descrambler would put them back
        for (i in 0 until 4) {
            val sx = (i % 2) * 4
            val sy = (i / 2) * 4
            val j = 3 - i
            val dx = (j % 2) * 4f
            val dy = (j / 2) * 4f
            canvas.drawBitmap(scrambled, Rect(sx, sy, sx + 4, sy + 4), RectF(dx, dy, dx + 4, dy + 4), Paint())
        }
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val sx = (x + 4) % 8
                val sy = (y + 4) % 8
                result.getPixel(x, y) shouldBe scrambled.getPixel(sx, sy)
            }
        }
    }

    @Test
    fun `drawBitmap clips a src Rect reaching outside the bitmap`() {
        val source = bitmapOf(4, 4) { _, _ -> 0xFF00FF00.toInt() }
        val result = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        // The right half of the src lies outside the bitmap, so only the left half of the dst is drawn
        Canvas(result).drawBitmap(source, Rect(0, 0, 8, 4), Rect(0, 0, 8, 4), null)
        result.getPixel(3, 0) shouldBe 0xFF00FF00.toInt()
        result.getPixel(4, 0) shouldBe 0
    }

    @Test
    fun `Matrix posts and pres like Android`() {
        val m = Matrix()
        m.postScale(2f, 3f)
        m.postTranslate(10f, 20f)
        val point = floatArrayOf(1f, 1f)
        m.mapPoints(point)
        point.toList() shouldBe listOf(12f, 23f)

        val pre = Matrix()
        pre.setScale(2f, 2f)
        pre.preTranslate(1f, 0f)
        val origin = floatArrayOf(0f, 0f)
        pre.mapPoints(origin)
        origin.toList() shouldBe listOf(2f, 0f)

        val values = FloatArray(9)
        m.getValues(values)
        values.toList() shouldBe listOf(2f, 0f, 10f, 0f, 3f, 20f, 0f, 0f, 1f)

        val rect = RectF(0f, 0f, 1f, 1f)
        m.mapRect(rect)
        rect shouldBe RectF(10f, 20f, 12f, 23f)
    }

    @Test
    fun `drawBitmap with a Matrix scales and translates`() {
        val source = bitmapOf(2, 2) { x, y -> (0xFF shl 24) or (x * 0x80 shl 16) or (y * 0x80) }
        val result = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setScale(2f, 2f)
        m.postTranslate(1f, 1f)
        Canvas(result).drawBitmap(source, m, null)
        result.getPixel(0, 0) shouldBe 0
        result.getPixel(1, 1) shouldBe source.getPixel(0, 0)
        result.getPixel(2, 2) shouldBe source.getPixel(0, 0)
        result.getPixel(3, 1) shouldBe source.getPixel(1, 0)
        result.getPixel(4, 4) shouldBe source.getPixel(1, 1)
        result.getPixel(5, 5) shouldBe 0
    }

    @Test
    fun `ColorMatrixColorFilter inverts the colours of a drawn bitmap`() {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(invert) }
        val source = bitmapOf(2, 1) { x, _ -> if (x == 0) 0xFF102030.toInt() else 0xFFFFFFFF.toInt() }
        val result = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        result.pixels().toList() shouldBe listOf(0xFFEFDFCF.toInt(), 0xFF000000.toInt())
    }

    @Test
    fun `copyPixelsFromBuffer reads premultiplied RGBA bytes`() {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val bytes = ByteBuffer.wrap(byteArrayOf(0x11, 0x22, 0x33, 0xFF.toByte(), 0x40, 0x00, 0x00, 0x80.toByte()))
        bitmap.copyPixelsFromBuffer(bytes)
        bytes.position() shouldBe 8
        bitmap.getPixel(0, 0) shouldBe 0xFF112233.toInt()
        // Half-transparent red stored premultiplied as 0x40
        bitmap.getPixel(1, 0) shouldBe 0x80800000.toInt()

        // An IntBuffer holds the same bytes as little-endian ints
        val ints = IntBuffer.wrap(intArrayOf(0xFF332211.toInt(), 0xFF000000.toInt()))
        bitmap.copyPixelsFromBuffer(ints)
        bitmap.pixels().toList() shouldBe listOf(0xFF112233.toInt(), 0xFF000000.toInt())

        val out = ByteBuffer.allocate(8)
        bitmap.copyPixelsToBuffer(out)
        out.array().toList() shouldBe listOf<Byte>(0x11, 0x22, 0x33, 0xFF.toByte(), 0, 0, 0, 0xFF.toByte())
    }

    @Test
    fun `an ALPHA_8 bitmap keeps its mask bytes through the buffers`() {
        val bitmap = Bitmap.createBitmap(4, 1, Bitmap.Config.ALPHA_8)
        bitmap.hasAlpha() shouldBe true
        val mask = byteArrayOf(0, 0x40, 0x80.toByte(), 0xFF.toByte())
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(mask))
        val out = ByteBuffer.allocate(4)
        bitmap.copyPixelsToBuffer(out)
        out.position() shouldBe 4
        out.array().toList() shouldBe mask.toList()
    }

    @Test
    fun `breakText fits as much text as measureText says fits`() {
        val paint = Paint().apply { textSize = 16f }
        val text = "The quick brown fox jumps over the lazy dog. "
        val maxWidth = paint.measureText(text) / 2
        val measured = FloatArray(1)
        val count = paint.breakText(text, true, maxWidth, measured)
        (count in 1 until text.length) shouldBe true
        (paint.measureText(text, 0, count) <= maxWidth + 0.5f) shouldBe true
        (paint.measureText(text, 0, count + 1) > maxWidth - 0.5f) shouldBe true
        abs(measured[0] - paint.measureText(text, 0, count)) shouldBeLessThan 0.5f

        val backwards = paint.breakText(text, false, maxWidth, measured)
        (paint.measureText(text, text.length - backwards, text.length) <= maxWidth + 0.5f) shouldBe true

        paint.breakText(text, true, 10_000f, null) shouldBe text.length
        paint.breakText(text, true, 0f, null) shouldBe 0

        // One layout per call: a long paragraph breaks quickly
        val paragraph = text.repeat(200)
        val started = System.nanoTime()
        paint.breakText(paragraph, true, paint.measureText(paragraph) / 3, null)
        ((System.nanoTime() - started) / 1_000_000f) shouldBeLessThan 2_000f
    }

    @Test
    fun `drawPath fills the path`() {
        val result = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(2f, 2f)
            lineTo(8f, 2f)
            lineTo(8f, 8f)
            lineTo(2f, 8f)
            close()
        }
        val paint = Paint().apply {
            isAntiAlias = false
            color = 0xFFFF0000.toInt()
            style = Paint.Style.FILL
        }
        Canvas(result).drawPath(path, paint)
        result.getPixel(2, 2) shouldBe 0xFFFF0000.toInt()
        result.getPixel(5, 5) shouldBe 0xFFFF0000.toInt()
        result.getPixel(7, 7) shouldBe 0xFFFF0000.toInt()
        result.getPixel(8, 8) shouldBe 0
        result.getPixel(1, 5) shouldBe 0

        val bounds = RectF()
        path.computeBounds(bounds, true)
        bounds shouldBe RectF(2f, 2f, 8f, 8f)
    }

    @Test
    fun `clipRect limits drawing until restore`() {
        val result = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { color = 0xFF0000FF.toInt() }
        canvas.save()
        canvas.clipRect(0f, 0f, 2f, 4f)
        canvas.drawRect(0f, 0f, 4f, 4f, paint)
        canvas.restore()
        result.getPixel(1, 1) shouldBe 0xFF0000FF.toInt()
        result.getPixel(2, 1) shouldBe 0
        canvas.drawColor(0xFF00FF00.toInt())
        result.getPixel(3, 3) shouldBe 0xFF00FF00.toInt()
    }

    @Test
    fun `restoreToCount with the count read before save restores the transform and clip`() {
        val result = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.saveCount shouldBe 1

        val c = canvas.saveCount
        canvas.save() shouldBe 1
        canvas.saveCount shouldBe 2
        canvas.translate(5f, 0f)
        canvas.clipRect(0, 0, 2, 2)
        canvas.save() shouldBe 2
        canvas.saveCount shouldBe 3

        canvas.restoreToCount(c)
        canvas.saveCount shouldBe 1
        val paint = Paint().apply {
            isAntiAlias = false
            color = 0xFFFF0000.toInt()
        }
        canvas.drawRect(0f, 0f, 1f, 1f, paint)
        result.getPixel(0, 0) shouldBe 0xFFFF0000.toInt()
        result.getPixel(5, 0) shouldBe 0

        // Restoring to the current count changes nothing; restore() with nothing saved is an error
        canvas.restoreToCount(canvas.saveCount)
        canvas.saveCount shouldBe 1
        shouldThrow<IllegalStateException> { canvas.restore() }
    }

    @Test
    fun `createBitmap cuts a tile of a palette PNG with its colours`() {
        // Red, green, blue and yellow quadrants in a custom palette (not the JDK default one)
        val palette = intArrayOf(0xFFE01010.toInt(), 0xFF10E010.toInt(), 0xFF1010E0.toInt(), 0xFFE0E010.toInt())
        val reds = ByteArray(4) { (palette[it] shr 16).toByte() }
        val greens = ByteArray(4) { (palette[it] shr 8).toByte() }
        val blues = ByteArray(4) { palette[it].toByte() }
        val model = IndexColorModel(8, 4, reds, greens, blues)
        val indexed = BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED, model)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                indexed.raster.setSample(x, y, 0, (if (x >= 4) 1 else 0) + (if (y >= 4) 2 else 0))
            }
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(indexed, "png", it) }.toByteArray()
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        val tile = Bitmap.createBitmap(src, 4, 0, 4, 4)
        tile.pixels().all { it == palette[1] } shouldBe true
        val lower = Bitmap.createBitmap(src, 4, 4, 4, 4)
        lower.pixels().all { it == palette[3] } shouldBe true
        val copy = src.copy(src.config, true)
        copy.getPixel(0, 0) shouldBe palette[0]
        copy.getPixel(7, 0) shouldBe palette[1]
        copy.getPixel(0, 7) shouldBe palette[2]
        copy.getPixel(7, 7) shouldBe palette[3]
    }

    @Test
    fun `createBitmap cuts a tile of a 16-bit PNG`() {
        val image = ImageTypeSpecifier.createInterleaved(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            intArrayOf(0, 1, 2),
            DataBuffer.TYPE_USHORT,
            false,
            false,
        ).createBufferedImage(8, 8)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = x < 4
                image.raster.setSample(x, y, 0, if (left) 0xFFFF else 0)
                image.raster.setSample(x, y, 1, if (left) 0 else 0xFFFF)
                image.raster.setSample(x, y, 2, 0x8080)
            }
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        // The right half is green (0, 255, 128)
        val tile = Bitmap.createBitmap(src, 4, 0, 4, 4)
        tile.width shouldBe 4
        tile.pixels().forEach { p ->
            (p ushr 24) shouldBe 0xFF
            (abs(((p shr 16) and 0xFF) - 0x00) <= 1) shouldBe true
            (abs(((p shr 8) and 0xFF) - 0xFF) <= 1) shouldBe true
            (abs((p and 0xFF) - 0x80) <= 1) shouldBe true
        }
    }

    @Test
    fun `ImageDecoder decodes a PNG`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFFF0000.toInt())
        image.setRGB(2, 1, 0x800000FF.toInt())
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png))
        bitmap.width shouldBe 3
        bitmap.height shouldBe 2
        bitmap.getPixel(0, 0) shouldBe 0xFFFF0000.toInt()
        bitmap.getPixel(2, 1) shouldBe 0x800000FF.toInt()

        var mime: String? = null
        val viaBuffer = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png))) { decoder, info, _ ->
            mime = info.mimeType
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
        mime shouldBe "image/png"
        viaBuffer.getPixel(0, 0) shouldBe 0xFFFF0000.toInt()
    }

    @Test
    fun `text measures and aligns`() {
        val paint = Paint().apply { textSize = 20f }
        val width = paint.measureText("Hello")
        0f shouldBeLessThan width
        paint.fontMetrics.ascent shouldBeLessThan 0f
        paint.hasGlyph("A") shouldBe true
        paint.letterSpacing = 0.5f
        width shouldBeLessThan paint.measureText("Hello")
        paint.letterSpacing = 0f
        paint.textAlign = Paint.Align.RIGHT
        val result = Bitmap.createBitmap(100, 30, Bitmap.Config.ARGB_8888)
        // Right-aligned at x = 50: nothing is drawn right of 50
        Canvas(result).drawText("Hello", 50f, 25f, paint)
        (52 until 100).all { x -> (0 until 30).all { y -> result.getPixel(x, y) == 0 } } shouldBe true
        (0 until 50).any { x -> (0 until 30).any { y -> result.getPixel(x, y) != 0 } } shouldBe true
    }
}
