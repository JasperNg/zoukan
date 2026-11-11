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
}
