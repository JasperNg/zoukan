// ZOUKAN -->
package tachiyomi.decoder

import android.graphics.Bitmap
import android.graphics.Rect
import xyz.nulldev.androidcompat.graphics.ImageIoDecoding
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.InputStream

/**
 * Desktop stand-in for tachiyomiorg/image-decoder, which the Android app ships and extensions call
 * (one extension's image helpers, for one). It has the native library's Kotlin API and decodes with
 * ImageIO: JPEG, PNG, GIF (first frame), BMP and WebP, and AVIF and HEIF where the app registers
 * an ImageIO reader for them; formats no reader knows (JPEG XL) make [newInstance] return null, as
 * the native decoder does for an image it cannot read.
 *
 * [width] and [height] are those of the image after [cropBorders], and [decode] regions are in
 * that cropped image.
 */
class ImageDecoder private constructor(
    private var bytes: ByteArray?,
    private val crop: Rectangle,
    // AVIF and HEIF readers decode the whole image for any region: it is decoded once and kept
    private val decodesWhole: Boolean,
    private var whole: BufferedImage?,
) {
    val width: Int = crop.width
    val height: Int = crop.height

    /**
     * Decodes [region] of the image, keeping one pixel in [sampleSize] in each direction, or returns
     * null when the region is empty or the decoder was recycled.
     */
    fun decode(
        region: Rect = Rect(0, 0, width, height),
        sampleSize: Int = 1,
    ): Bitmap? {
        val data = bytes ?: return null
        val left = region.left.coerceIn(0, width)
        val top = region.top.coerceIn(0, height)
        val right = region.right.coerceIn(left, width)
        val bottom = region.bottom.coerceIn(top, height)
        if (right <= left || bottom <= top) return null
        val source = Rectangle(crop.x + left, crop.y + top, right - left, bottom - top)
        // Only Exceptions are turned into null: an OutOfMemoryError or an interrupt must reach the caller
        return try {
            if (decodesWhole) {
                Bitmap(cut(wholeImage(data), source, sampleSize))
            } else {
                Bitmap(ImageIoDecoding.decode(data, source, sampleSize))
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun wholeImage(data: ByteArray): BufferedImage = whole ?: ImageIoDecoding.decode(data).also { whole = it }

    /** Frees the decoder; [decode] returns null afterwards. */
    @Synchronized
    fun recycle() {
        bytes = null
        whole = null
    }

    companion object {
        /**
         * Reads [stream] (to the end) and returns a decoder for it, or null when the image cannot be
         * read. [cropBorders] trims plain white or black borders. [displayProfile] (an ICC profile
         * to convert to) is ignored: images are decoded in sRGB.
         */
        fun newInstance(
            stream: InputStream,
            cropBorders: Boolean = false,
            @Suppress("UNUSED_PARAMETER") displayProfile: ByteArray? = null,
        ): ImageDecoder? =
            try {
                val bytes = stream.readBytes()
                val decodesWhole = ImageFormats.sniff(bytes).let { it == Format.Avif || it == Format.Heif }
                var whole: BufferedImage? = null
                val crop =
                    if (cropBorders) {
                        val image = ImageIoDecoding.decode(bytes)
                        if (decodesWhole) whole = image
                        ImageIoDecoding.contentBounds(image)
                    } else {
                        // An AVIF or HEIF reader reads the size from the file's header, without decoding
                        val (w, h) = ImageIoDecoding.size(bytes)
                        Rectangle(0, 0, w, h)
                    }
                if (crop.width <= 0 || crop.height <= 0) null else ImageDecoder(bytes, crop, decodesWhole, whole)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                null
            }

        /** [source] of [image] as a new ARGB image, keeping one pixel in [sampleSize] each way, as ImageIO subsamples. */
        internal fun cut(
            image: BufferedImage,
            source: Rectangle,
            sampleSize: Int,
        ): BufferedImage {
            val region = source.intersection(Rectangle(0, 0, image.width, image.height))
            require(!region.isEmpty) { "The region is outside the image" }
            val step = sampleSize.coerceAtLeast(1)
            val w = (region.width + step - 1) / step
            val h = (region.height + step - 1) / step
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val row = IntArray(region.width)
            val sampled = IntArray(w)
            for (y in 0 until h) {
                image.getRGB(region.x, region.y + y * step, region.width, 1, row, 0, region.width)
                for (x in 0 until w) sampled[x] = row[x * step]
                out.setRGB(0, y, w, 1, sampled, 0, w)
            }
            return out
        }

        /** The format of the image in [bytes] from its first bytes, or null when unknown. */
        fun findType(bytes: ByteArray): ImageType? {
            val format = ImageFormats.sniff(bytes) ?: return null
            val animated =
                when (format) {
                    Format.Gif, Format.Webp -> runCatching { ImageIoDecoding.frameCount(bytes) > 1 }.getOrDefault(false)
                    else -> false
                }
            return ImageType(format, animated)
        }
    }
}

data class ImageType(
    val format: Format,
    val isAnimated: Boolean,
)

enum class Format {
    Avif,
    Gif,
    Heif,
    Jpeg,
    Jxl,
    Png,
    Webp,
}

/**
 * The image format of some bytes from their first bytes: the one sniffer the desktop app shares
 * (page file extensions, the reader's errors, the AVIF and HEIF decoder), so they all agree.
 * [HEADER] bytes are enough for every format it knows.
 */
object ImageFormats {
    /** Bytes enough to sniff any format: an ISO-BMFF ftyp box with a few compatible brands. */
    const val HEADER = 64

    // ISO-BMFF brands (major or compatible) of AVIF and HEIF images and image sequences
    private val AVIF_BRANDS = setOf("avif", "avis")
    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx")
    private val GENERIC_BRANDS = setOf("mif1", "msf1")

    fun sniff(bytes: ByteArray): Format? {
        fun at(
            offset: Int,
            vararg expected: Int,
        ) = bytes.size >= offset + expected.size && expected.indices.all { (bytes[offset + it].toInt() and 0xFF) == expected[it] }

        fun ascii(
            offset: Int,
            text: String,
        ) = at(offset, *text.map { it.code }.toIntArray())

        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> Format.Jpeg
            at(0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> Format.Png
            ascii(0, "GIF8") -> Format.Gif
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Format.Webp
            at(0, 0xFF, 0x0A) || at(0, 0x00, 0x00, 0x00, 0x0C, 'J'.code, 'X'.code, 'L'.code, ' '.code, 0x0D, 0x0A, 0x87, 0x0A) -> Format.Jxl
            ascii(4, "ftyp") -> isoBmff(bytes)
            else -> null
        }
    }

    /**
     * AVIF or HEIF by the ftyp box's major brand, then its compatible brands: an AVIF often has the
     * generic major brand mif1 with avif among its compatible brands.
     */
    private fun isoBmff(bytes: ByteArray): Format? {
        fun brand(offset: Int) = String(CharArray(4) { (bytes[offset + it].toInt() and 0xFF).toChar() })
        if (bytes.size < 12) return null
        val boxSize = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
        // Major brand at 8, minor version at 12, compatible brands from 16 to the end of the box
        val end = minOf(if (boxSize >= 16) boxSize else 16L, bytes.size.toLong()).toInt()
        val major = brand(8)
        val compatible = (16..end - 4 step 4).map(::brand)
        fun formatOf(brand: String) =
            when (brand) {
                in AVIF_BRANDS -> Format.Avif
                in HEIF_BRANDS -> Format.Heif
                else -> null
            }
        return formatOf(major)
            ?: compatible.firstOrNull { it in AVIF_BRANDS }?.let { Format.Avif }
            ?: compatible.firstNotNullOfOrNull(::formatOf)
            ?: Format.Heif.takeIf { major in GENERIC_BRANDS || compatible.any { it in GENERIC_BRANDS } }
    }
}
// ZOUKAN <--
