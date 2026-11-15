package zoukan.desktop.image

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream

/**
 * ImageIO's reader for AVIF and HEIF, through [FfmpegImageDecoder]. Registered in
 * META-INF/services, so every place that falls back to ImageIO (the reader, page actions, the
 * tall image splitter) reads these formats too.
 */
class FfmpegImageReaderSpi :
    ImageReaderSpi(
        "Zoukan",
        "1.0",
        arrayOf("avif", "AVIF", "heif", "HEIF", "heic", "HEIC"),
        arrayOf("avif", "heif", "heic"),
        arrayOf("image/avif", "image/heif", "image/heic"),
        FfmpegImageReader::class.java.name,
        arrayOf(ImageInputStream::class.java),
        null,
        false,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
    ) {
    override fun canDecodeInput(source: Any?): Boolean {
        val input = source as? ImageInputStream ?: return false
        val header = ByteArray(HEADER)
        input.mark()
        return try {
            var read = 0
            while (read < HEADER) {
                val n = input.read(header, read, HEADER - read)
                if (n < 0) break
                read += n
            }
            FfmpegImageDecoder.handles(header.copyOf(read))
        } finally {
            input.reset()
        }
    }

    override fun createReaderInstance(extension: Any?): ImageReader = FfmpegImageReader(this)

    override fun getDescription(locale: Locale?): String = "AVIF and HEIF reader (FFmpeg)"

    private companion object {
        const val HEADER = 12
    }
}

/** Decodes the whole image once, on first use; a source region is cut from it. */
class FfmpegImageReader(spi: ImageReaderSpi) : ImageReader(spi) {
    private var image: BufferedImage? = null

    override fun setInput(input: Any?, seekForwardOnly: Boolean, ignoreMetadata: Boolean) {
        super.setInput(input, seekForwardOnly, ignoreMetadata)
        image = null
    }

    private fun decoded(index: Int): BufferedImage {
        if (index != 0) throw IndexOutOfBoundsException("Only image 0, not $index")
        image?.let { return it }
        val stream = input as? ImageInputStream ?: throw IllegalStateException("No input")
        val bytes = ByteArrayOutputStream().also { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }.toByteArray()
        return FfmpegImageDecoder.decode(bytes).also { image = it }
    }

    override fun getNumImages(allowSearch: Boolean): Int = 1

    override fun getWidth(imageIndex: Int): Int = decoded(imageIndex).width

    override fun getHeight(imageIndex: Int): Int = decoded(imageIndex).height

    override fun getImageTypes(imageIndex: Int): Iterator<ImageTypeSpecifier> =
        listOf(ImageTypeSpecifier.createFromRenderedImage(decoded(imageIndex))).iterator()

    override fun getStreamMetadata(): IIOMetadata? = null

    override fun getImageMetadata(imageIndex: Int): IIOMetadata? = null

    override fun read(imageIndex: Int, param: ImageReadParam?): BufferedImage {
        val image = decoded(imageIndex)
        val region = param?.sourceRegion?.intersection(java.awt.Rectangle(0, 0, image.width, image.height))
            ?: return image
        if (region.isEmpty) throw IllegalArgumentException("The source region is outside the image")
        return image.getSubimage(region.x, region.y, region.width, region.height)
    }

    override fun dispose() {
        image = null
    }
}
