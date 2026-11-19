package zoukan.desktop.image

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * Fails with an [UnreadableImageException] when [bytes] declare an image
 * too large for the decoder that will read it, read from the header before anything that size is
 * allocated: the reader's bounds ([PageSizeLimits.checkDeclaredSize]), for every other image from
 * outside (covers, page previews, a picked cover, pages to combine or copy). None of their decoders
 * subsamples: Coil's Skia decoder decodes the whole image before it scales it, and FFmpeg and
 * ImageIO decode whole images too, so the declared size is what is allocated. Returns the declared
 * size, null when no reader knows it.
 */
internal fun checkImageSize(bytes: ByteArray): Pair<Int, Int>? =
    PageSizeLimits.checkDeclaredSize(bytes) { localized(DMR.strings.the_image_is_too_large) }

/**
 * First among Coil's decoders: refuses an image whose declared size is too large ([checkImageSize]),
 * a cover or a page preview, and otherwise leaves it to the next decoder with its bytes untouched.
 */
internal class DeclaredSizeCheck(private val prefixBytes: Long = PREFIX_BYTES) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        checkImageSize(result.source.source(), prefixBytes)
        return null
    }

    internal companion object {
        const val PREFIX_BYTES = 256L * 1024
    }
}

/**
 * [checkImageSize] on the image [source] holds, which is left unread (read through [okio.BufferedSource.peek]).
 * Only the first [prefixBytes] are read when they hold the size of a type Skia reads (PNG, GIF,
 * WebP and BMP headers, and nearly every JPEG's frame header, come first): a library grid checks
 * many covers, and a whole copy of each is not needed for that. Another type (whose ImageIO bound
 * depends on the file's size), or a header past the prefix, is checked over the whole image.
 */
internal fun checkImageSize(source: okio.BufferedSource, prefixBytes: Long): Pair<Int, Int>? {
    val peek = source.peek()
    if (!peek.request(prefixBytes)) return checkImageSize(peek.readByteArray())
    val prefix = peek.readByteArray(prefixBytes)
    if (holdsWholeHeader(prefix)) {
        PageSizeLimits.checkSkiaSize(prefix) { localized(DMR.strings.the_image_is_too_large) }?.let { return it }
    }
    return checkImageSize(source.peek().readByteArray())
}

/**
 * Whether [prefix], the start of an image, holds all of the header Skia reads for its type, so
 * Skia is never given a header cut short: its JPEG and PNG decoders (libjpeg, libpng) fail such a
 * header by jumping out of native code, after which the JVM no longer answers a request to attach
 * to it (a thread dump, a profiler). A JPEG's segments up to its scan, a PNG's chunks up to its
 * first IDAT; GIF, WebP and BMP declare their size in their first bytes, which Skia's own decoders
 * read. Anything else is not read from the prefix.
 */
internal fun holdsWholeHeader(prefix: ByteArray): Boolean {
    fun u8(i: Int) = prefix[i].toInt() and 0xFF
    fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
    fun ascii(i: Int, text: String) = i + text.length <= prefix.size && text.indices.all { prefix[i + it] == text[it].code.toByte() }
    return when {
        prefix.size >= 2 && u8(0) == 0xFF && u8(1) == 0xD8 -> {
            // Segments: FF, marker, two bytes of length (counting themselves); the scan (FFDA) ends the header
            var i = 2
            while (i + 4 <= prefix.size) {
                if (u8(i) != 0xFF) return false
                val marker = u8(i + 1)
                if (marker == 0xFF) {
                    i++
                    continue
                }
                if (marker == 0xDA) return true
                i += 2 + u16(i + 2)
            }
            false
        }
        ascii(0, "\u0089PNG\r\n\u001A\n") -> {
            var i = 8
            while (i + 8 <= prefix.size) {
                if (ascii(i + 4, "IDAT")) return true
                i += 12 + u32(i).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (i < 0) return false
            }
            false
        }
        ascii(0, "GIF8") || (ascii(0, "RIFF") && ascii(8, "WEBP")) || ascii(0, "BM") -> true
        else -> false
    }
}
