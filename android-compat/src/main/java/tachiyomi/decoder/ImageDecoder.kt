// ZOUKAN -->
package tachiyomi.decoder

import android.graphics.Bitmap
import android.graphics.Rect
import xyz.nulldev.androidcompat.graphics.ImageIoDecoding
import java.awt.Rectangle
import java.io.InputStream

/**
 * Desktop stand-in for tachiyomiorg/image-decoder, which the Android app ships and extensions call
 * (keiyoushi's image helpers, for one). It has the native library's Kotlin API and decodes with
 * ImageIO: JPEG, PNG, GIF (first frame), BMP and WebP; formats ImageIO cannot read (AVIF, HEIF,
 * JPEG XL) make [newInstance] return null, as the native decoder does for an image it cannot read.
 *
 * [width] and [height] are those of the image after [cropBorders], and [decode] regions are in
 * that cropped image.
 */
class ImageDecoder private constructor(
    private var bytes: ByteArray?,
    private val crop: Rectangle,
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
        return runCatching { Bitmap(ImageIoDecoding.decode(data, source, sampleSize)) }.getOrNull()
    }

    /** Frees the decoder; [decode] returns null afterwards. */
    fun recycle() {
        bytes = null
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
            runCatching {
                val bytes = stream.readBytes()
                val crop =
                    if (cropBorders) {
                        ImageIoDecoding.contentBounds(ImageIoDecoding.decode(bytes))
                    } else {
                        val (w, h) = ImageIoDecoding.size(bytes)
                        Rectangle(0, 0, w, h)
                    }
                if (crop.width <= 0 || crop.height <= 0) null else ImageDecoder(bytes, crop)
            }.getOrNull()

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

internal object ImageFormats {
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
            at(0, 0xFF, 0x0A) || at(0, 0x00, 0x00, 0x00, 0x0C, 'J'.code, 'X'.code, 'L'.code, ' '.code) -> Format.Jxl
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> Format.Avif
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1")) -> Format.Heif
            else -> null
        }
    }
}
// ZOUKAN <--
