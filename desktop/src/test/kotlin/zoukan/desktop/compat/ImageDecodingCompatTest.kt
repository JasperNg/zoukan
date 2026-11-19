package zoukan.desktop.compat

import android.graphics.BitmapFactory
import android.graphics.Rect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import zoukan.desktop.ui.reader.PageImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import ca.mpreg.imagedecoder.ImageDecoder as FrameDecoder

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

    /** 300x200 red, then a 50x40 blue frame at (100, 60) drawn over it. */
    private fun twoFrameGif(): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            fun frame(width: Int, height: Int, color: Color, left: Int, top: Int) {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().apply {
                    this.color = color
                    fillRect(0, 0, width, height)
                    dispose()
                }
                val type = ImageTypeSpecifier.createFromRenderedImage(image)
                val metadata = writer.getDefaultImageMetadata(type, writer.defaultWriteParam)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                val descriptor = root.getElementsByTagName("ImageDescriptor").item(0) as IIOMetadataNode
                descriptor.setAttribute("imageLeftPosition", left.toString())
                descriptor.setAttribute("imageTopPosition", top.toString())
                val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                control.setAttribute("disposalMethod", "doNotDispose")
                control.setAttribute("delayTime", "10")
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
            }
            frame(300, 200, Color.RED, 0, 0)
            frame(50, 40, Color.BLUE, 100, 60)
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** Runs every decode, probe and compress path once; [onOpen] runs while a stream is open. */
    private fun useEveryImagePath(png: ByteArray, gif: ByteArray, onOpen: () -> Unit) {
        val source = android.graphics.ImageDecoder.createSource(png)
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { _, _, _ -> onOpen() }
        ImageDecoder.newInstance(ByteArrayInputStream(png))!!.decode().shouldNotBeNull()
        FrameDecoder.open(ByteArrayInputStream(gif)).use { decoder ->
            decoder.decodeNext().close()
            decoder.decodeNext().close()
        }
        for (format in listOf(android.graphics.Bitmap.CompressFormat.PNG, android.graphics.Bitmap.CompressFormat.JPEG)) {
            val sink = object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    onOpen()
                    super.write(b, off, len)
                }
            }
            bitmap.compress(format, 100, sink)
            (sink.size() > 0) shouldBe true
        }
    }

    @Test
    fun `decoding and compressing never write page bytes to ImageIO's cache folder`(@TempDir dir: Path) {
        val useCache = ImageIO.getUseCache()
        val cacheDirectory = ImageIO.getCacheDirectory()
        val png = png()
        val gif = twoFrameGif()
        val tiff = ByteArrayOutputStream().also { ImageIO.write(ImageIO.read(ByteArrayInputStream(png)), "tiff", it) }.toByteArray()
        try {
            ImageIO.setUseCache(true)
            ImageIO.setCacheDirectory(dir.toFile())
            val seen = mutableListOf<String>()
            useEveryImagePath(png, gif) { seen += dir.toFile().list().orEmpty() }
            seen shouldBe emptyList()
            dir.toFile().list().orEmpty().toList() shouldBe emptyList()

            // A cache folder that no longer exists makes any file-backed stream fail
            dir.toFile().delete()
            useEveryImagePath(png, gif) {}
            // The reader's page header check and its ImageIO fallback, on a type Skia cannot read
            PageImage.declaredSize(tiff) shouldBe (120 to 80)
            PageImage.decode(tiff).let { it.width to it.height } shouldBe (120 to 80)
        } finally {
            ImageIO.setUseCache(useCache)
            ImageIO.setCacheDirectory(cacheDirectory)
        }
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** A valid PNG whose IHDR claims [width] x [height] (8-bit RGBA) with a tiny IDAT: a few hundred bytes. */
    private fun hugePng(width: Int = 100_000, height: Int = 100_000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(int(data.size))
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            out.write(int(crc.value.toInt()))
        }
        chunk("IHDR", int(width) + int(height) + byteArrayOf(8, 6, 0, 0, 0))
        val deflater = Deflater()
        deflater.setInput(ByteArray(8))
        deflater.finish()
        val buffer = ByteArray(64)
        val idat = ByteArrayOutputStream()
        while (!deflater.finished()) idat.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun <T> quickly(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(10), block)

    @Test
    fun `all decoders refuse a PNG whose IHDR claims 100000x100000`() = everyDecoderRefuses(100_000, 100_000)

    // The pixel count fits an int, so the platform does not refuse it by itself: 6 GB as ARGB
    @Test
    fun `all decoders refuse a PNG whose IHDR claims 40000x40000`() = everyDecoderRefuses(40_000, 40_000)

    private fun everyDecoderRefuses(width: Int, height: Int) {
        val bytes = hugePng(width, height)
        quickly { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.shouldBeNull()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        quickly { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }.shouldBeNull()
        bounds.outWidth shouldBe width
        bounds.outHeight shouldBe height

        quickly {
            shouldThrow<android.graphics.ImageDecoder.DecodeException> {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(bytes))
            }
        }
        quickly { ImageDecoder.newInstance(ByteArrayInputStream(bytes))?.decode() }.shouldBeNull()
        quickly {
            shouldThrow<IOException> { FrameDecoder.open(ByteArrayInputStream(bytes)).decodeNext() }
        }
    }

    @Test
    fun `refusing a huge page leaves the page file unchanged`(@TempDir dir: Path) {
        val file = dir.resolve("page.png")
        Files.write(file, hugePng())
        val before = Files.readAllBytes(file)
        val modified = Files.getLastModifiedTime(file)
        quickly {
            runCatching { android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file.toFile())) }
            BitmapFactory.decodeFile(file.toString())
        }.shouldBeNull()
        Files.exists(file) shouldBe true
        Files.readAllBytes(file).contentEquals(before) shouldBe true
        Files.size(file) shouldBe before.size.toLong()
        Files.getLastModifiedTime(file) shouldBe modified
    }

    @Test
    fun `a tall strip page still decodes`() {
        val strip = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(800, 12_000, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(strip, 0, strip.size).shouldNotBeNull()
        bitmap.width shouldBe 800
        bitmap.height shouldBe 12_000
        val decoded = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(strip))
        decoded.height shouldBe 12_000
    }

    @Test
    fun `the frame decoder composites animation frames on the whole canvas`() {
        FrameDecoder.open(ByteArrayInputStream(twoFrameGif())).use { decoder ->
            decoder.width shouldBe 300
            decoder.height shouldBe 200
            decoder.frameCount shouldBe 2
            decoder.decodeNext().use { it.width to it.height shouldBe (300 to 200) }
            decoder.decodeNext().use { frame ->
                frame.width shouldBe 300
                frame.height shouldBe 200
                fun rgb(x: Int, y: Int): List<Int> =
                    (0 until 3).map { frame.image.get((y * 300 + x) * 4 + it).toInt() and 0xFF }
                rgb(120, 80) shouldBe listOf(0, 0, 0xFF) // the new blue frame
                rgb(10, 10) shouldBe listOf(0xFF, 0, 0) // the red first frame left in place
            }
        }
    }
}
