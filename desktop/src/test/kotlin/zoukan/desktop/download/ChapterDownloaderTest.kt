package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.local.LocalFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class ChapterDownloaderTest {

    @TempDir
    lateinit var temp: File

    private fun png(shade: Int): ByteArray {
        val image = BufferedImage(4, 6, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, shade) }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val pages = (0 until 12).map { png(it) }

    private fun downloader(fetched: AtomicInteger = AtomicInteger(), failAt: Int? = null) = ChapterDownloader(
        pageList = { pages.indices.map { Page(it, url = "p$it") } },
        imageUrl = { "https://example.org/${it.url}.png" },
        image = { page ->
            if (page.index == failAt) error("HTTP 500")
            fetched.incrementAndGet()
            pages[page.index]
        },
        parallelPages = 4,
    )

    private val comicInfo = ComicInfoXml(mapOf("Title" to "Chapter 1", "Series" to "Test", "Number" to "1"))

    @Test
    fun `downloads a CBZ with pages in order and ComicInfo`() = runBlocking<Unit> {
        val file = downloader().download(temp, "Chapter 1", ChapterDownloader.Options(true, null, comicInfo)) { _, _ -> }
        file.name shouldBe "Chapter 1.cbz"
        File(temp, "Chapter 1_tmp").exists() shouldBe false
        val names = ZipFile(file).fileHeaders.map { it.fileName }.sorted()
        names shouldContainExactly (1..12).map { "%03d.png".format(it) } + "ComicInfo.xml"
        LocalFormat.open(file).use { format ->
            format.pageCount shouldBe 12
            (0 until 12).forEach { format.page(it).contentEquals(pages[it]) shouldBe true }
        }
        CbzReader(file).use { r -> ComicInfoXml.read(r.open(r.find("ComicInfo.xml")!!))["Series"] shouldBe "Test" }
    }

    @Test
    fun `downloads a folder`() = runBlocking<Unit> {
        val dir = downloader().download(temp, "Chapter 2", ChapterDownloader.Options(false, null, null)) { _, _ -> }
        dir.isDirectory shouldBe true
        ChapterDownloader.imageFiles(dir).map { it.name } shouldBe (1..12).map { "%03d.png".format(it) }
    }

    @Test
    fun `encrypted CBZ needs the password`() = runBlocking<Unit> {
        SecurityPreferences.EncryptionType.entries.forEach { type ->
            val password = "s3cret-$type".toCharArray()
            val file = downloader().download(temp, "Enc $type", ChapterDownloader.Options(true, CbzEncryption(password, type), comicInfo)) { _, _ -> }
            ZipFile(file).isEncrypted shouldBe true
            LocalFormat.open(file) { password }.use { it.page(3).contentEquals(pages[3]) shouldBe true }
            shouldThrow<IllegalStateException> { LocalFormat.open(file) { null } }.message shouldContain "password"
            shouldThrow<Exception> { LocalFormat.open(file) { "wrong".toCharArray() }.use { it.page(0) } }
        }
    }

    @Test
    fun `a failed page fails the chapter, and a retry fetches only the missing pages`() = runBlocking<Unit> {
        val fetched = AtomicInteger()
        shouldThrow<IllegalStateException> {
            downloader(fetched, failAt = 7).download(temp, "Chapter 3", ChapterDownloader.Options(true, null, null)) { _, _ -> }
        }
        File(temp, "Chapter 3.cbz").exists() shouldBe false
        File(temp, "Chapter 3_tmp").exists() shouldBe true
        val kept = ChapterDownloader.imageFiles(File(temp, "Chapter 3_tmp")).size
        (kept in 1..11) shouldBe true
        val retry = AtomicInteger()
        downloader(retry).download(temp, "Chapter 3", ChapterDownloader.Options(true, null, null)) { _, _ -> }
        retry.get() shouldBe 12 - kept
        File(temp, "Chapter 3.cbz").exists() shouldBe true
    }

    @Test
    fun `image types from bytes`() {
        ImageType.fromBytes(pages[0]) shouldBe ImageType.PNG
        ImageType.fromBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)) shouldBe ImageType.JPEG
        ImageType.fromBytes("RIFF0000WEBPVP8 ".toByteArray()) shouldBe ImageType.WEBP
        ImageType.fromBytes("GIF89a".toByteArray()) shouldBe ImageType.GIF
        ImageType.fromBytes("hello".toByteArray()) shouldBe null
    }
}
