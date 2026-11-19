package zoukan.desktop.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ui.reader.PageImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Covers AVIF decoding through Coil ([FfmpegCoilDecoder]) and multi-page AVIF reader decoding
 * ([PageImage.decode]), ensuring AVIF chapters and covers render on desktop without Skia support.
 */
class FfmpegCoilDecoderTest {

    @TempDir
    lateinit var tempDir: File

    private fun resource(path: String): ByteArray =
        javaClass.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun options(size: Size = Size.ORIGINAL): Options =
        Options(context = PlatformContext.INSTANCE, size = size)

    @Test
    fun `factory handles AVIF bytes and leaves other formats to the next decoder`() {
        val factory = FfmpegCoilDecoder.Factory()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val opts = options()

        val avifResult = SourceFetchResult(
            source = ImageSource(Buffer().write(resource("/images/red-32x24.avif")), FileSystem.SYSTEM),
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
        val bytes = resource("/images/blue-16x40.avif")
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
            val bytes = resource("/e2e-fixtures/images/$name.avif")
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
            writeBytes(resource("/images/red-32x24.avif"))
        }
        val source = ImageSource(okio.Path.Companion.run { avifFile.toOkioPath() }, FileSystem.SYSTEM)
        val result = FfmpegCoilDecoder(source, options()).decode()
        result.image.width shouldBe 32
        result.image.height shouldBe 24
    }
}
