package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.testing.FileSystemSandbox
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
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
    fun `a CBZ write that fails leaves no partial archive, keeps the pages and every other download`() = runBlocking<Unit> {
        val other = kotlin.random.Random(5).nextBytes(4096)
        File(temp, "Chapter 0.cbz").writeBytes(other) // an existing download in the same folder
        var calls = 0
        val failing = ChapterDownloader(
            pageList = { pages.indices.map { Page(it, url = "p$it") } },
            imageUrl = { "https://example.org/${it.url}.png" },
            image = { pages[it.index] },
            parallelPages = 4,
            writeEntry = { writer, name, bytes -> if (++calls == 2) throw java.io.IOException("No space left on device") else writer.write(name, bytes) },
        )
        shouldThrow<java.io.IOException> { failing.download(temp, "Chapter 1", ChapterDownloader.Options(true, null, comicInfo)) { _, _ -> } }

        File(temp, "Chapter 1.cbz_tmp").exists() shouldBe false
        File(temp, "Chapter 1.cbz").exists() shouldBe false
        ChapterDownloader.imageFiles(File(temp, "Chapter 1_tmp")).size shouldBe 12
        File(temp, "Chapter 0.cbz").readBytes() shouldBe other
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

    @Test
    fun `an HTML body is retried, then fails the page instead of being saved as an image`() = runBlocking<Unit> {
        val tries = AtomicInteger()
        val html = "<!DOCTYPE html><html><body>Please log in</body></html>".toByteArray()
        val downloader = ChapterDownloader(
            pageList = { listOf(Page(0, url = "p0"), Page(1, url = "p1")) },
            imageUrl = { "https://example.org/${it.url}.png" },
            image = { page ->
                if (page.index == 1) {
                    tries.incrementAndGet()
                    html
                } else {
                    pages[0]
                }
            },
            parallelPages = 2,
        )
        val error = shouldThrow<java.io.IOException> {
            downloader.download(temp, "Chapter 4", ChapterDownloader.Options(true, null, null)) { _, _ -> }
        }
        error.message shouldContain "not an image"
        tries.get() shouldBe 3
        val tmp = File(temp, "Chapter 4_tmp")
        ChapterDownloader.imageFiles(tmp).map { it.name } shouldContainExactly listOf("001.png")
        File(temp, "Chapter 4.cbz").exists() shouldBe false
    }

    @Test
    fun `an HTML body that turns into an image on a retry is saved`() = runBlocking<Unit> {
        val tries = AtomicInteger()
        val downloader = ChapterDownloader(
            pageList = { listOf(Page(0, url = "p0")) },
            imageUrl = { "https://example.org/${it.url}.png" },
            image = { if (tries.incrementAndGet() == 1) "<html>busy</html>".toByteArray() else pages[0] },
            parallelPages = 1,
        )
        val dir = downloader.download(temp, "Chapter 5", ChapterDownloader.Options(false, null, null)) { _, _ -> }
        ChapterDownloader.imageFiles(dir).map { it.name } shouldBe listOf("001.png")
    }

    @Test
    fun `an unknown binary format is still kept`() = runBlocking<Unit> {
        val bmp = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "bmp", it) }.toByteArray()
        val downloader = ChapterDownloader(
            pageList = { listOf(Page(0, url = "p0")) },
            imageUrl = { "https://example.org/${it.url}.bmp" },
            image = { bmp },
            parallelPages = 1,
        )
        val dir = downloader.download(temp, "Chapter 6", ChapterDownloader.Options(false, null, null)) { _, _ -> }
        ChapterDownloader.imageFiles(dir).size shouldBe 1
    }

    @Test
    fun `finishing a CBZ removes a link left in the page folder, never what it points to`() = runBlocking<Unit> {
        FileSystemSandbox.create(temp).use { sandbox ->
            val mangaDir = File(sandbox.storageFolder, "downloads/Source/Manga")
            // A link inside the page folder to the user's folder, as a copied storage folder may hold
            val link = sandbox.link(File(mangaDir, "Chapter 1_tmp/linked"), sandbox.userFolder)
            assumeTrue(link != null, "symbolic links cannot be made here")
            sandbox.record()

            val file = downloader().download(mangaDir, "Chapter 1", ChapterDownloader.Options(true, null, comicInfo)) { _, _ -> }

            file.name shouldBe "Chapter 1.cbz"
            Files.exists(File(mangaDir, "Chapter 1_tmp").toPath(), LinkOption.NOFOLLOW_LINKS) shouldBe false
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `a page folder that is a link is refused and nothing it points to is touched`() = runBlocking<Unit> {
        listOf(true, false).forEach { asCbz ->
            FileSystemSandbox.create(temp).use { sandbox ->
                val mangaDir = File(sandbox.storageFolder, "downloads/Source/Manga")
                // Files the downloader would otherwise delete or overwrite: a leftover part, a ComicInfo.xml
                File(sandbox.userFolder, "001.tmp").writeText("another program's temporary file")
                File(sandbox.userFolder, "ComicInfo.xml").writeText("<ComicInfo/>")
                val link = sandbox.link(File(mangaDir, "Chapter 1_tmp"), sandbox.userFolder)
                assumeTrue(link != null, "symbolic links cannot be made here")
                sandbox.record()

                shouldThrow<java.io.IOException> {
                    downloader().download(mangaDir, "Chapter 1", ChapterDownloader.Options(asCbz, null, comicInfo)) { _, _ -> }
                }

                File(mangaDir, "Chapter 1.cbz").exists() shouldBe false
                File(mangaDir, "Chapter 1").exists() shouldBe false
                sandbox.assertNothingOutsideAppChanged()
            }
        }
    }

    @Test
    fun `a file link in the page folder named like a page is never packed, and the page is downloaded instead`() = runBlocking<Unit> {
        FileSystemSandbox.create(temp).use { sandbox ->
            val mangaDir = File(sandbox.storageFolder, "downloads/Source/Manga")
            // A link named like page 2, to one of the user's files, as a copied storage folder may hold
            val secret = File(sandbox.userFolder, "notes.png").apply { writeText("the user's private notes") }
            val link = sandbox.link(File(mangaDir, "Chapter 1_tmp/002.png"), secret)
            assumeTrue(link != null, "symbolic links cannot be made here")
            sandbox.record()

            val file = downloader().download(mangaDir, "Chapter 1", ChapterDownloader.Options(true, null, comicInfo)) { _, _ -> }

            // Every page is the source's own, page 2 too; nothing of the user's file is in the archive
            LocalFormat.open(file).use { format ->
                format.pageCount shouldBe 12
                (0 until 12).forEach { format.page(it).contentEquals(pages[it]) shouldBe true }
            }
            CbzReader(file).use { r ->
                r.entries.forEach { entry -> String(r.open(entry).use { it.readBytes() }).contains("private notes") shouldBe false }
            }
            secret.readText() shouldBe "the user's private notes"
            sandbox.assertNothingOutsideAppChanged()
        }
    }
}
