package zoukan.desktop.image

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import xyz.nulldev.androidcompat.graphics.byteArrayImageInputStream
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import javax.imageio.ImageIO

/**
 * A page that cannot be decoded, with a [message] already worded for the user. Other
 * [IllegalArgumentException]s (a failed `require`, a malformed URL) carry developer text and are
 * not shown.
 */
internal class UnreadableImageException(override val message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** A page response longer than [PageSizeLimits.MAX_PAGE_BYTES]: the same page comes back the same size, so it is not fetched again. */
internal class PageTooLargeException(message: String) : java.io.IOException(message)

/**
 * How large a page may be, in bytes and in the pixels its header declares, for the reader and the
 * downloads alike: a page larger than these is no picture a person reads, and holding it would
 * end the whole session with an out-of-memory error.
 */
internal object PageSizeLimits {
    /** The most bytes one page's response may have, and one page read from disk (`Archives.Limits.pageBytes`). */
    const val MAX_PAGE_BYTES = 128L * 1024 * 1024

    /** [body]'s bytes, or a [PageTooLargeException] when it says or turns out to be longer than [limit]. */
    fun readPageBody(body: okhttp3.ResponseBody, limit: Long = MAX_PAGE_BYTES): ByteArray {
        val tooLarge = { PageTooLargeException(localized(DMR.strings.the_page_is_too_large_to_show)) }
        if (body.contentLength() > limit) throw tooLarge()
        val source = body.source()
        // More than the limit available: the page is refused before it is held whole
        if (source.request(limit + 1)) throw tooLarge()
        return source.readByteArray()
    }

    /**
     * The most pixels a page may declare on Skia's path (JPEG, PNG, WebP, GIF, BMP): WebP's
     * largest, 16384 by 16384, so every page Skia showed before still shows. Skia decodes the
     * page whole, in native memory outside the Java heap, and only then scales it down to the
     * window, so the declared size is what is allocated. Android shows such pages through its
     * subsampling view; a header that declares far more (a JPEG of 60000 by 60000) is no page,
     * and allocating it would end the session.
     */
    const val MAX_DECODED_PIXELS = 16_384L * 16_384

    /**
     * The longest side a page may declare on ImageIO's path: JPEG's largest, as the Android
     * layer's ImageIoDecoding.MAX_SIDE. Skia's path has no side limit of its own (a PNG strip of
     * 720 by 88,000 shows): there only [MAX_DECODED_PIXELS] applies.
     */
    const val MAX_PAGE_SIDE = 65_535

    /**
     * Bytes of Java heap a pixel takes on ImageIO's path at its peak: the decoded image (up to
     * 4 bytes a pixel) and its copy into a Skia bitmap (4 more).
     */
    private const val IMAGE_IO_BYTES_PER_PIXEL = 8L

    /**
     * The most pixels a page may declare on ImageIO's path (TIFF, AVIF, HEIF...), which decodes
     * on the Java heap: what fits in half of [maxHeap] beside the [heldBytes] the read holds,
     * and never more than [MAX_DECODED_PIXELS]. The reader decodes several pages at once, but
     * ImageIO's path one at a time, so the half is this decode's own.
     */
    fun imageIoPixelLimit(maxHeap: Long = Runtime.getRuntime().maxMemory(), heldBytes: Long = 0): Long =
        minOf(MAX_DECODED_PIXELS, ((maxHeap / 2 - heldBytes) / IMAGE_IO_BYTES_PER_PIXEL).coerceAtLeast(0))

    /**
     * The heap a read on ImageIO's path holds besides its pixels: the page's bytes, read in
     * place (no copy), and as much again for what a reader buffers or unpacks on the way.
     */
    fun imageIoHeldBytes(bytes: ByteArray): Long = 2L * bytes.size

    fun isTooLarge(width: Int, height: Int, maxPixels: Long, maxSide: Int = MAX_PAGE_SIDE): Boolean =
        width > maxSide || height > maxSide || width.toLong() * height > maxPixels

    /**
     * Fails with an [UnreadableImageException] worded [tooLarge] when [bytes] declare a page too
     * large for the decoder that will read it ([MAX_DECODED_PIXELS] for Skia, [imageIoPixelLimit]
     * and [MAX_PAGE_SIDE] for ImageIO), read from the image's header before anything that size is
     * allocated. Returns the declared size, null when no reader knows it.
     */
    fun checkDeclaredSize(
        bytes: ByteArray,
        tooLarge: () -> String = { localized(DMR.strings.the_page_is_too_large_to_show) },
    ): Pair<Int, Int>? {
        skiaSize(bytes)?.let { (width, height) ->
            if (isTooLarge(width, height, MAX_DECODED_PIXELS, maxSide = Int.MAX_VALUE)) throw UnreadableImageException(tooLarge())
            return width to height
        }
        val (width, height) = imageIoSize(bytes) ?: return null
        if (isTooLarge(width, height, imageIoPixelLimit(heldBytes = imageIoHeldBytes(bytes)))) throw UnreadableImageException(tooLarge())
        return width to height
    }

    /** The width and height the image in [bytes] declares, without decoding it; null when no reader knows it. */
    fun declaredSize(bytes: ByteArray): Pair<Int, Int>? = skiaSize(bytes) ?: imageIoSize(bytes)

    /** The size Skia's codec reads from the header; null when Skia does not read the type. */
    private fun skiaSize(bytes: ByteArray): Pair<Int, Int>? = try {
        Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { it.width to it.height } }
    } catch (_: RuntimeException) {
        // Not a type Skia reads (it fails with an unchecked exception)
        null
    }

    /** The size ImageIO's readers read from the header; null when none knows the type. */
    private fun imageIoSize(bytes: ByteArray): Pair<Int, Int>? = try {
        // Over the bytes themselves: ImageIO.createImageInputStream may spill the page to a temporary file
        byteArrayImageInputStream(bytes).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
