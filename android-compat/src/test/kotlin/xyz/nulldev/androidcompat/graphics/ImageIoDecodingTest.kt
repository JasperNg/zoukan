package xyz.nulldev.androidcompat.graphics

import android.graphics.Bitmap
import com.twelvemonkeys.imageio.stream.ByteArrayImageInputStream
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import tachiyomi.decoder.ImageFormats
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream

class ImageIoDecodingTest {
    @TempDir
    lateinit var temp: File

    private fun sampleImage(
        width: Int = 8,
        height: Int = 6,
        type: Int = BufferedImage.TYPE_INT_ARGB,
        color: Color = Color(0x12, 0x34, 0x56, 0x80),
    ): BufferedImage =
        BufferedImage(width, height, type).apply {
            val g = createGraphics()
            try {
                g.color = color
                g.fillRect(0, 0, width, height)
            } finally {
                g.dispose()
            }
        }

    private fun encodeImage(
        image: BufferedImage,
        format: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { ios ->
            ImageIO.write(image, format, ios)
        }
        return out.toByteArray()
    }

    private fun encodeWebp(
        image: BufferedImage,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSLESS,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap(image).compress(format, 100, out) shouldBe true
        return out.toByteArray()
    }

    private fun encodeAnimatedGif(
        frame0: BufferedImage,
        frame1: BufferedImage,
        disposal: String = "restoreToBackgroundColor",
        frame1X: Int = 2,
        frame1Y: Int = 1,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            MemoryCacheImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam
                val streamMeta = writer.getDefaultStreamMetadata(param)
                val streamRoot = streamMeta.getAsTree("javax_imageio_gif_stream_1.0") as IIOMetadataNode
                val screen = IIOMetadataNode("LogicalScreenDescriptor").apply {
                    setAttribute("logicalScreenWidth", frame0.width.toString())
                    setAttribute("logicalScreenHeight", frame0.height.toString())
                    setAttribute("colorResolution", "8")
                    setAttribute("pixelAspectRatio", "0")
                }
                streamRoot.appendChild(screen)
                streamMeta.setFromTree("javax_imageio_gif_stream_1.0", streamRoot)
                writer.prepareWriteSequence(streamMeta)

                fun writeFrame(
                    img: BufferedImage,
                    x: Int,
                    y: Int,
                    frameDisposal: String,
                ) {
                    val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param)
                    val format = meta.nativeMetadataFormatName
                    val root = meta.getAsTree(format) as IIOMetadataNode
                    val descriptor = (0 until root.length).map { root.item(it) }.filterIsInstance<IIOMetadataNode>()
                        .firstOrNull { it.nodeName == "ImageDescriptor" }
                        ?: IIOMetadataNode("ImageDescriptor").also { root.appendChild(it) }
                    descriptor.setAttribute("imageLeftPosition", x.toString())
                    descriptor.setAttribute("imageTopPosition", y.toString())
                    val gce = (0 until root.length).map { root.item(it) }.filterIsInstance<IIOMetadataNode>()
                        .firstOrNull { it.nodeName == "GraphicControlExtension" }
                        ?: IIOMetadataNode("GraphicControlExtension").also { root.appendChild(it) }
                    gce.setAttribute("disposalMethod", frameDisposal)
                    gce.setAttribute("userInputFlag", "FALSE")
                    gce.setAttribute("transparentColorFlag", "FALSE")
                    gce.setAttribute("delayTime", "10")
                    gce.setAttribute("transparentColorIndex", "0")
                    meta.setFromTree(format, root)
                    writer.writeToSequence(IIOImage(img, null, meta), param)
                }

                writeFrame(frame0, 0, 0, disposal)
                writeFrame(frame1, frame1X, frame1Y, "none")
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun pngHeaderWithDimensions(
        width: Int,
        height: Int,
    ): ByteArray {
        val ihdrPayload =
            ByteBuffer.allocate(17).apply {
                put("IHDR".toByteArray(Charsets.US_ASCII))
                putInt(width)
                putInt(height)
                put(8.toByte()) // bit depth
                put(6.toByte()) // RGBA
                put(0.toByte()) // compression
                put(0.toByte()) // filter
                put(0.toByte()) // interlace
            }.array()
        val crc = CRC32().apply { update(ihdrPayload) }.value.toInt()
        return ByteBuffer.allocate(8 + 4 + ihdrPayload.size + 4).apply {
            put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            putInt(13)
            put(ihdrPayload)
            putInt(crc)
        }.array()
    }

