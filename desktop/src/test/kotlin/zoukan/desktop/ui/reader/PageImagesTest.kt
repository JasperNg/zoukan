package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PageImagesTest {
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(color)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test
    fun `the extension comes from the image bytes`() {
        PageImages.extension(png(4, 4, Color.RED)) shouldBe "png"
        PageImages.extension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)) shouldBe "jpg"
        PageImages.extension("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()) shouldBe "webp"
        PageImages.extension("<html>".toByteArray()) shouldBe null
    }

    @Test
    fun `file names lose the characters Windows refuses`() {
        PageImages.validFilename("What? A: \"title\" / part 1.") shouldBe "What_ A_ _title_ _ part 1"
        PageImages.validFilename("é".repeat(200), 21).toByteArray().size shouldBe 20
    }

    @Test
    fun `a combined page puts the pages side by side, centered on the taller`() {
        val red = png(30, 40, Color.RED)
        val blue = png(20, 20, Color.BLUE)
        Image.makeFromEncoded(PageImages.combine(red, blue, leftFirst = true, background = Color.WHITE)).use { image ->
            image.width shouldBe 50
            image.height shouldBe 40
        }
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(
            Image.makeFromEncoded(PageImages.combine(red, blue, leftFirst = false, background = Color.WHITE)),
        )
        // Right to left: the first page sits on the right, the second on the left with white above it
        (Color.getR(bitmap.getColor(45, 20)) > 200 && Color.getB(bitmap.getColor(45, 20)) < 60) shouldBe true
        (Color.getB(bitmap.getColor(10, 20)) > 200) shouldBe true
        (Color.getG(bitmap.getColor(10, 2)) > 200) shouldBe true
    }

    @Test
    fun `saving keeps the image as it is, with its extension`() {
        val dir = Files.createTempDirectory("zoukan-pictures").toFile()
        val bytes = png(8, 8, Color.GREEN)
        val file = PageImages.save(bytes, dir, "Title - Chapter 1 - 3")
        file.name shouldEndWith "Title - Chapter 1 - 3.png"
        file.readBytes().contentEquals(bytes) shouldBe true
        dir.deleteRecursively()
    }
}
