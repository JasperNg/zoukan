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
        // More pixels than Skia's bound, square and as a strip (no side limit applies on Skia's path)
        listOf(20_000 to 20_000, 1_000 to 300_000, 100_000 to 100_000).forEach { (w, h) ->
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

    /** A real 16 by 16 JPEG whose frame header is rewritten to declare [width] by [height]. */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", out)
        val bytes = out.toByteArray()
        val sof = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xC0.toByte() }
        bytes[sof + 5] = (height shr 8).toByte()
        bytes[sof + 6] = height.toByte()
        bytes[sof + 7] = (width shr 8).toByte()
        bytes[sof + 8] = width.toByte()
        return bytes
    }

    // 65,500 is the tallest JPEG libjpeg (Skia's and ImageIO's decoder) reads; a taller one never showed
    @Test
    fun `a webtoon strip near JPEG's height limit at an ordinary width is not refused`() {
        listOf(1080 to 65_500, 1280 to 60_000).forEach { (w, h) ->
            val page = jpeg(w, h)
            PageImage.declaredSize(page) shouldBe (w to h)
            PageImage.checkDeclaredSize(page)
        }
    }

    // Skia has no side limit of its own for PNG: a tall strip within the pixel bound showed before
    @Test
    fun `a PNG strip taller than 65,535 pixels within the pixel bound is not refused`() {
        val page = png(720, 88_000)
        PageImage.declaredSize(page) shouldBe (720 to 88_000)
        PageImage.checkDeclaredSize(page)
    }

    @Test
    fun `a page as large as WebP allows is not refused on Skia's path`() {
        PageImage.isTooLarge(16_383, 16_383, PageImage.MAX_DECODED_PIXELS) shouldBe false
        PageImage.isTooLarge(16_384, 16_384, PageImage.MAX_DECODED_PIXELS) shouldBe false
    }

    @Test
    fun `an absurd declared size is a page error`() {
        val error = shouldThrow<UnreadableImageException> { PageImage.decode(jpeg(60_000, 60_000)) }
        error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
    }

    @Test
    fun `ImageIO's path is bounded by the heap it decodes on`() {
        val twoGb = 2L * 1024 * 1024 * 1024
        // A strip fits in the heap the app runs with; the same strip does not fit in a small one
        PageImage.isTooLarge(1080, 65_535, PageImage.imageIoPixelLimit(twoGb)) shouldBe false
        PageImage.isTooLarge(1080, 65_535, PageImage.imageIoPixelLimit(256L * 1024 * 1024)) shouldBe true
        // Never above Skia's bound, however large the heap
        PageImage.imageIoPixelLimit(Long.MAX_VALUE) shouldBe PageImage.MAX_DECODED_PIXELS
    }

    @Test
    fun `the page bytes held during an ImageIO read count against its heap budget`() {
        val twoGb = 2L * 1024 * 1024 * 1024
        val strip = 1080L * 65_535
        // A 100 MB page held twice (its bytes and the read's copy) leaves less room for pixels
        PageImage.imageIoPixelLimit(twoGb, heldBytes = 0) shouldBe twoGb / 2 / 8
        PageImage.imageIoPixelLimit(twoGb, heldBytes = 200L * 1024 * 1024) shouldBe (twoGb / 2 - 200L * 1024 * 1024) / 8
        (strip <= PageImage.imageIoPixelLimit(twoGb, heldBytes = 2L * 100 * 1024 * 1024)) shouldBe true
        // Bytes alone past the budget leave no room at all
        PageImage.imageIoPixelLimit(256L * 1024 * 1024, heldBytes = 512L * 1024 * 1024) shouldBe 0L
    }

    @Test
    fun `ImageIO's path decodes one page at a time`() {
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val secondEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val first = Thread {
            PageImage.oneImageIoDecodeAtATime {
                inside.countDown()
                release.await()
            }
        }.apply { start() }
        inside.await()
        val second = Thread { PageImage.oneImageIoDecodeAtATime { secondEntered.set(true) } }.apply { start() }
        // The second decode waits for the first: it parks on the gate
        val deadline = System.nanoTime() + 10_000_000_000L
        while (second.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        second.state shouldBe Thread.State.WAITING
        secondEntered.get() shouldBe false
        release.countDown()
        first.join()
        second.join()
        secondEntered.get() shouldBe true
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
