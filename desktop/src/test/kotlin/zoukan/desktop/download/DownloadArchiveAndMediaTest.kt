package zoukan.desktop.download

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalFormat
import zoukan.i18n.desktop.DMR
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

/** One entry of a CBZ, ZIP or EPUB is read whole only up to the size a page may have. */
class CbzReaderLimitTest {
    @TempDir
    lateinit var temp: File

    /** An archive with a small page and a large, highly compressible one (the file stays small). */
    private fun archive(): File = File(temp, "chapter.cbz").also { file ->
        ZipFile(file).use { zip ->
            zip.addStream(ByteArrayInputStream(ByteArray(1_000) { 1 }), ZipParameters().apply { fileNameInZip = "001.jpg" })
            zip.addStream(ByteArrayInputStream(ByteArray(1_000_000)), ZipParameters().apply { fileNameInZip = "002.jpg" })
        }
    }

    @Test
    fun `an entry larger than the page cap fails that page with the reader's message, and the others read`() {
        CbzReader(archive(), { null }, maxEntryBytes = 64 * 1024).use { reader ->
            reader.read(reader.find("001.jpg")!!).size shouldBe 1_000
            val error = shouldThrow<IOException> { reader.read(reader.find("002.jpg")!!) }
            error.message shouldBe localized(DMR.strings.the_page_is_too_large_to_show)
            // The reader goes on
            reader.read(reader.find("001.jpg")!!).size shouldBe 1_000
        }
    }

    @Test
    fun `an entry at the cap reads whole`() {
        CbzReader(archive(), { null }, maxEntryBytes = 1_000_000).use { reader ->
            reader.read(reader.find("002.jpg")!!).size shouldBe 1_000_000
        }
    }

    @Test
    fun `a stream longer than its header says is cut off at the cap, never read whole`() {
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int = 0.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Arrays.fill(b, off, off + len, 0)
                served += len
                return len
            }
        }
        CbzReader.readBounded(endless, limit = 10_000) shouldBe null
        (endless.served < 100_000) shouldBe true
        CbzReader.readBounded(ByteArrayInputStream(ByteArray(10_000)), limit = 10_000)!!.size shouldBe 10_000
    }
}

class ComicInfoXmlTest {

    /** As the Android app writes it (xmlutil, core-metadata's ComicInfo) */
    private val android = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>Chapter 5</Title>
          <Series>One Series</Series>
          <Number>5</Number>
          <Summary>About it</Summary>
          <Writer>Writer A</Writer>
          <Penciller>Artist B</Penciller>
          <Translator>Group C</Translator>
          <Genre>Action, Drama</Genre>
          <ty:PublishingStatusTachiyomi xmlns:ty="http://www.w3.org/2001/XMLSchema">Completed</ty:PublishingStatusTachiyomi>
          <ty:Categories xmlns:ty="http://www.w3.org/2001/XMLSchema">Reading</ty:Categories>
          <mh:SourceMihon xmlns:mh="http://www.w3.org/2001/XMLSchema">MangaPill</mh:SourceMihon>
        </ComicInfo>
    """.trimIndent()

    @Test
    fun `reads the Android app's ComicInfo`() {
        val info = ComicInfoXml.read(android.byteInputStream())
        val manga = SManga.create().apply { title = "x" }
        info.copyTo(manga)
        manga.title shouldBe "One Series"
        manga.author shouldBe "Writer A"
        manga.artist shouldBe "Artist B"
        manga.genre shouldBe "Action, Drama, Reading"
        manga.status shouldBe SManga.COMPLETED
        val chapter = SChapter.create().apply { name = "x" }
        info.copyTo(chapter)
        chapter.name shouldBe "Chapter 5"
        chapter.chapter_number shouldBe 5f
        chapter.scanlator shouldBe "Group C"
    }

    @Test
    fun `writes what it reads`() {
        val info = ComicInfoXml.read(android.byteInputStream())
        val xml = info.write()
        xml shouldContain "<ty:PublishingStatusTachiyomi"
        xml shouldContain "xmlns:ty=\"http://www.w3.org/2001/XMLSchema\""
        ComicInfoXml.read(xml.byteInputStream()).values shouldBe info.values
    }

    @Test
    fun `a value with markup inside reads as the DOM's own text content`() {
        // Nested elements, CDATA, comments, processing instructions, character and predefined
        // entities, whitespace, an empty element and a second root child after a deep one
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ComicInfo>
              <Series>a<b>b<c>c<d/></c><!-- not text -->d</b><?pi not text?><![CDATA[<e>]]>&amp;&#x41;
                f</Series>
              <Title><x><y><z>deep</z></y></x>tail</Title>
              <Writer/>
              <Summary><p>one</p><p>two</p></Summary>
            </ComicInfo>
        """.trimIndent()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val children = doc.documentElement.childNodes
        val expected = (0 until children.length).mapNotNull { children.item(it) as? Element }
            .associate { it.localName to it.textContent }

        ComicInfoXml.read(xml.byteInputStream()).values shouldBe expected
        expected["Series"] shouldBe "abcd<e>&A\n    f"
    }

    @Test
    fun `a value nested deeper than the stack allows reads without a stack overflow`() {
        val depth = 100_000
        val xml = "<ComicInfo><Series>" + "<a>".repeat(depth) + "x" + "</a>".repeat(depth) + "</Series><Title>t</Title></ComicInfo>"
        ComicInfoXml.read(xml.byteInputStream()).values shouldBe mapOf("Series" to "x", "Title" to "t")
    }
}

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
    fun `a tall page more pixels than ImageIO may decode on the heap stays whole`() {
        val image = File(temp, "001.png").apply { writeBytes(png(100, 1000)) }
        // 100,000 pixels, over a limit of 50,000: not decoded, not split
        TallImageSplitter.splitIfTall(image, "001", partHeight = 300, maxPixels = 50_000) shouldContainExactly listOf(image)
        image.exists() shouldBe true
        temp.list()!!.toList() shouldBe listOf("001.png")
    }

    /**
     * A grey 1-bit PNG of [width] by [height] pixels, written row by row so the test never holds
     * the whole image.
     */
    private fun stripPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(
            "IHDR",
            ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply {
                    writeInt(width)
                    writeInt(height)
                    write(byteArrayOf(1, 0, 0, 0, 0))
                }
            }.toByteArray(),
        )
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { z ->
            val row = ByteArray(1 + (width + 7) / 8)
            repeat(height) { z.write(row) }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a strip taller than 65,535 pixels is still split, as ImageIO reads it by region`() {
        // The 65,535 side limit is the reader's ImageIO display path; the splitter reads a part at a time
        val image = File(temp, "001.png").apply { writeBytes(stripPng(100, 90_000)) }
        val parts = TallImageSplitter.splitIfTall(image, "001", partHeight = 15_000, maxPixels = 100L * 90_000)
        parts.size shouldBe 6
        parts.sumOf {
            ImageIO.createImageInputStream(it).use { input ->
                ImageIO.getImageReaders(input).next().run {
                    setInput(input)
                    getHeight(0)
                }
            }
        } shouldBe 90_000
        image.exists() shouldBe false
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
