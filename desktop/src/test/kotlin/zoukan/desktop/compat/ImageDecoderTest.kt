package zoukan.desktop.compat

import android.graphics.Rect
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
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
        } finally {
            ImageIO.setUseCache(useCache)
            ImageIO.setCacheDirectory(cacheDirectory)
        }
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
