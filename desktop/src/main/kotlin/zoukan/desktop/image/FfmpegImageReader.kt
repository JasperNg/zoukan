package zoukan.desktop.image

import tachiyomi.decoder.ImageFormats
import java.awt.Rectangle
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
        // The ftyp box with its compatible brands, where an AVIF with the mif1 major brand says it is AVIF
        const val HEADER = ImageFormats.HEADER
    }
}

/**
 * Decodes the whole image once, on first use, and keeps it: source regions and subsampling are
 * cut from it. The size comes from the file's header when it tells it, so asking for it decodes
 * nothing.
 */
class FfmpegImageReader(spi: ImageReaderSpi) : ImageReader(spi) {
    private var bytes: ByteArray? = null
    private var image: BufferedImage? = null

    override fun setInput(input: Any?, seekForwardOnly: Boolean, ignoreMetadata: Boolean) {
        super.setInput(input, seekForwardOnly, ignoreMetadata)
        bytes = null
        image = null
    }

    private fun bytes(): ByteArray {
        bytes?.let { return it }
        val stream = input as? ImageInputStream ?: throw IllegalStateException("No input")
        return ByteArrayOutputStream().also { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }.toByteArray().also { bytes = it }
    }

    private fun decoded(index: Int): BufferedImage {
        checkIndex(index)
        image?.let { return it }
        return FfmpegImageDecoder.decode(bytes()).also { image = it }
    }

    private fun checkIndex(index: Int) {
        if (index != 0) throw IndexOutOfBoundsException("Only image 0, not $index")
    }

    /** The size from the header, or from the decoded image when the header does not tell it. */
    private fun size(index: Int): Pair<Int, Int> {
        checkIndex(index)
        image?.let { return it.width to it.height }
        return HeifHeader.primaryImageSize(bytes()) ?: decoded(index).let { it.width to it.height }
    }

    override fun getNumImages(allowSearch: Boolean): Int = 1

    override fun getWidth(imageIndex: Int): Int = size(imageIndex).first

    override fun getHeight(imageIndex: Int): Int = size(imageIndex).second

    override fun getImageTypes(imageIndex: Int): Iterator<ImageTypeSpecifier> {
        checkIndex(imageIndex)
        // Decoded images are always ARGB
        return listOf(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)).iterator()
    }

    override fun getStreamMetadata(): IIOMetadata? = null

    override fun getImageMetadata(imageIndex: Int): IIOMetadata? = null

    override fun read(imageIndex: Int, param: ImageReadParam?): BufferedImage {
        val image = decoded(imageIndex)
        val whole = Rectangle(0, 0, image.width, image.height)
        val region = param?.sourceRegion?.intersection(whole) ?: whole
        if (region.isEmpty) throw IllegalArgumentException("The source region is outside the image")
        val stepX = param?.sourceXSubsampling?.coerceAtLeast(1) ?: 1
        val stepY = param?.sourceYSubsampling?.coerceAtLeast(1) ?: 1
        if (stepX == 1 && stepY == 1) {
            return if (region == whole) image else image.getSubimage(region.x, region.y, region.width, region.height)
        }
        return subsampled(image, region, stepX, stepY, param?.subsamplingXOffset ?: 0, param?.subsamplingYOffset ?: 0)
    }

    override fun dispose() {
        bytes = null
        image = null
    }

    internal companion object {
        /**
         * One pixel in [stepX] by [stepY] of [region], starting [offsetX], [offsetY] into it, as
         * ImageIO's readers subsample.
         */
        fun subsampled(image: BufferedImage, region: Rectangle, stepX: Int, stepY: Int, offsetX: Int, offsetY: Int): BufferedImage {
            val x0 = region.x + offsetX.coerceIn(0, region.width - 1)
            val y0 = region.y + offsetY.coerceIn(0, region.height - 1)
            val spanX = region.x + region.width - x0
            val spanY = region.y + region.height - y0
            val width = (spanX + stepX - 1) / stepX
            val height = (spanY + stepY - 1) / stepY
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val row = IntArray(spanX)
            val sampled = IntArray(width)
            for (y in 0 until height) {
                image.getRGB(x0, y0 + y * stepY, spanX, 1, row, 0, spanX)
                for (x in 0 until width) sampled[x] = row[x * stepX]
                out.setRGB(0, y, width, 1, sampled, 0, width)
            }
            return out
        }
    }
}
