package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageFormats
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.UnreadableImageException
import zoukan.i18n.desktop.DMR
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

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
            if (format == Format.Jxl) throw UnreadableImageException(localized(DMR.strings.unsupported_image_format, JPEG_XL))
            val declared = checkDeclaredSize(bytes)
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
                // HEIF reader covers a few more. Their pages go the same way, cropped as the others. Read
                // through a cache in memory: ImageIO's default spills the page to a temporary file.
                // ImageIO decodes on the Java heap, which holds less than Skia's native memory
                if (declared != null && isTooLarge(declared.first, declared.second, imageIoPixelLimit())) {
                    throw UnreadableImageException(localized(DMR.strings.the_page_is_too_large_to_show), e)
                }
                val buffered = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))
                    ?: throw UnreadableImageException(localized(DMR.strings.unsupported_image_format, sniff(bytes)), e)
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
        /**
         * The most pixels a page may declare on Skia's path (JPEG, PNG, WebP, GIF, BMP): WebP's
         * largest, 16384 by 16384, so every page Skia showed before still shows. Skia decodes the
         * page whole, in native memory outside the Java heap, and only then scales it down to the
         * window, so the declared size is what is allocated. Android shows such pages through its
         * subsampling view; a header that declares far more (a JPEG of 60000 by 60000) is no page,
         * and allocating it would end the session.
         */
        internal const val MAX_DECODED_PIXELS = 16_384L * 16_384

        /**
         * The longest side a page may declare on ImageIO's path: JPEG's largest, as the Android
         * layer's ImageIoDecoding.MAX_SIDE. Skia's path has no side limit of its own (a PNG strip of
         * 720 by 88,000 shows): there only [MAX_DECODED_PIXELS] applies.
         */
        internal const val MAX_PAGE_SIDE = 65_535

        /**
         * Bytes of Java heap a pixel takes on ImageIO's path at its peak: the decoded image (up to
         * 4 bytes a pixel) and its copy into a Skia bitmap (4 more).
         */
        private const val IMAGE_IO_BYTES_PER_PIXEL = 8L

        /**
         * The most pixels a page may declare on ImageIO's path (TIFF, AVIF, HEIF...), which decodes
         * on the Java heap: what fits in half of [maxHeap], and never more than [MAX_DECODED_PIXELS].
         */
        internal fun imageIoPixelLimit(maxHeap: Long = Runtime.getRuntime().maxMemory()): Long =
            minOf(MAX_DECODED_PIXELS, maxHeap / 2 / IMAGE_IO_BYTES_PER_PIXEL)

        internal fun isTooLarge(width: Int, height: Int, maxPixels: Long, maxSide: Int = MAX_PAGE_SIDE): Boolean =
            width > maxSide || height > maxSide || width.toLong() * height > maxPixels

        /**
         * Fails with a page error when [bytes] declare a page too large for the decoder that will
         * read it ([MAX_DECODED_PIXELS] for Skia, [imageIoPixelLimit] and [MAX_PAGE_SIDE] for ImageIO), read from the
         * image's header before anything that size is allocated. Returns the declared size, null
         * when no reader knows it.
         */
        internal fun checkDeclaredSize(bytes: ByteArray): Pair<Int, Int>? {
            skiaSize(bytes)?.let { (width, height) ->
                if (isTooLarge(width, height, MAX_DECODED_PIXELS, maxSide = Int.MAX_VALUE)) throw UnreadableImageException(localized(DMR.strings.the_page_is_too_large_to_show))
                return width to height
            }
            val (width, height) = imageIoSize(bytes) ?: return null
            if (isTooLarge(width, height, imageIoPixelLimit())) throw UnreadableImageException(localized(DMR.strings.the_page_is_too_large_to_show))
            return width to height
        }

        /** The width and height the image in [bytes] declares, without decoding it; null when no reader knows it. */
        internal fun declaredSize(bytes: ByteArray): Pair<Int, Int>? = skiaSize(bytes) ?: imageIoSize(bytes)

        /** The size Skia's codec reads from the header; null when Skia does not read the type. */
        private fun skiaSize(bytes: ByteArray): Pair<Int, Int>? = try {
            Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { it.width to it.height } }
        } catch (_: RuntimeException) {
            // Not a type Skia reads (it fails with an unchecked exception)
            null
        }

        /** The size ImageIO's readers read from the header; null when none knows the type. */
        private fun imageIoSize(bytes: ByteArray): Pair<Int, Int>? = try {
            // A cache in memory: ImageIO.createImageInputStream may spill the page to a temporary file
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
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
