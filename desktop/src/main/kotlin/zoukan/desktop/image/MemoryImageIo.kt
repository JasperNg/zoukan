package zoukan.desktop.image

import xyz.nulldev.androidcompat.graphics.byteArrayImageInputStream
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.stream.ImageOutputStreamImpl

/**
 * ImageIO reads and writes of images held in memory, through streams over byte arrays. ImageIO's
 * own `read(InputStream)`, `write(..., OutputStream)` and `createImageInputStream` /
 * `createImageOutputStream` on a stream copy the image to a temporary file in the system's
 * temporary folder while it is read or written: a page or cover, decrypted, would land there.
 * Reads go over the bytes themselves, with no copy; a PNG is written into one growing array.
 */
object MemoryImageIo {
    /** The image in [bytes], or null when no ImageIO reader knows its type. */
    fun read(bytes: ByteArray): BufferedImage? = ImageIO.read(byteArrayImageInputStream(bytes))

    /** [image] as a PNG. */
    fun png(image: BufferedImage): ByteArray {
        val stream = ByteArrayImageOutputStream()
        stream.use {
            if (!ImageIO.write(image, "png", stream)) throw IOException("No PNG writer")
        }
        return stream.toByteArray()
    }
}

/**
 * An ImageIO output stream into a growing byte array, with no file and no second cache (as
 * `MemoryCacheImageOutputStream` over a `ByteArrayOutputStream` would hold). Seeks back and
 * rewrites, as image writers do for chunk lengths.
 */
internal class ByteArrayImageOutputStream : ImageOutputStreamImpl() {
    private var buffer = ByteArray(64 * 1024)
    private var count = 0

    /** What was written, trimmed to its length. */
    fun toByteArray(): ByteArray = buffer.copyOf(count)

    override fun length(): Long = count.toLong()

    override fun write(b: Int) {
        flushBits()
        ensure(streamPos + 1)
        buffer[streamPos.toInt()] = b.toByte()
        streamPos++
        count = maxOf(count, streamPos.toInt())
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        flushBits()
        if (len == 0) return
        ensure(streamPos + len)
        System.arraycopy(b, off, buffer, streamPos.toInt(), len)
        streamPos += len
        count = maxOf(count, streamPos.toInt())
    }

    override fun read(): Int {
        checkClosed()
        bitOffset = 0
        if (streamPos >= count) return -1
        return buffer[streamPos++.toInt()].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkClosed()
        bitOffset = 0
        if (len == 0) return 0
        if (streamPos >= count) return -1
        val n = minOf(len.toLong(), count - streamPos).toInt()
        System.arraycopy(buffer, streamPos.toInt(), b, off, n)
        streamPos += n
        return n
    }

    private fun ensure(size: Long) {
        if (size > Int.MAX_VALUE - 8) throw IOException("The image is too large to write in memory")
        if (size <= buffer.size) return
        val grown = maxOf(size, minOf(Int.MAX_VALUE - 8L, buffer.size * 2L)).toInt()
        buffer = buffer.copyOf(grown)
    }
}
