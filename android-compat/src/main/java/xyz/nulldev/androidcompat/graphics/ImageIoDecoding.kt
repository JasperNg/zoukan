// ZOUKAN -->
package xyz.nulldev.androidcompat.graphics

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.MemoryCacheImageInputStream
import org.w3c.dom.Node

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
        // An in-memory cache: ImageIO.createImageInputStream may spill the page to a temp file
        val input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
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

    /**
     * The size of the canvas the frames of [bytes] are drawn on: a GIF's logical screen, otherwise
     * the first image's size.
     */
    fun canvasSize(bytes: ByteArray): Pair<Int, Int> =
        withReader(bytes) { reader ->
            val screen =
                runCatching {
                    val root = reader.streamMetadata?.getAsTree(GIF_STREAM_FORMAT) as? Node
                    val descriptor = root?.child("LogicalScreenDescriptor")
                    val w = descriptor?.attr("logicalScreenWidth")?.toIntOrNull() ?: 0
                    val h = descriptor?.attr("logicalScreenHeight")?.toIntOrNull() ?: 0
                    if (w > 0 && h > 0) w to h else null
                }.getOrNull()
            screen ?: (reader.getWidth(0) to reader.getHeight(0))
        }

    /**
     * Draws the frames of an animation one after another on a [width] x [height] canvas, as a
     * player shows them: ImageIO gives each GIF frame at its own size, at an offset, to be drawn
     * over what the previous frames left (by their disposal method).
     */
    class FrameCompositor(
        private val bytes: ByteArray,
        private val width: Int,
        private val height: Int,
    ) {
        private val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        private var nextIndex = 0

        // What to do to the canvas before drawing the next frame
        private var pendingDispose: (() -> Unit)? = null

        /** Frame [index] composited, as a new width x height image; frames must come in order. */
        fun frame(index: Int): BufferedImage {
            require(index == nextIndex) { "Frames are composited in order: expected $nextIndex, got $index" }
            nextIndex++
            val (image, placement) =
                withReader(bytes) { reader ->
                    val image = reader.read(index)
                    image to placementOf(reader, index, image)
                }
            pendingDispose?.invoke()
            pendingDispose = null
            val previous = if (placement.disposal == "restoreToPrevious") copy(canvas) else null
            val graphics = canvas.createGraphics()
            try {
                graphics.drawImage(image, placement.x, placement.y, null)
            } finally {
                graphics.dispose()
            }
            pendingDispose =
                when (placement.disposal) {
                    "restoreToBackgroundColor" -> {
                        { clear(placement.x, placement.y, image.width, image.height) }
                    }
                    "restoreToPrevious" -> {
                        { previous?.let { canvas.data = it.data } }
                    }
                    else -> null
                }
            return copy(canvas)
        }

        private fun clear(x: Int, y: Int, w: Int, h: Int) {
            val graphics = canvas.createGraphics()
            try {
                graphics.composite = java.awt.AlphaComposite.Clear
                graphics.fillRect(x, y, w, h)
            } finally {
                graphics.dispose()
            }
        }

        private fun copy(image: BufferedImage): BufferedImage =
            BufferedImage(image.colorModel, image.copyData(null), image.isAlphaPremultiplied, null)
    }

    private class Placement(val x: Int, val y: Int, val disposal: String?)

    /** Where frame [index] goes on the canvas, and its disposal method (GIF metadata; 0,0 otherwise). */
    private fun placementOf(reader: ImageReader, index: Int, image: BufferedImage): Placement =
        runCatching {
            val root = reader.getImageMetadata(index)?.getAsTree(GIF_IMAGE_FORMAT) as? Node ?: return@runCatching null
            val descriptor = root.child("ImageDescriptor")
            val control = root.child("GraphicControlExtension")
            Placement(
                descriptor?.attr("imageLeftPosition")?.toIntOrNull() ?: 0,
                descriptor?.attr("imageTopPosition")?.toIntOrNull() ?: 0,
                control?.attr("disposalMethod"),
            )
        }.getOrNull() ?: Placement(0, 0, null)

    private fun Node.child(name: String): Node? {
        var node = firstChild
        while (node != null) {
            if (node.nodeName == name) return node
            node = node.nextSibling
        }
        return null
    }

    private fun Node.attr(name: String): String? = attributes?.getNamedItem(name)?.nodeValue

    private const val GIF_STREAM_FORMAT = "javax_imageio_gif_stream_1.0"
    private const val GIF_IMAGE_FORMAT = "javax_imageio_gif_image_1.0"

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
