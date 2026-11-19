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
}
