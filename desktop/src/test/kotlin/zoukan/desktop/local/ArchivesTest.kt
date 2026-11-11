package zoukan.desktop.local

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.ComicInfoXml
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class ArchivesTest {

    @TempDir
    lateinit var temp: File

    @BeforeEach
    fun cache() {
        Archives.cacheRoot = File(temp, "cache")
    }

    /** A PNG whose width tells the pages apart */
    private fun png(width: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, 2, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    // Out of order on purpose, in a subfolder, with page 10 after page 9
    private val entries = listOf("pages/10.png" to png(10), "pages/2.png" to png(2), "pages/1.png" to png(1), "pages/9.png" to png(9)) +
        (ComicInfoXml.FILE to ComicInfoXml(mapOf("Title" to "Packed", "Number" to "3")).write().toByteArray())

    private fun tar(file: File) = file.apply {
        TarArchiveOutputStream(outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    private fun sevenZ(file: File) = file.apply {
        SevenZOutputFile(this).use { out ->
            entries.forEach { (name, bytes) ->
                out.putArchiveEntry(out.createArchiveEntry(File(name), name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    private fun widths(format: LocalFormat) = (0 until format.pageCount).map { ImageIO.read(format.page(it).inputStream()).width }

    @Test
    fun `TAR and 7z chapters open with their pages in order`() {
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7")), tar(File(temp, "c.tar")), sevenZ(File(temp, "d.7z"))).forEach { file ->
            LocalFormat.isSupported(file) shouldBe true
            LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
            Archives.comicInfo(file)!!.values["Title"] shouldBe "Packed"
        }
        // Opened again from the cache
        LocalFormat.open(File(temp, "a.cbt")).use { it.pageCount shouldBe 4 }
    }

    @Test
    fun `a changed archive is unpacked again`() {
        val file = tar(File(temp, "a.cbt"))
        LocalFormat.open(file).use { it.pageCount shouldBe 4 }
        TarArchiveOutputStream(file.outputStream()).use { out ->
            val bytes = png(5)
            out.putArchiveEntry(TarArchiveEntry("only.png").apply { size = bytes.size.toLong() })
            out.write(bytes)
            out.closeArchiveEntry()
        }
        file.setLastModified(file.lastModified() + 5_000)
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(5) }
    }

    @Test
    fun `entries outside the archive's folder are skipped`() {
        val file = File(temp, "evil.cbt")
        TarArchiveOutputStream(file.outputStream()).use { out ->
            listOf("../escape.png", "ok.png").forEach { name ->
                val bytes = png(3)
                out.putArchiveEntry(TarArchiveEntry(name, true).apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        LocalFormat.open(file).use { it.pageCount shouldBe 1 }
        File(temp, "escape.png").exists() shouldBe false
        File(Archives.cacheRoot, "escape.png").exists() shouldBe false
    }

    @Test
    fun `the local source lists 7z chapters and reads their ComicInfo`() = runBlocking<Unit> {
        val local = File(temp, "local")
        sevenZ(File(local, "Gamma/Chapter 3.cb7").apply { parentFile.mkdirs() })
        val source = LocalSource(folder = { local })
        val manga = SManga.create().apply {
            url = "Gamma"
            title = "Gamma"
        }
        val chapters = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).chapters
        chapters.map { it.url } shouldContainExactly listOf("Gamma/Chapter 3.cb7")
        chapters[0].name shouldBe "Packed"
        source.getPageList(chapters[0]).size shouldBe 4
    }

    @Test
    fun `the cache never removes an open chapter`() {
        val open = LocalFormat.open(tar(File(temp, "open.cbt")))
        try {
            // Twice the cache's size in other chapters, opened and closed
            (1..16).forEach { i -> LocalFormat.open(sevenZ(File(temp, "other$i.cb7"))).use { it.pageCount shouldBe 4 } }
            widths(open) shouldContainExactly listOf(1, 2, 9, 10)
        } finally {
            open.close()
        }
        Archives.cacheRoot.listFiles { f -> f.isDirectory }!!.size shouldBe 8
    }

    @Test
    fun `covers come from the first page without unpacking`() {
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            ImageIO.read(Archives.firstImage(file)!!.inputStream()).width shouldBe 1
        }
        (Archives.cacheRoot.listFiles()?.size ?: 0) shouldBe 0
    }
}
