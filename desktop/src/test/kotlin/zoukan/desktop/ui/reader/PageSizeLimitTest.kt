package zoukan.desktop.ui.reader

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.UnreadableImageException
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A page from a source or a file that declares more pixels or bytes than the reader can hold is a
 * page error, found before anything that size is allocated: running out of memory on one page
 * would end the whole session.
 */
class PageSizeLimitTest {

    /** A grey 1-bit PNG that declares [width] by [height] pixels and holds one row of data. */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(1, 0, 0, 0, 0))
            }
        }.toByteArray()
        chunk("IHDR", ihdr)
        val row = ByteArray(1 + (width + 7) / 8)
        val deflater = Deflater().apply {
            setInput(row)
            finish()
        }
        val compressed = ByteArray(row.size + 64).let { it.copyOf(deflater.deflate(it)) }
        chunk("IDAT", compressed)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a page that declares more pixels than the reader can hold is a page error`() {
        // Both sizes ImageIO would read (a 1-bit page becomes 4 bytes a pixel once decoded) and one it refuses itself
        listOf(20_000 to 20_000, 1_000 to 100_000, 100_000 to 100_000).forEach { (w, h) ->
            val error = shouldThrow<UnreadableImageException> { PageImage.decode(png(w, h)) }
            error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
        }
    }

    @Test
    fun `the declared size is read without decoding the page`() {
        PageImage.declaredSize(png(30_000, 40_000)) shouldBe (30_000 to 40_000)
        PageImage.declaredSize("not an image".toByteArray()) shouldBe null
    }

    @Test
    fun `a page within the limit is not refused by its size`() {
        PageImage.checkDeclaredSize(png(8_000, 8_000))
    }

    @Test
    fun `a page response longer than the limit fails before it is read whole`() {
        val declared = Buffer().write(ByteArray(64)).asResponseBody(contentLength = 1L shl 40)
        shouldThrow<IOException> { readPageBody(declared, limit = 1024) }
        // A response that says no length is cut off at the limit
        val unknown = Buffer().write(ByteArray(4096)).asResponseBody(contentLength = -1)
        shouldThrow<IOException> { readPageBody(unknown, limit = 1024) }
        readPageBody(ByteArray(1024).toResponseBody("image/png".toMediaType()), limit = 1024).size shouldBe 1024
    }

    private fun BufferedSource.asResponseBody(contentLength: Long): ResponseBody {
        val source = this
        return object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = contentLength
            override fun source() = source
        }
    }
}
