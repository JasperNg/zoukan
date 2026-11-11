package zoukan.desktop.local

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.ComicInfoXml
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class LocalSourceTest {

    @TempDir
    lateinit var temp: File

    private fun png(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun library(): File {
        val local = File(temp, "local")
        // A folder chapter and a CBZ chapter with its own ComicInfo.xml
        File(local, "Alpha/Chapter 1").apply { mkdirs() }.let { dir ->
            (1..3).forEach { File(dir, "$it.png").writeBytes(png()) }
        }
        CbzWriter(File(local, "Alpha/Chapter 2.cbz")).use { w ->
            (1..10).forEach { w.write("$it.png", png()) }
            w.write(
                ComicInfoXml.FILE,
                ComicInfoXml(mapOf("Title" to "The second", "Series" to "Alpha Series", "Writer" to "W", "Number" to "2", "Translator" to "TL")).write().toByteArray(),
            )
        }
        File(local, "Alpha/notes.txt").writeText("not a chapter")
        File(local, "Beta").mkdirs()
        File(local, ".hidden").mkdirs()
        return local
    }

    @Test
    fun `lists, details, chapters and pages`() = runBlocking<Unit> {
        val local = library()
        val source = LocalSource(folder = { local })
        val popular = source.getPopularManga(1).mangas
        popular.map { it.title } shouldContainExactly listOf("Alpha", "Beta")
        source.getSearchManga(1, "alp", FilterList()).mangas.map { it.url } shouldContainExactly listOf("Alpha")

        val manga = SManga.create().apply {
            url = "Alpha"
            title = "Alpha"
        }
        val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
        // Series details from the first chapter archive's ComicInfo.xml, now copied to the folder
        update.manga.title shouldBe "Alpha Series"
        update.manga.author shouldBe "W"
        File(local, "Alpha/ComicInfo.xml").exists() shouldBe true

        val chapters = update.chapters
        chapters.map { it.url } shouldContainExactly listOf("Alpha/Chapter 2.cbz", "Alpha/Chapter 1")
        chapters[0].name shouldBe "The second"
        chapters[0].scanlator shouldBe "TL"
        chapters[1].chapter_number shouldBe 1f

        // A cover from the first chapter
        LocalSource.findCover(File(local, "Alpha"))!!.name shouldBe "cover.png"
        update.manga.thumbnail_url!! shouldStartWith "file:"

        source.getPageList(chapters[0]).size shouldBe 10
        LocalFormat.open(source.chapterFile(chapters[1].url)).use { it.pageCount shouldBe 3 }
    }
}
