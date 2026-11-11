package zoukan.desktop.download

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.local.LocalFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class TallImageSplitterTest {

    @TempDir
    lateinit var temp: File

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    @Test
    fun `a tall page is split into parts that add up to it`() {
        val image = File(temp, "001.png").apply { writeBytes(png(100, 1000)) }
        val parts = TallImageSplitter.splitIfTall(image, "001", partHeight = 300)
        parts.map { it.name } shouldContainExactly listOf("001__001.jpg", "001__002.jpg", "001__003.jpg", "001__004.jpg")
        parts.sumOf { ImageIO.read(it).height } shouldBe 1000
        parts.all { ImageIO.read(it).width == 100 } shouldBe true
        image.exists() shouldBe false
    }

    @Test
    fun `ordinary and short tall pages stay whole`() {
        val normal = File(temp, "001.png").apply { writeBytes(png(100, 150)) }
        TallImageSplitter.splitIfTall(normal, "001", partHeight = 300) shouldContainExactly listOf(normal)
        val tallButShort = File(temp, "002.png").apply { writeBytes(png(50, 250)) }
        TallImageSplitter.splitIfTall(tallButShort, "002", partHeight = 300) shouldContainExactly listOf(tallButShort)
        normal.exists() shouldBe true
        tallButShort.exists() shouldBe true
    }

    @Test
    fun `downloads split tall pages and resume without fetching them again`() = runBlocking<Unit> {
        // Taller than twice any screen, so it splits at the default part height
        val tall = png(10, 20_000)
        val small = png(10, 10)
        val fetched = AtomicInteger()
        var failAt: Int? = 1
        val downloader = ChapterDownloader(
            pageList = { (0 until 2).map { Page(it, url = "p$it") } },
            imageUrl = { "https://example.org/${it.url}.png" },
            image = { page ->
                if (page.index == failAt) error("HTTP 500")
                fetched.incrementAndGet()
                if (page.index == 0) tall else small
            },
            parallelPages = 1,
        )
        val options = ChapterDownloader.Options(asCbz = false, encryption = null, comicInfo = null, splitTallImages = true)
        runCatching { downloader.download(temp, "Chapter 1", options) { _, _ -> } }.isFailure shouldBe true
        fetched.get() shouldBe 1

        failAt = null
        val dir = downloader.download(temp, "Chapter 1", options) { _, _ -> }
        // The split page was kept from the first run
        fetched.get() shouldBe 2
        val files = ChapterDownloader.imageFiles(dir).map { it.name }
        files.first() shouldBe "001__001.jpg"
        files.last() shouldBe "002.png"
        LocalFormat.open(dir).use { it.pageCount shouldBe files.size }
    }

    @Test
    fun `a split cut short is redone on resume`() {
        // The state after a stop between renaming the parts and deleting the original
        val original = File(temp, "001.png").apply { writeBytes(png(100, 6000)) }
        File(temp, "001__001.jpg").writeBytes(png(100, 10))
        File(temp, "001__002.jpg.tmp").writeBytes(png(100, 10))
        val parts = TallImageSplitter.settle(temp, "001", split = true, partHeight = 2000)
        parts.map { it.name } shouldContainExactly listOf("001__001.jpg", "001__002.jpg", "001__003.jpg")
        parts.sumOf { ImageIO.read(it).height } shouldBe 6000
        original.exists() shouldBe false
        temp.listFiles()!!.none { it.name.endsWith(".tmp") } shouldBe true

        // Without splitting, the original page alone
        val other = File(temp, "002.png").apply { writeBytes(png(100, 1000)) }
        File(temp, "002__001.jpg").writeBytes(png(100, 10))
        TallImageSplitter.settle(temp, "002", split = false) shouldContainExactly listOf(other)
        File(temp, "002__001.jpg").exists() shouldBe false
        // A page never downloaded
        TallImageSplitter.settle(temp, "003", split = true) shouldContainExactly emptyList()
    }
}
