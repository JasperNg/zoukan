package zoukan.desktop.image

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import kotlinx.coroutines.runInterruptible
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Coil's decoder for AVIF and HEIF covers and previews, which Coil's Skia decoder cannot read.
 * Other images go on to the next decoder.
 */
class FfmpegCoilDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible {
        // Covers from the disk cache are files already
        val decoded = source.fileOrNull()?.toFile()?.let(FfmpegImageDecoder::decode)
            ?: FfmpegImageDecoder.decode(source.source().readByteArray())
        val image = downscaled(decoded)
        DecodeResult(image = image.toComposeImageBitmap().asSkiaBitmap().asImage(), isSampled = image !== decoded)
    }

    /** The image at the size asked for when it is much larger, as Coil's Skia decoder samples. */
    private fun downscaled(image: BufferedImage): BufferedImage {
        val size = options.size
        val width = (size.width as? Dimension.Pixels)?.px
        val height = (size.height as? Dimension.Pixels)?.px
        if (width == null && height == null) return image
        val ratios = listOfNotNull(width?.let { it.toDouble() / image.width }, height?.let { it.toDouble() / image.height })
        // Filling crops, so the image must cover both sides; fitting needs it inside both
        val scale = if (options.scale == Scale.FILL) ratios.max() else ratios.min()
        if (scale > 0.5) return image
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { out ->
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val peek = result.source.source().peek()
            // Twelve bytes hold the ftyp box's brand; a shorter file is no AVIF or HEIF
            if (!peek.request(HEADER)) return null
            if (!FfmpegImageDecoder.handles(peek.readByteArray(HEADER))) return null
            return FfmpegCoilDecoder(result.source, options)
        }
    }

    private companion object {
        const val HEADER = 12L
    }
}
