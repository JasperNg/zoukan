// ZOUKAN -->
package ca.mpreg.imagedecoder

import xyz.nulldev.androidcompat.graphics.ImageIoDecoding
import java.awt.Rectangle
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Desktop stand-in for the ca.mpreg fork of the image decoder (frame by frame, with HDR support)
 * that newer Android apps ship and extensions call (one extension's image helpers, for one). It decodes
 * with ImageIO: JPEG, PNG, GIF, BMP and WebP, never as HDR.
 *
 * Each [Frame] holds its pixels as Android's ARGB_8888 does in memory: R, G, B, A bytes per pixel,
 * alpha premultiplied, row after row, so Bitmap.copyPixelsFromBuffer can take them.
 */
class ImageDecoder private constructor(
    private var bytes: ByteArray?,
    private val crop: Rectangle,
    /** How many frames the image has: more than one for an animated GIF or WebP. */
    val frameCount: Int,
) : Closeable {
    val width: Int = crop.width
    val height: Int = crop.height

    /** Always false: ImageIO decodes 8 bits per channel. */
    val isHdr: Boolean = false

    val isAnimated: Boolean get() = frameCount > 1

    private var nextFrame = 0

    // Draws animation frames, which ImageIO gives at their own size and offset, on the full canvas
    private val compositor =
        if (frameCount > 1) ImageIoDecoding.FrameCompositor(bytes!!, crop.width, crop.height) else null

    /** Decodes the next frame; throws once all frames were decoded or the decoder was closed. */
    fun decodeNext(): Frame {
        val data = bytes ?: throw IllegalStateException("The decoder is closed")
        if (nextFrame >= frameCount) throw NoSuchElementException("No more frames")
        val index = nextFrame++
        // Every frame of an animation is the whole canvas; crop only applies to still images
        val image = compositor?.frame(index) ?: ImageIoDecoding.decode(data, crop, 1, index)
        val width = image.width
        val height = image.height
        ImageIoDecoding.checkSize(width, height)
        val pixels = IntArray(Math.multiplyExact(width, height))
        image.getRGB(0, 0, width, height, pixels, 0, width)
        val buffer = ByteBuffer.allocateDirect(Math.multiplyExact(pixels.size, 4))
        for (argb in pixels) {
            val a = argb ushr 24
            buffer.put(premultiply((argb shr 16) and 0xFF, a))
            buffer.put(premultiply((argb shr 8) and 0xFF, a))
            buffer.put(premultiply(argb and 0xFF, a))
            buffer.put(a.toByte())
        }
        buffer.flip()
        return Frame(width, height, buffer)
    }

    override fun close() {
        bytes = null
    }

    /** One decoded frame: [image] holds [width] x [height] RGBA pixels (see [ImageDecoder]). */
    class Frame(
        val width: Int,
        val height: Int,
        val image: ByteBuffer,
    ) : Closeable {
        override fun close() {}
    }

    companion object {
        /**
         * Reads [stream] (to the end) and returns a decoder for it; throws an IOException when the
         * image cannot be read. [cropBorders] trims plain white or black borders.
         */
        fun open(
            stream: InputStream,
            cropBorders: Boolean = false,
        ): ImageDecoder {
            val bytes = stream.readBytes()
            val frames = runCatching { ImageIoDecoding.frameCount(bytes) }.getOrDefault(1)
            val crop =
                if (cropBorders && frames == 1) {
                    ImageIoDecoding.contentBounds(ImageIoDecoding.decode(bytes))
                } else if (frames > 1) {
                    val (w, h) = ImageIoDecoding.canvasSize(bytes)
                    Rectangle(0, 0, w, h)
                } else {
                    val (w, h) = ImageIoDecoding.size(bytes)
                    Rectangle(0, 0, w, h)
                }
            if (crop.width <= 0 || crop.height <= 0) throw IOException("Empty image")
            return ImageDecoder(bytes, crop, frames)
        }

        private fun premultiply(
            channel: Int,
            alpha: Int,
        ): Byte = (if (alpha == 0xFF) channel else (channel * alpha + 127) / 255).toByte()
    }
}
// ZOUKAN <--
