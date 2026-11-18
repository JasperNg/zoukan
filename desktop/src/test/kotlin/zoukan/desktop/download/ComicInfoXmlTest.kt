package zoukan.desktop.download

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val children = doc.documentElement.childNodes
        val expected = (0 until children.length).mapNotNull { children.item(it) as? org.w3c.dom.Element }
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
