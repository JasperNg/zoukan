package zoukan.desktop.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * ImageIO reads and writes of images held in memory, through streams cached in memory. ImageIO's
 * own `read(InputStream)`, `write(..., OutputStream)` and `createImageInputStream` /
 * `createImageOutputStream` on a stream copy the image to a temporary file in the system's
 * temporary folder while it is read or written: a page or cover, decrypted, would land there.
 */
object MemoryImageIo {
    /** The image in [bytes], or null when no ImageIO reader knows its type. */
    fun read(bytes: ByteArray): BufferedImage? = ImageIO.read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)))

    /** [image] as a PNG. */
    fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream ->
            if (!ImageIO.write(image, "png", stream)) throw IOException("No PNG writer")
        }
        return out.toByteArray()
    }
}
