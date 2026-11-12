package zoukan.desktop.local

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.ComicInfoXml
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class ArchivesTest {

    @TempDir
    lateinit var temp: File

    @BeforeEach
    fun cache() {
        Archives.cacheRoot = File(temp, "cache")
    }

    @AfterEach
    fun limits() {
        Archives.limits = Archives.Limits()
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

    @Test
    fun `a link in a TAR is not a page`() {
        val secret = File(temp, "secret.png").apply { writeBytes(png(7)) }
        val file = File(temp, "links.cbt")
        TarArchiveOutputStream(file.outputStream()).use { out ->
            listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK).forEachIndexed { i, flag ->
                out.putArchiveEntry(TarArchiveEntry("000$i.png", flag).apply { linkName = secret.absolutePath })
                out.closeArchiveEntry()
            }
            val bytes = png(3)
            out.putArchiveEntry(TarArchiveEntry("ok.png").apply { size = bytes.size.toLong() })
            out.write(bytes)
            out.closeArchiveEntry()
        }
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(3) }
        ImageIO.read(Archives.firstImage(file)!!.inputStream()).width shouldBe 3
        secret.exists() shouldBe true
    }

    @Test
    fun `unpacked links and what is behind them are not files`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        val secret = File(outside, "secret.png").apply { writeBytes(png(7)) }
        val root = File(temp, "unpacked").apply { mkdirs() }
        File(root, "pages").mkdirs()
        File(root, "pages/1.png").writeBytes(png(1))
        val linked = runCatching {
            Files.createSymbolicLink(File(root, "0001.png").toPath(), secret.toPath())
            Files.createSymbolicLink(File(root, "folder").toPath(), outside.toPath())
        }.isSuccess
        // Windows only lets a privileged user, or one in developer mode, make links
        assumeTrue(linked, "this machine cannot create symbolic links")

        Archives.regularFiles(root).map { it.relativeTo(root).invariantSeparatorsPath } shouldContainExactly listOf("pages/1.png")
        // Counted as nothing, and never followed
        Archives.exceeds(root, Archives.Limits(entries = 1)) shouldBe false
        secret.exists() shouldBe true
    }

    @Test
    fun `an archive with too many files is refused`() {
        Archives.limits = Archives.Limits(entries = 4)
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            shouldThrow<Archives.TooLargeException> { Archives.open(file) }
        }
        // Nothing half unpacked is left behind
        Archives.cacheRoot.listFiles().orEmpty().toList() shouldBe emptyList()
        Archives.limits = Archives.Limits(entries = 5)
        LocalFormat.open(File(temp, "a.cbt")).use { it.pageCount shouldBe 4 }
    }

    @Test
    fun `an archive that unpacks to too much is refused`() {
        val largest = entries.maxOf { it.second.size }.toLong()
        val total = entries.sumOf { it.second.size }.toLong()
        listOf(
            Archives.Limits(entryBytes = largest - 1),
            Archives.Limits(totalBytes = total - 1),
        ).forEach { limits ->
            Archives.limits = limits
            listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
                shouldThrow<Archives.TooLargeException> { Archives.open(file) }
            }
            Archives.cacheRoot.listFiles().orEmpty().toList() shouldBe emptyList()
        }
        Archives.limits = Archives.Limits(entryBytes = largest, totalBytes = total)
        LocalFormat.open(File(temp, "b.cb7")).use { it.pageCount shouldBe 4 }
    }

    @Test
    fun `the failure says what was too much`() {
        val file = sevenZ(File(temp, "b.cb7"))
        val total = entries.sumOf { it.second.size }.toLong()
        Archives.limits = Archives.Limits(totalBytes = total - 1)
        shouldThrow<Archives.TooLargeException> { Archives.open(file) }.message shouldBe
            "b.cb7 unpacks to more than ${total - 1} bytes, which is more than a chapter may"
    }

    @Test
    fun `a cover over the cap is not read into memory`() {
        Archives.limits = Archives.Limits(entryBytes = 16)
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            shouldThrow<Archives.TooLargeException> { Archives.firstImage(file) }
        }
    }

    @Test
    fun `an unpacked folder is measured against the caps`() {
        val root = File(temp, "unpacked").apply { mkdirs() }
        File(root, "a/b").mkdirs()
        File(root, "a/1.bin").writeBytes(ByteArray(10))
        File(root, "a/b/2.bin").writeBytes(ByteArray(20))
        Archives.exceeds(root, Archives.Limits(entryBytes = 20, totalBytes = 30, entries = 2)) shouldBe false
        Archives.exceeds(root, Archives.Limits(entryBytes = 19)) shouldBe true
        Archives.exceeds(root, Archives.Limits(totalBytes = 29)) shouldBe true
        Archives.exceeds(root, Archives.Limits(entries = 1)) shouldBe true
    }

    @Test
    fun `entry names that leave the folder or name a stream are refused`() {
        val sep = File.separator
        Archives.entryPath("pages/1.png") shouldBe "pages${sep}1.png"
        Archives.entryPath("./pages\\1.png") shouldBe "pages${sep}1.png"
        listOf("../1.png", "pages/../../1.png", "/etc/1.png", "C:/1.png", "C:\\1.png", "sub/file.png:ads", "sub:x/1.png", "a/b/c:d/1.png", "", "./")
            .forEach { Archives.entryPath(it) shouldBe null }
    }

    @Test
    fun `an entry naming a stream is skipped`() {
        val file = File(temp, "ads.cbt")
        TarArchiveOutputStream(file.outputStream()).use { out ->
            listOf("sub/ok.png:hidden.png", "sub/ok.png").forEach { name ->
                val bytes = png(3)
                out.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        LocalFormat.open(file).use { it.pageCount shouldBe 1 }
    }
}
