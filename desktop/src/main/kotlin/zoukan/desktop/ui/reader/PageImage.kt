package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A decoded page. Tall pages (webtoon strips) are cut into slices no taller than [MAX_SLICE]
 * pixels, because a GPU texture has a size limit; the reader stacks the slices.
 */
class PageImage(
    val width: Int,
    val height: Int,
    val slices: List<ImageBitmap>,
    // ZOUKAN -->
    /** The page's own background for the Automatic reader theme, read when it decodes */
    val background: PageBackground? = null,
    // ZOUKAN <--
) {
    val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1)

    companion object {
        const val MAX_SLICE = 4096

        fun decode(bytes: ByteArray, cropBorders: Boolean = false): PageImage {
            if (cropBorders) {
                runCatching { decodeCropped(bytes) }.getOrNull()?.let { return it }
            }
            val image = try {
                Image.makeFromEncoded(bytes)
            } catch (e: Exception) {
                // Skia reads JPEG, PNG, WebP, GIF and BMP; ImageIO with TwelveMonkeys covers a few more
                val buffered = ImageIO.read(ByteArrayInputStream(bytes))
                    ?: throw IllegalArgumentException(localized(DMR.strings.unsupported_image_format, sniff(bytes)), e)
                val bitmap = buffered.toComposeImageBitmap()
                // ZOUKAN -->
                val background = background(buffered.width, buffered.height) { x, y -> buffered.getRGB(x, y) }
                if (buffered.height <= MAX_SLICE) return PageImage(buffered.width, buffered.height, listOf(bitmap), background)
                return slice(buffered.width, buffered.height, background) { y, h ->
                    buffered.getSubimage(0, y, buffered.width, h).toComposeImageBitmap()
                }
                // ZOUKAN <--
            }
            val width = image.width
            val height = image.height
            // ZOUKAN -->
            val bitmap = Bitmap.makeFromImage(image)
            image.close()
            val background = background(width, height) { x, y -> bitmap.getColor(x, y) }
            if (height <= MAX_SLICE) {
                return PageImage(width, height, listOf(bitmap.asComposeImageBitmap()), background)
            }
            return slice(width, height, background) { y, h ->
                // ZOUKAN <--
                val part = Bitmap()
                bitmap.extractSubset(part, IRect.makeXYWH(0, y, width, h))
                part.asComposeImageBitmap()
            }
        }

        /**
         * Decodes with the page's plain margins cut off, as Android's "crop borders": rows and
         * columns at the edges that are all one color (near white or near black) go.
         */
        private fun decodeCropped(bytes: ByteArray): PageImage? {
            val image = Image.makeFromEncoded(bytes)
            val bitmap = Bitmap.makeFromImage(image)
            image.close()
            val rect = contentRect(bitmap) ?: return null
            if (rect.width == bitmap.width && rect.height == bitmap.height) return null
            val width = rect.width
            val height = rect.height
            // ZOUKAN -->
            val background = background(bitmap.width, bitmap.height) { x, y -> bitmap.getColor(x, y) }
            return if (height <= MAX_SLICE) {
                val part = Bitmap()
                bitmap.extractSubset(part, rect)
                PageImage(width, height, listOf(part.asComposeImageBitmap()), background)
            } else {
                slice(width, height, background) { y, h ->
                    // ZOUKAN <--
                    val part = Bitmap()
                    bitmap.extractSubset(part, IRect.makeXYWH(rect.left, rect.top + y, width, h))
                    part.asComposeImageBitmap()
                }
            }
        }

        /** The part of [bitmap] inside its plain borders, or null when it is all border. */
        internal fun contentRect(bitmap: Bitmap): IRect? {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 8 || h < 8) return null
            val corner = bitmap.getColor(0, 0)
            if (!isPlain(corner)) return null
            fun same(c: Int) = close(c, corner)
            // Sample every few pixels: margins are wide and pages are large
            val step = maxOf(1, minOf(w, h) / 400)
            fun rowPlain(y: Int) = (0 until w step step).all { same(bitmap.getColor(it, y)) }
            fun colPlain(x: Int, top: Int, bottom: Int) = (top until bottom step step).all { same(bitmap.getColor(x, it)) }
            var top = 0
            while (top < h && rowPlain(top)) top++
            if (top >= h) return null
            var bottom = h - 1
            while (bottom > top && rowPlain(bottom)) bottom--
            var left = 0
            while (left < w && colPlain(left, top, bottom + 1)) left++
            var right = w - 1
            while (right > left && colPlain(right, top, bottom + 1)) right--
            // Keep a small margin, as Android does
            val pad = maxOf(2, minOf(w, h) / 200)
            val l = (left - pad).coerceAtLeast(0)
            val t = (top - pad).coerceAtLeast(0)
            val r = (right + pad).coerceAtMost(w - 1)
            val b = (bottom + pad).coerceAtMost(h - 1)
            return IRect.makeLTRB(l, t, r + 1, b + 1)
        }

        private fun channels(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

        private fun isPlain(c: Int): Boolean {
            val (r, g, b) = channels(c)
            return (r > 225 && g > 225 && b > 225) || (r < 30 && g < 30 && b < 30)
        }

        private fun close(a: Int, b: Int): Boolean {
            val x = channels(a)
            val y = channels(b)
            return (0..2).all { kotlin.math.abs(x[it] - y[it]) < 26 }
        }

        // ZOUKAN -->
        private fun slice(width: Int, height: Int, background: PageBackground?, cut: (y: Int, h: Int) -> ImageBitmap): PageImage {
            val slices = (0 until height step MAX_SLICE).map { y -> cut(y, minOf(MAX_SLICE, height - y)) }
            return PageImage(width, height, slices, background)
        }

        /** A few hundred pixels are read, so every page gets one; only the Automatic theme shows it */
        private fun background(width: Int, height: Int, pixel: (Int, Int) -> Int): PageBackground? =
            runCatching { PageBackground.of(width, height, pixel) }.getOrNull()
        // ZOUKAN <--

        private fun sniff(bytes: ByteArray): String {
            fun at(i: Int, text: String) = bytes.size >= i + text.length &&
                text.indices.all { bytes[i + it] == text[it].code.toByte() }
            return when {
                at(4, "ftypavif") || at(4, "ftypavis") -> "AVIF"
                at(4, "ftypheic") || at(4, "ftypmif1") -> "HEIF"
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0x0A.toByte() -> "JPEG XL"
                at(0, "<") || at(0, "\n<") -> localized(DMR.strings.an_html_page_not_an_image)
                else -> localized(DMR.strings.bytes, bytes.size)
            }
        }
    }
}
