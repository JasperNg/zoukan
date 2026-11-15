// ZOUKAN -->
package xyz.nulldev.androidcompat.graphics

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * Image decoding on ImageIO (with the TwelveMonkeys and WebP readers on the classpath) for the
 * desktop stand-ins of the native image decoders extensions call: tachiyomi.decoder.ImageDecoder
 * and ca.mpreg.imagedecoder.ImageDecoder.
 */
internal object ImageIoDecoding {
    /** Runs [block] with an ImageIO reader set to [bytes]; throws when no reader knows the format. */
    fun <T> withReader(
        bytes: ByteArray,
        block: (ImageReader) -> T,
    ): T {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw IOException("Cannot read the image")
        input.use {
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw IOException("No decoder for this image format")
            val reader = readers.next()
            try {
                reader.setInput(input, false, false)
                return block(reader)
            } finally {
                reader.dispose()
            }
        }
    }

    /** The width and height of the first image in [bytes]. */
    fun size(bytes: ByteArray): Pair<Int, Int> = withReader(bytes) { it.getWidth(0) to it.getHeight(0) }

    /**
     * Decodes [region] of image [index] in [bytes] (the whole image when null), keeping one pixel in
     * [sampleSize] in each direction, as ARGB.
     */
    fun decode(
        bytes: ByteArray,
        region: Rectangle? = null,
        sampleSize: Int = 1,
        index: Int = 0,
    ): BufferedImage =
        withReader(bytes) { reader ->
            val param = reader.defaultReadParam
            if (region != null) param.sourceRegion = region
            val sample = sampleSize.coerceAtLeast(1)
            if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
            toArgb(reader.read(index, param))
        }

    /** The number of frames in [bytes] (1 for a still image, or when the reader cannot tell). */
    fun frameCount(bytes: ByteArray): Int =
        withReader(bytes) { reader -> runCatching { reader.getNumImages(true) }.getOrDefault(1).coerceAtLeast(1) }

    fun toArgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_ARGB) return image
        val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = argb.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return argb
    }

    /**
     * The part of [image] inside its plain white or black borders, as the native decoder's
     * cropBorders finds it: each side loses the rows or columns that are (almost) all white, or all
     * black. The whole image when cropping would leave less than a quarter of a side.
     */
    fun contentBounds(image: BufferedImage): Rectangle {
        val width = image.width
        val height = image.height
        val whole = Rectangle(0, 0, width, height)
        if (width < 4 || height < 4) return whole

        fun luminance(x: Int, y: Int): Int {
            val rgb = image.getRGB(x, y)
            val alpha = rgb ushr 24
            if (alpha < 0x80) return 0xFF // transparent counts as white paper
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        // A line is border when nearly all of its pixels are white (or all black)
        fun isBorder(
            count: Int,
            white: Boolean,
            pixel: (Int) -> Int,
        ): Boolean {
            val allowed = count / 200 // 0.5% of stray pixels, for scan noise
            var off = 0
            for (i in 0 until count) {
                val lum = pixel(i)
                val matches = if (white) lum >= WHITE_THRESHOLD else lum <= BLACK_THRESHOLD
                if (!matches && ++off > allowed) return false
            }
            return true
        }

        fun rowIsBorder(y: Int, white: Boolean) = isBorder(width, white) { luminance(it, y) }

        fun columnIsBorder(x: Int, top: Int, bottom: Int, white: Boolean) =
            isBorder(bottom - top, white) { luminance(x, top + it) }

        fun colorOf(isLine: (Boolean) -> Boolean): Boolean? =
            when {
                isLine(true) -> true
                isLine(false) -> false
                else -> null
            }

        var top = 0
        colorOf { rowIsBorder(0, it) }?.let { white -> while (top < height - 1 && rowIsBorder(top, white)) top++ }
        var bottom = height
        colorOf { rowIsBorder(height - 1, it) }?.let { white -> while (bottom - 1 > top && rowIsBorder(bottom - 1, white)) bottom-- }
        var left = 0
        colorOf { columnIsBorder(0, top, bottom, it) }?.let { white ->
            while (left < width - 1 && columnIsBorder(left, top, bottom, white)) left++
        }
        var right = width
        colorOf { columnIsBorder(width - 1, top, bottom, it) }?.let { white ->
            while (right - 1 > left && columnIsBorder(right - 1, top, bottom, white)) right--
        }

        if ((right - left) * 4 < width || (bottom - top) * 4 < height) return whole
        return Rectangle(left, top, right - left, bottom - top)
    }

    private const val WHITE_THRESHOLD = 0xAA
    private const val BLACK_THRESHOLD = 0x55
}
// ZOUKAN <--
