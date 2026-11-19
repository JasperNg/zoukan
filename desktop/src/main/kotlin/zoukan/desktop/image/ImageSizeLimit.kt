package zoukan.desktop.image

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * Fails with an [UnreadableImageException] when [bytes] declare an image
 * too large for the decoder that will read it, read from the header before anything that size is
 * allocated: the reader's bounds ([PageSizeLimits.checkDeclaredSize]), for every other image from
 * outside (covers, page previews, a picked cover, pages to combine or copy). None of their decoders
 * subsamples: Coil's Skia decoder decodes the whole image before it scales it, and FFmpeg and
 * ImageIO decode whole images too, so the declared size is what is allocated. Returns the declared
 * size, null when no reader knows it.
 */
internal fun checkImageSize(bytes: ByteArray): Pair<Int, Int>? =
    PageSizeLimits.checkDeclaredSize(bytes) { localized(DMR.strings.the_image_is_too_large) }

/**
 * First among Coil's decoders: refuses an image whose declared size is too large ([checkImageSize]),
 * a cover or a page preview, and otherwise leaves it to the next decoder with its bytes untouched.
 */
internal class DeclaredSizeCheck : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
        checkImageSize(result.source.source().peek().readByteArray())
        return null
    }
}
