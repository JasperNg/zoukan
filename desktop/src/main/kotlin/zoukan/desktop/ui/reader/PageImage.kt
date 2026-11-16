package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageFormats
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
    slices: List<ImageBitmap>,
    // ZOUKAN -->
    /** The page's own background for the Automatic reader theme, read when it decodes */
    val background: PageBackground? = null,
    /** The animation of an animated GIF or WebP page, which the page's composable plays */
    val animation: PageAnimation? = null,
    /** The Skia bitmaps behind the slices, freed once the image is released and no longer drawn */
    private val owned: List<Bitmap> = emptyList(),
    // ZOUKAN <--
) {
    // ZOUKAN -->
    private val stillSlices = slices

    /** The slices to draw: an animated page's current frame (snapshot state, so drawing follows it) */
    val slices: List<ImageBitmap> get() = animation?.let { listOf(it.frame) } ?: if (closed) emptyList() else stillSlices

    // The model releases the image and the composables that draw it let go of it; the native pixels
    // are freed once both have happened, so a bitmap is never closed while it is drawn
    private val lock = Any()
    private var users = 0
    private var released = false

    @Volatile
    private var closed = false

    /** A composable starts drawing this image */
    fun attach() = synchronized(lock) { users++ }

    /** A composable stops drawing this image */
    fun detach() = synchronized(lock) {
        users--
        closeIfUnused()
    }

    /** The model no longer keeps this image: it closes as soon as nothing draws it */
    fun release() = synchronized(lock) {
        released = true
        closeIfUnused()
    }

    private fun closeIfUnused() {
        if (!released || users > 0 || closed) return
        closed = true
        owned.forEach { runCatching { it.close() } }
    }
    // ZOUKAN <--

    val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1)

    companion object {
        const val MAX_SLICE = 4096

        fun decode(bytes: ByteArray, cropBorders: Boolean = false, maxWidth: Int? = null): PageImage {
            // ZOUKAN -->
            val format = ImageFormats.sniff(bytes)
            // Neither Skia, ImageIO nor the bundled FFmpeg (no libjxl) reads JPEG XL: say so plainly
            if (format == Format.Jxl) throw IllegalArgumentException(localized(DMR.strings.unsupported_image_format, JPEG_XL))
            // Animated GIF and WebP pages play, as on Android; they are not cropped, nor given a background
            if (format == Format.Gif || format == Format.Webp) {
                PageAnimation.of(bytes)?.let { return PageImage(it.width, it.height, emptyList(), null, it) }
            }
            val full = try {
                val image = Image.makeFromEncoded(bytes)
                try {
                    Bitmap.makeFromImage(image)
                } finally {
                    image.close()
                }
            } catch (e: Exception) {
                // Skia reads JPEG, PNG, WebP, GIF and BMP; ImageIO with TwelveMonkeys and the AVIF and
                // HEIF reader covers a few more. Their pages go the same way, cropped as the others
                val buffered = ImageIO.read(ByteArrayInputStream(bytes))
                    ?: throw IllegalArgumentException(localized(DMR.strings.unsupported_image_format, sniff(bytes)), e)
                buffered.toComposeImageBitmap().asSkiaBitmap()
            }
            // A page far wider than the window can show is scaled down first, and the full one freed
            val bitmap = if (maxWidth != null && maxWidth > 0 && full.width > maxWidth) {
                try {
                    scaledTo(full, maxWidth)
                } finally {
                    full.close()
                }
            } else {
                full
            }
            val width = bitmap.width
            val height = bitmap.height
            // The one decoded bitmap is cropped, or shown whole when it has no plain borders
            if (cropBorders) {
                runCatching { cropped(bitmap) }.getOrNull()?.let { return it }
            }
            val background = background(width, height) { x, y -> bitmap.getColor(x, y) }
            if (height <= MAX_SLICE) {
                return PageImage(width, height, listOf(bitmap.asComposeImageBitmap()), background, owned = listOf(bitmap))
            }
            val owned = mutableListOf(bitmap)
            return slice(width, height, background, owned) { y, h ->
                // ZOUKAN <--
                val part = Bitmap()
                bitmap.extractSubset(part, IRect.makeXYWH(0, y, width, h))
                owned += part
                part.asComposeImageBitmap()
            }
        }

        // ZOUKAN -->
        /** [source] scaled to [width] pixels wide, keeping its aspect */
        private fun scaledTo(source: Bitmap, width: Int): Bitmap {
            val height = (source.height.toLong() * width / source.width).toInt().coerceAtLeast(1)
            val target = Bitmap()
            target.allocPixels(ImageInfo.makeN32Premul(width, height))
            val image = Image.makeFromBitmap(source)
            try {
                val scaled = target.peekPixels()?.let { image.scalePixels(it, SamplingMode.MITCHELL, false) } ?: false
                if (!scaled) {
                    target.close()
                    throw IllegalStateException("The page could not be scaled")
                }
            } finally {
                image.close()
            }
            return target
        }
        // ZOUKAN <--

        /**
         * [bitmap] with the page's plain margins cut off, as Android's "crop borders": rows and
         * columns at the edges that are all one color (near white or near black) go.
         */
        private fun cropped(bitmap: Bitmap): PageImage? {
            val rect = contentRect(bitmap) ?: return null
            if (rect.width == bitmap.width && rect.height == bitmap.height) return null
            val width = rect.width
            val height = rect.height
            // ZOUKAN -->
            val background = background(bitmap.width, bitmap.height) { x, y -> bitmap.getColor(x, y) }
            return if (height <= MAX_SLICE) {
                val part = Bitmap()
                bitmap.extractSubset(part, rect)
                PageImage(width, height, listOf(part.asComposeImageBitmap()), background, owned = listOf(bitmap, part))
            } else {
                val owned = mutableListOf(bitmap)
                slice(width, height, background, owned) { y, h ->
                    // ZOUKAN <--
                    val part = Bitmap()
                    bitmap.extractSubset(part, IRect.makeXYWH(rect.left, rect.top + y, width, h))
                    owned += part
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
        private fun slice(width: Int, height: Int, background: PageBackground?, owned: List<Bitmap>, cut: (y: Int, h: Int) -> ImageBitmap): PageImage {
            val slices = (0 until height step MAX_SLICE).map { y -> cut(y, minOf(MAX_SLICE, height - y)) }
            return PageImage(width, height, slices, background, owned = owned)
        }

        /** A few hundred pixels are read, so every page gets one; only the Automatic theme shows it */
        private fun background(width: Int, height: Int, pixel: (Int, Int) -> Int): PageBackground? =
            runCatching { PageBackground.of(width, height, pixel) }.getOrNull()
        // ZOUKAN <--

        private const val JPEG_XL = "JPEG XL"

        private fun sniff(bytes: ByteArray): String {
            fun at(i: Int, text: String) = bytes.size >= i + text.length &&
                text.indices.all { bytes[i + it] == text[it].code.toByte() }
            // ZOUKAN -->
            return when (ImageFormats.sniff(bytes)) {
                Format.Avif -> "AVIF"
                Format.Heif -> "HEIF"
                Format.Jxl -> JPEG_XL
                else -> null
            } ?: when {
                // ZOUKAN <--
                at(0, "<") || at(0, "\n<") -> localized(DMR.strings.an_html_page_not_an_image)
                else -> localized(DMR.strings.bytes, bytes.size)
            }
        }
    }
}
