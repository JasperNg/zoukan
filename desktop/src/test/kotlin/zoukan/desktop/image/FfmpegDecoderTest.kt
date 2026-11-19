package zoukan.desktop.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.ImageType
import zoukan.desktop.ui.reader.PageImage
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

private fun imageResource(path: String): ByteArray =
    FfmpegImageDecoderTest::class.java.getResourceAsStream(path)!!.use { it.readBytes() }

/**
 * AVIF pages and covers (Atsumaru serves only AVIF) decode on desktop, where Skia and ImageIO
 * alone cannot read them.
 */
class FfmpegImageDecoderTest {
    private fun fixture(name: String): ByteArray = imageResource("/images/$name")

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
    fun `decodes from memory, in parallel, and the same as from a file`() {
        val red = fixture("red-32x24.avif")
        val blue = fixture("blue-16x40.avif")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val sizes = (0 until 32).map { i ->
                pool.submit<Pair<Int, Int>> { FfmpegImageDecoder.decode(if (i % 2 == 0) red else blue).let { it.width to it.height } }
            }.map { it.get() }
            sizes.forEachIndexed { i, size -> size shouldBe if (i % 2 == 0) (32 to 24) else (16 to 40) }
        } finally {
            pool.shutdown()
        }
        val file = kotlin.io.path.createTempFile(suffix = ".avif").toFile()
        try {
            file.writeBytes(red)
            FfmpegImageDecoder.decode(file).getRGB(5, 5) shouldBe FfmpegImageDecoder.decode(red).getRGB(5, 5)
        } finally {
            file.delete()
        }
        // Cut short or empty: an error (or what could be read), never a crash
        runCatching { FfmpegImageDecoder.decode(red.copyOf(red.size / 2)) }
        runCatching { FfmpegImageDecoder.decode(ByteArray(0)) }.isFailure shouldBe true
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

    /** The same AVIF with the generic major brand mif1; avif stays among its compatible brands. */
    private fun withMajorBrand(bytes: ByteArray, brand: String): ByteArray =
        bytes.copyOf().also { copy -> brand.forEachIndexed { i, c -> copy[8 + i] = c.code.toByte() } }

    @Test
    fun `an AVIF with the mif1 major brand is still AVIF, decoded and saved as avif`() {
        val bytes = withMajorBrand(fixture("red-32x24.avif"), "mif1")
        FfmpegImageDecoder.handles(bytes) shouldBe true
        ImageType.fromBytes(bytes) shouldBe ImageType.AVIF
        FfmpegImageDecoder.decode(bytes).width shouldBe 32
        ImageIO.read(ByteArrayInputStream(bytes)) shouldNotBe null
    }

    @Test
    fun `keeps the alpha of an AVIF with an alpha auxiliary image`() {
        val image = FfmpegImageDecoder.decode(fixture("alpha-32x24.avif"))
        image.width shouldBe 32
        image.height shouldBe 24
        // Left half opaque red, right half transparent
        assertColor(image.getRGB(4, 12), 0xFF, 0, 0)
        ((image.getRGB(28, 12) ushr 24) <= 8) shouldBe true
        // Opaque images stay opaque
        (FfmpegImageDecoder.decode(fixture("red-32x24.avif")).getRGB(30, 20) ushr 24) shouldBe 0xFF
    }