    @Test
    fun `decodes valid PNG JPEG WEBP and GIF images into ARGB`() {
        val pngBytes = encodeImage(sampleImage(10, 8, BufferedImage.TYPE_INT_ARGB, Color(200, 100, 50, 128)), "png")
        val jpgBytes = encodeImage(sampleImage(12, 9, BufferedImage.TYPE_INT_RGB, Color(10, 200, 30)), "jpg")
        val gifBytes = encodeImage(sampleImage(6, 5, BufferedImage.TYPE_INT_RGB, Color(20, 40, 220)), "gif")
        val webpBytes = encodeWebp(sampleImage(14, 7, BufferedImage.TYPE_INT_ARGB, Color(90, 110, 130, 200)))

        ImageFormats.sniff(pngBytes) shouldBe Format.Png
        ImageFormats.sniff(jpgBytes) shouldBe Format.Jpeg
        ImageFormats.sniff(gifBytes) shouldBe Format.Gif
        ImageFormats.sniff(webpBytes) shouldBe Format.Webp

        ImageIoDecoding.size(pngBytes) shouldBe (10 to 8)
        ImageIoDecoding.size(jpgBytes) shouldBe (12 to 9)
        ImageIoDecoding.size(gifBytes) shouldBe (6 to 5)
        ImageIoDecoding.size(webpBytes) shouldBe (14 to 7)

        listOf(pngBytes, jpgBytes, gifBytes, webpBytes).forEach { bytes ->
            val decoded = ImageIoDecoding.decode(bytes)
            decoded.type shouldBe BufferedImage.TYPE_INT_ARGB
        }

        // Alpha channel is preserved on lossless PNG and WebP
        val decodedPng = ImageIoDecoding.decode(pngBytes)
        (decodedPng.getRGB(0, 0) ushr 24) shouldBe 128
        val decodedWebp = ImageIoDecoding.decode(webpBytes)
        (decodedWebp.getRGB(0, 0) ushr 24) shouldBe 200
    }

    @Test
    fun `supports region cropping and subsampling`() {
        val img =
            BufferedImage(20, 12, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                try {
                    g.color = Color.RED
                    g.fillRect(0, 0, 10, 12)
                    g.color = Color.BLUE
                    g.fillRect(10, 0, 10, 12)
                } finally {
                    g.dispose()
                }
            }
        val pngBytes = encodeImage(img, "png")

        val cropped = ImageIoDecoding.decode(pngBytes, region = Rectangle(10, 0, 10, 12), sampleSize = 2)
        cropped.width shouldBe 5
        cropped.height shouldBe 6
        (cropped.getRGB(0, 0) and 0x00FFFFFF) shouldBe (Color.BLUE.rgb and 0x00FFFFFF)
    }

    @Test
    fun `rejects empty garbage and truncated bytes`() {
        shouldThrow<IOException> { ImageIoDecoding.size(byteArrayOf()) }
        shouldThrow<IOException> { ImageIoDecoding.decode("not an image".toByteArray()) }

        val pngBytes = encodeImage(sampleImage(16, 16), "png")
        val truncatedPng = pngBytes.copyOf(16)
        shouldThrow<IOException> { ImageIoDecoding.decode(truncatedPng) }

        val jpgBytes = encodeImage(sampleImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpg")
        val truncatedJpg = jpgBytes.copyOf(12)
        shouldThrow<IOException> { ImageIoDecoding.decode(truncatedJpg) }

        ImageDecoder.newInstance(byteArrayOf().inputStream()) shouldBe null
        ImageDecoder.newInstance(truncatedPng.inputStream()) shouldBe null
    }

    @Test
    fun `refuses oversized and invalid dimensions before pixel allocation`() {
        shouldThrow<IOException> { ImageIoDecoding.checkSize(0, 10) }
        shouldThrow<IOException> { ImageIoDecoding.checkSize(10, -1) }
        shouldThrow<IOException> { ImageIoDecoding.checkSize(ImageIoDecoding.MAX_SIDE + 1, 1) }
        shouldThrow<IOException> { ImageIoDecoding.checkSize(1, ImageIoDecoding.MAX_SIDE + 1) }
        shouldThrow<IOException> { ImageIoDecoding.checkSize(20_000, 10_000) }
        ImageIoDecoding.checkSize(ImageIoDecoding.MAX_SIDE, 1)
        ImageIoDecoding.checkSize(10_000, 10_000)

        val tooWidePng = pngHeaderWithDimensions(ImageIoDecoding.MAX_SIDE + 1, 4)
        shouldThrow<IOException> { ImageIoDecoding.size(tooWidePng) }
        shouldThrow<IOException> { ImageIoDecoding.decode(tooWidePng) }
        shouldThrow<IOException> { ImageIoDecoding.canvasSize(tooWidePng) }

        val tooManyPixelsPng = pngHeaderWithDimensions(20_000, 10_000)
        shouldThrow<IOException> { ImageIoDecoding.size(tooManyPixelsPng) }
        shouldThrow<IOException> { ImageIoDecoding.decode(tooManyPixelsPng) }
    }

    @Test
    fun `toArgb and contentBounds handle grayscale borders and small images`() {
        val gray = sampleImage(8, 8, BufferedImage.TYPE_BYTE_GRAY, Color(120, 120, 120))
        val converted = ImageIoDecoding.toArgb(gray)
        converted.type shouldBe BufferedImage.TYPE_INT_ARGB
        ImageIoDecoding.toArgb(converted) shouldBe converted

        // 20x20 image with 4px white margin around a 12x12 dark center
        val bordered =
            BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                try {
                    g.color = Color.WHITE
                    g.fillRect(0, 0, 20, 20)
                    g.color = Color(0x20, 0x30, 0x40, 0xFF)
                    g.fillRect(4, 4, 12, 12)
                } finally {
                    g.dispose()
                }
            }
        ImageIoDecoding.contentBounds(bordered) shouldBe Rectangle(4, 4, 12, 12)

        val tiny = sampleImage(3, 3)
        ImageIoDecoding.contentBounds(tiny) shouldBe Rectangle(0, 0, 3, 3)
    }

