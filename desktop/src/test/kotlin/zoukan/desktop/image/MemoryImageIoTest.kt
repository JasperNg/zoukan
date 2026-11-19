package zoukan.desktop.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/** Images in memory are read and written without ImageIO's temporary file cache. */
class MemoryImageIoTest {

    @Test
    fun `reads and writes with ImageIO's file cache on and its folder gone`(@TempDir dir: Path) {
        val tiff = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB), "tiff", it) }.toByteArray()
        val useCache = ImageIO.getUseCache()
        val cacheDirectory = ImageIO.getCacheDirectory()
        try {
            ImageIO.setUseCache(true)
            ImageIO.setCacheDirectory(dir.toFile())
            // A file-backed stream would fail now
            dir.toFile().delete()
            val image = MemoryImageIo.read(tiff)!!
            (image.width to image.height) shouldBe (7 to 5)
            val png = MemoryImageIo.png(image)
            MemoryImageIo.read(png)!!.width shouldBe 7
            MemoryImageIo.read("not an image".toByteArray()) shouldBe null
        } finally {
            ImageIO.setUseCache(useCache)
            ImageIO.setCacheDirectory(cacheDirectory)
        }
    }

    @Test
    fun `the PNG written into a byte array is the image, pixel for pixel`() {
        val image = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 200) for (x in 0 until 300) image.setRGB(x, y, (x * 7 + y * 13) and 0xFFFFFF)
        val back = MemoryImageIo.read(MemoryImageIo.png(image))!!
        (back.width to back.height) shouldBe (300 to 200)
        (0 until 200 step 17).all { y -> (0 until 300 step 23).all { x -> back.getRGB(x, y) == image.getRGB(x, y) } } shouldBe true
    }

    @Test
    fun `the byte array output stream seeks back, rewrites and grows`() {
        val stream = ByteArrayImageOutputStream()
        stream.write(ByteArray(100_000) { 1 })
        stream.seek(10)
        stream.writeInt(0x01020304)
        stream.seek(stream.length())
        stream.write(9)
        stream.close()
        val bytes = stream.toByteArray()
        bytes.size shouldBe 100_001
        bytes.copyOfRange(10, 14).toList() shouldBe listOf<Byte>(1, 2, 3, 4)
        bytes[9] shouldBe 1.toByte()
        bytes[14] shouldBe 1.toByte()
        bytes[100_000] shouldBe 9.toByte()
    }
}
