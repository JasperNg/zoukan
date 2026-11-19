package zoukan.desktop.image

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import okio.Buffer
import okio.FileSystem
import okio.buffer
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.UnreadableImageException
import zoukan.desktop.ui.reader.PageImages
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Images from outside (covers, page previews, a picked cover, pages to combine) that declare an
 * absurd size are an ordinary error with a message, found from the header before anything that
 * size is allocated, with the reader's bounds.
 */
class ImageSizeLimitTest {

    /** A real 16 by 16 JPEG whose frame header is rewritten to declare [width] by [height]. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpeg", out)
        val bytes = out.toByteArray()
        val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
        bytes[sof + 5] = (height shr 8).toByte()
        bytes[sof + 6] = height.toByte()
        bytes[sof + 7] = (width shr 8).toByte()
        bytes[sof + 8] = width.toByte()
        return bytes
    }

    private val absurd get() = jpeg(60_000, 60_000)

    @Test
    fun `an absurd declared size is an error with the image's message`() {
        val error = shouldThrow<UnreadableImageException> { checkImageSize(absurd) }
        error.message shouldBe localized(DMR.strings.the_image_is_too_large)
        checkImageSize(jpeg(1_000, 1_500)) shouldBe (1_000 to 1_500)
        // A tall strip shows as a cover or preview as it does in the reader
        checkImageSize(jpeg(1_080, 65_500)) shouldBe (1_080 to 65_500)
        checkImageSize("not an image".toByteArray()) shouldBe null
    }

    private fun fetched(bytes: ByteArray) = SourceFetchResult(
        source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    @Test
    fun `covers and previews with an absurd size fail before Coil decodes them`() {
        val factory = DeclaredSizeCheck()
        shouldThrow<UnreadableImageException> { factory.create(fetched(absurd), mockk<Options>(relaxed = true), mockk(relaxed = true)) }
        // An ordinary image goes on to the next decoder, its bytes still all there
        val page = jpeg(16, 16)
        val result = fetched(page)
        factory.create(result, mockk<Options>(relaxed = true), mockk(relaxed = true)) shouldBe null
        result.source.source().readByteArray().size shouldBe page.size
    }

    @Test
    fun `combining or copying a page with an absurd size is an error, not a decode`() {
        shouldThrow<UnreadableImageException> { PageImages.combine(absurd, jpeg(16, 16), leftFirst = true, background = 0) }
        shouldThrow<UnreadableImageException> { PageImages.combine(jpeg(16, 16), absurd, leftFirst = true, background = 0) }
    }

    /** Noise of [width] by [height] in [format], so it does not compress below the prefix. */
    private fun noise(format: String, width: Int = 400, height: Int = 400): ByteArray {
        val random = java.util.Random(1)
        val type = if (format == "gif") BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()
    }

    /** A source over [bytes] that counts what is pulled from it. */
    private class Counted(bytes: ByteArray) {
        var pulled = 0L
        val source: okio.BufferedSource = object : okio.ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) pulled += it }
        }.buffer()
    }

    @Test
    fun `a cover's size is found from a prefix of its bytes, and the source is left unread`() {
        for (format in listOf("jpeg", "png", "gif", "bmp")) {
            val bytes = noise(format)
            (bytes.size > 64 * 1024) shouldBe true
            val counted = Counted(bytes)

            checkImageSize(counted.source, prefixBytes = 16 * 1024) shouldBe (400 to 400)

            // Far less than the whole image was read, and every byte is still there for the decoder
            (counted.pulled < bytes.size / 2) shouldBe true
            counted.source.readByteArray().contentEquals(bytes) shouldBe true
        }
    }

    @Test
    fun `a size the prefix does not hold is found from the whole image`() {
        // A JPEG whose frame header comes after a large comment segment, past the prefix
        val plain = jpeg(1_000, 1_500)
        val comment = ByteArray(40_000) { 'x'.code.toByte() }
        val com = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), ((comment.size + 2) shr 8).toByte(), (comment.size + 2).toByte()) + comment
        val bytes = plain.copyOfRange(0, 2) + com + plain.copyOfRange(2, plain.size)
        val counted = Counted(bytes)

        checkImageSize(counted.source, prefixBytes = 4 * 1024) shouldBe (1_000 to 1_500)
        counted.source.readByteArray().contentEquals(bytes) shouldBe true
    }

    @Test
    fun `an absurd size found from the prefix still fails`() {
        shouldThrow<UnreadableImageException> { checkImageSize(Counted(absurd).source, prefixBytes = 64) }
    }

    @Test
    fun `Skia is given a prefix only when it holds the whole header`() {
        val png = noise("png")
        holdsWholeHeader(png.copyOf(16 * 1024)) shouldBe true
        holdsWholeHeader(png.copyOf(20)) shouldBe false
        val jpeg = noise("jpeg")
        holdsWholeHeader(jpeg.copyOf(16 * 1024)) shouldBe true
        holdsWholeHeader(jpeg.copyOf(100)) shouldBe false
        holdsWholeHeader(noise("gif").copyOf(64)) shouldBe true
        holdsWholeHeader("not an image".toByteArray()) shouldBe false
    }
}