    @Test
    fun `merges an alpha image into the color image`() {
        val color = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFFFF0000.toInt())
            setRGB(1, 0, 0xFF00FF00.toInt())
        }
        val alpha = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFF808080.toInt())
            setRGB(1, 0, 0xFF000000.toInt())
        }
        val merged = FfmpegImageDecoder.withAlpha(color, alpha)
        merged.getRGB(0, 0) shouldBe 0x80FF0000.toInt()
        merged.getRGB(1, 0) shouldBe 0x0000FF00
        // An alpha image of another size is left out
        val opaque = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 0xFF0000FF.toInt()) }
        FfmpegImageDecoder.withAlpha(opaque, alpha).getRGB(0, 0) shouldBe 0xFF0000FF.toInt()
    }

    @Test
    fun `reads the size from the header without decoding`() {
        HeifHeader.primaryImageSize(fixture("red-32x24.avif")) shouldBe (32 to 24)
        HeifHeader.primaryImageSize(fixture("blue-16x40.avif")) shouldBe (16 to 40)
        HeifHeader.primaryImageSize(fixture("alpha-32x24.avif")) shouldBe (32 to 24)
        listOf("avif-1", "avif-2", "avif-3").forEach { name ->
            val bytes = imageResource("/e2e-fixtures/images/$name.avif")
            val decoded = FfmpegImageDecoder.decode(bytes)
            HeifHeader.primaryImageSize(bytes) shouldBe (decoded.width to decoded.height)
        }
        HeifHeader.primaryImageSize("....ftypavif garbage".toByteArray()) shouldBe null
        HeifHeader.primaryImageSize(ByteArray(0)) shouldBe null
    }

    @Test
    fun `the ImageIO reader subsamples, and decodes once per reader`() {
        val bytes = fixture("blue-16x40.avif")
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            reader.input = input
            val half = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(2, 2, 0, 0) })
            half.width shouldBe 8
            half.height shouldBe 20
            assertColor(half.getRGB(4, 10), 0, 0, 0xFF)
            val part = reader.read(
                0,
                reader.defaultReadParam.apply {
                    sourceRegion = Rectangle(0, 10, 16, 20)
                    setSourceSubsampling(4, 3, 0, 0)
                },
            )
            part.width shouldBe 4
            part.height shouldBe 7
            // The same decoded image serves every read
            reader.read(0) shouldBe reader.read(0)
            reader.dispose()
        }
    }

    @Test
    fun `subsampling takes one pixel in each step, from the offset`() {
        val image = BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 3) for (x in 0 until 5) image.setRGB(x, y, (0xFF shl 24) or (x shl 8) or y)
        val out = FfmpegImageReader.subsampled(image, Rectangle(0, 0, 5, 3), 2, 2, 1, 0)
        out.width shouldBe 2
        out.height shouldBe 2
        out.getRGB(0, 0) and 0xFFFF shouldBe ((1 shl 8) or 0)
        out.getRGB(1, 1) and 0xFFFF shouldBe ((3 shl 8) or 2)
    }

    @Test
    fun `the reader crops the borders of AVIF pages`() {
        val bytes = fixture("bordered-64x48.avif")
        PageImage.decode(bytes).width shouldBe 64
        val cropped = PageImage.decode(bytes, cropBorders = true)
        (cropped.width < 64) shouldBe true
        (cropped.height < 48) shouldBe true
        // Around the 32x24 red block, with the small margin kept
        (cropped.width in 32..40) shouldBe true
        (cropped.height in 24..32) shouldBe true
    }

    @Test
    fun `JPEG XL pages fail with a message that names the format`() {
        val codestream = byteArrayOf(0xFF.toByte(), 0x0A, 0, 0, 0, 0, 0, 0)
        val container = byteArrayOf(0, 0, 0, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0, 0, 0, 0)
        listOf(codestream, container).forEach { bytes ->
            ImageType.fromBytes(bytes) shouldBe ImageType.JXL
            val error = shouldThrow<IllegalArgumentException> { PageImage.decode(bytes) }
            error.message shouldContain "JPEG XL"
        }
    }

    @Test
    fun `the extension image decoder reads AVIF sizes and regions, subsampled`() {
        val decoder = tachiyomi.decoder.ImageDecoder.newInstance(ByteArrayInputStream(fixture("blue-16x40.avif")))!!
        decoder.width shouldBe 16
        decoder.height shouldBe 40
        val bitmap = decoder.decode(android.graphics.Rect(0, 0, 16, 40), sampleSize = 2)!!
        bitmap.width shouldBe 8
        bitmap.height shouldBe 20
        val part = decoder.decode(android.graphics.Rect(0, 20, 16, 40))!!
        part.height shouldBe 20
        decoder.recycle()
        decoder.decode() shouldBe null
    }
}

/**
 * Covers AVIF decoding through Coil ([FfmpegCoilDecoder]) and multi-page AVIF reader decoding
 * ([PageImage.decode]), ensuring AVIF chapters and covers render on desktop without Skia support.
 */
class FfmpegCoilDecoderTest {

    @TempDir
    lateinit var tempDir: File

    private fun options(size: Size = Size.ORIGINAL): Options =
        Options(context = PlatformContext.INSTANCE, size = size)

    @Test
    fun `factory handles AVIF bytes and leaves other formats to the next decoder`() {
        val factory = FfmpegCoilDecoder.Factory()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val opts = options()

        val avifResult = SourceFetchResult(
            source = ImageSource(Buffer().write(imageResource("/images/red-32x24.avif")), FileSystem.SYSTEM),
            mimeType = "image/avif",
            dataSource = coil3.decode.DataSource.NETWORK,
        )
        factory.create(avifResult, opts, loader).shouldNotBeNull()

        val pngBytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val pngResult = SourceFetchResult(
            source = ImageSource(Buffer().write(pngBytes), FileSystem.SYSTEM),
            mimeType = "image/png",
            dataSource = coil3.decode.DataSource.NETWORK,
        )
        factory.create(pngResult, opts, loader).shouldBeNull()
    }

    @Test
    fun `decodes AVIF from memory and downscales when target size is smaller than half`() = runBlocking<Unit> {
        val bytes = imageResource("/images/blue-16x40.avif")
        val factory = FfmpegCoilDecoder.Factory()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

        val fullFetch = SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = "image/avif",
            dataSource = coil3.decode.DataSource.MEMORY,
        )
        val full = factory.create(fullFetch, options(), loader)!!.decode()!!
        full.isSampled shouldBe false
        full.image.width shouldBe 16
        full.image.height shouldBe 40

        val scaledFetch = SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = "image/avif",
            dataSource = coil3.decode.DataSource.MEMORY,
        )
        val scaled = factory.create(scaledFetch, options(Size(4, 10)), loader)!!.decode()!!
        scaled.isSampled shouldBe true
        scaled.image.width shouldBe 4
        scaled.image.height shouldBe 10
    }

    @Test
    fun `decodes AVIF pages from e2e fixtures into drawable PageImage slices`() {
        listOf("avif-1", "avif-2", "avif-3").forEach { name ->
            val bytes = imageResource("/e2e-fixtures/images/$name.avif")
            val page = PageImage.decode(bytes)
            try {
                page.width shouldBe 600
                page.height shouldBe 900
                page.slices.shouldNotBeEmpty()
                page.slices.first().width shouldBe 600
            } finally {
                page.release()
            }
        }
    }

    @Test
    fun `decodes AVIF directly from a system file`() = runBlocking<Unit> {
        val avifFile = File(tempDir, "sample.avif").apply {
            writeBytes(imageResource("/images/red-32x24.avif"))
        }
        val source = ImageSource(okio.Path.Companion.run { avifFile.toOkioPath() }, FileSystem.SYSTEM)
        val result = FfmpegCoilDecoder(source, options()).decode()
        result.image.width shouldBe 32
        result.image.height shouldBe 24
    }
}