    @Test
    fun `compositor respects animated GIF frames canvas size and disposal`() {
        val frame0 = sampleImage(6, 6, BufferedImage.TYPE_INT_ARGB, Color.RED)
        val frame1 = sampleImage(2, 2, BufferedImage.TYPE_INT_ARGB, Color.BLUE)
        val gif = encodeAnimatedGif(frame0, frame1, disposal = "restoreToBackgroundColor", frame1X = 2, frame1Y = 1)

        ImageIoDecoding.frameCount(gif) shouldBe 2
        ImageIoDecoding.canvasSize(gif) shouldBe (6 to 6)

        val compositor = ImageIoDecoding.FrameCompositor(gif, 6, 6)
        val first = compositor.frame(0)
        (first.getRGB(0, 0) and 0x00FFFFFF) shouldBe (Color.RED.rgb and 0x00FFFFFF)

        // Frame 0 used restoreToBackgroundColor so (0, 0) is cleared to transparent before frame 1 draws at (2, 1)
        val second = compositor.frame(1)
        (second.getRGB(0, 0) ushr 24) shouldBe 0
        (second.getRGB(2, 1) and 0x00FFFFFF) shouldBe (Color.BLUE.rgb and 0x00FFFFFF)

        shouldThrow<IllegalArgumentException> { compositor.frame(0) }
    }

    @Test
    fun `decoding never spills to an ImageIO temp file cache`() {
        val pngBytes = encodeImage(sampleImage(32, 32), "png")
        val jpgBytes = encodeImage(sampleImage(32, 32, BufferedImage.TYPE_INT_RGB), "jpg")
        val gifBytes = encodeImage(sampleImage(16, 16, BufferedImage.TYPE_INT_RGB), "gif")
        val webpBytes = encodeWebp(sampleImage(16, 16))

        byteArrayImageInputStream(pngBytes).use { input ->
            input.shouldBeInstanceOf<ByteArrayImageInputStream>()
        }

        val cacheDir = File(temp, "imageio-cache").apply { mkdirs() }
        val prevUseCache = ImageIO.getUseCache()
        val prevCacheDir = ImageIO.getCacheDirectory()
        try {
            ImageIO.setUseCache(true)
            ImageIO.setCacheDirectory(cacheDir)

            listOf(pngBytes, jpgBytes, gifBytes, webpBytes).forEach { bytes ->
                ImageIoDecoding.size(bytes)
                ImageIoDecoding.decode(bytes)
                ImageDecoder.newInstance(bytes.inputStream())?.let { decoder ->
                    decoder.decode()
                    decoder.recycle()
                }
            }

            cacheDir.listFiles().orEmpty().shouldBeEmpty()
        } finally {
            ImageIO.setUseCache(prevUseCache)
            ImageIO.setCacheDirectory(prevCacheDir)
        }
    }
}
