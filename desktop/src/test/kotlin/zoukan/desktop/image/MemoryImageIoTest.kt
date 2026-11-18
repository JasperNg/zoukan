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
}
