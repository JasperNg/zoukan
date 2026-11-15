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
import kotlinx.coroutines.runInterruptible

/**
 * Coil's decoder for AVIF and HEIF covers and previews, which Coil's Skia decoder cannot read.
 * Other images go on to the next decoder.
 */
class FfmpegCoilDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible {
        val bytes = source.source().readByteArray()
        val bitmap = FfmpegImageDecoder.decode(bytes).toComposeImageBitmap().asSkiaBitmap()
        DecodeResult(image = bitmap.asImage(), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val peek = result.source.source().peek()
            // Twelve bytes hold the ftyp box's brand; a shorter file is no AVIF or HEIF
            if (!peek.request(HEADER)) return null
            if (!FfmpegImageDecoder.handles(peek.readByteArray(HEADER))) return null
            return FfmpegCoilDecoder(result.source)
        }
    }

    private companion object {
        const val HEADER = 12L
    }
}
