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
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.testing.FileSystemSandbox
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ArchivesTest {

    @TempDir
    lateinit var temp: File

    private fun newCipher() = FileCipher(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    private var cipher: SettingsFileCodec = newCipher()

    @BeforeEach
    fun cache() {
        Archives.cacheRoot = File(temp, "cache")
        Archives.codecSource = { cipher }
    }

    @AfterEach
    fun limits() {
        Archives.limits = Archives.Limits()
        Archives.codecSource = { SettingsFileCodec.current }
    }

    /** Every file the cache holds. */
    private fun cachedFiles(): List<File> = Archives.cacheRoot.walkTopDown().filter { it.isFile }.toList()

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

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
    fun `pruning the cache removes links as themselves, never what they point to`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            Archives.cacheRoot = File(sandbox.dataFolder, "cache/archives")
            LocalFormat.open(tar(File(temp, "first.cbt"))).use { it.pageCount shouldBe 4 }
            val first = Archives.cacheRoot.listFiles { f -> f.isDirectory }!!.single()
            // A link in an unpacked chapter, and a chapter-named link in the cache folder itself
            assumeTrue(sandbox.link(File(first, "linked"), sandbox.userFolder) != null, "symbolic links cannot be made here")
            val rootLink = sandbox.link(File(Archives.cacheRoot, "0123456789abcdef0123"), sandbox.downloads)!!
            first.setLastModified(1_000L)
            sandbox.record()

            // More chapters than the cache keeps, so the oldest go
            (1..9).forEach { i -> LocalFormat.open(sevenZ(File(temp, "other$i.cb7"))).use { it.pageCount shouldBe 4 } }

            first.exists() shouldBe false
            Files.exists(rootLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
            sandbox.assertNothingOutsideAppChanged()
        }
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
    fun `firstImage refuses a page over the in-memory cap`() {
        // The unpack caps allow the archive; only the in-memory cap is small
        Archives.limits = Archives.Limits(pageBytes = 16)
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            val before = file.readBytes()
            shouldThrow<Archives.TooLargeException> { Archives.firstImage(file) }
            file.readBytes() shouldBe before
        }
    }

    @Test
    fun `a downloaded page is read back up to the size a download may save`() {
        // A chapter downloaded as a folder is read page by page through readPage: a page the
        // download kept (up to the page response limit) must open, and one past it must not
        val max = zoukan.desktop.image.PageSizeLimits.MAX_PAGE_BYTES
        Archives.Limits().pageBytes shouldBe max
        val chapter = File(temp, "Chapter 1").apply { mkdirs() }
        fun page(name: String, size: Long) = File(chapter, name).also { file ->
            java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
        }
        page("001.jpg", 64L * 1024 * 1024 + 1)
        page("002.jpg", max + 1)
        LocalFormat.Directory(chapter).use { format ->
            format.page(0).size shouldBe 64 * 1024 * 1024 + 1
            shouldThrow<Archives.TooLargeException> { format.page(1) }
        }
    }

    @Test
    fun `an unpacked page over the in-memory cap is refused`() {
        val file = tar(File(temp, "a.cbt"))
        val before = file.readBytes()
        Archives.limits = Archives.Limits(pageBytes = 16)
        LocalFormat.open(file).use { format ->
            shouldThrow<Archives.TooLargeException> { format.page(0) }
        }
        file.readBytes() shouldBe before
    }

    @Test
    fun `the cache holds pages and ComicInfo encrypted only`() {
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
        }
        val files = cachedFiles()
        // Four pages, ComicInfo.xml and the marker, for each chapter
        files.size shouldBe 12
        files.forEach { cached ->
            val stored = cached.readBytes()
            cipher.isEncoded(stored) shouldBe true
            stored.contains(pngSignature) shouldBe false
            stored.contains("Packed".toByteArray()) shouldBe false
        }
        Archives.comicInfo(File(temp, "a.cbt"))!!.values["Title"] shouldBe "Packed"
    }

    @Test
    fun `a cache written with another key is unpacked again`() {
        val file = tar(File(temp, "a.cbt"))
        LocalFormat.open(file).use { it.pageCount shouldBe 4 }
        cipher = newCipher()
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
        Archives.comicInfo(file)!!.values["Title"] shouldBe "Packed"
        cachedFiles().forEach { cipher.decode(it.readBytes()) }
    }

    @Test
    fun `the plain cache from before encryption is cleared once, and nothing else`() {
        val root = Archives.cacheRoot
        val old = File(root, "0123456789abcdef0123").apply { mkdirs() }
        File(old, "0001.png").writeBytes(png(1))
        File(old, ".done").createNewFile()
        File(root, "fedcba9876543210fedc.raw/pages").mkdirs()
        File(root, "fedcba9876543210fedc.raw/pages/1.png").writeBytes(png(1))
        val outside = File(temp, "downloads/Chapter 1/001.png").apply { parentFile.mkdirs() }
        outside.writeBytes(png(1))

        LocalFormat.open(tar(File(temp, "a.cbt"))).use { it.pageCount shouldBe 4 }

        old.exists() shouldBe false
        File(root, "fedcba9876543210fedc.raw").exists() shouldBe false
        outside.readBytes() shouldBe png(1)
        cachedFiles().forEach { cipher.isEncoded(it.readBytes()) shouldBe true }
    }

    @Test
    fun `without the file key nothing is cached on disk`() {
        val stale = File(Archives.cacheRoot, "0123456789abcdef0123").apply { mkdirs() }
        File(stale, "0001.png").writeBytes(png(1))
        Archives.codecSource = { null }
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
            Archives.comicInfo(file)!!.values["Title"] shouldBe "Packed"
        }
        cachedFiles() shouldBe emptyList()
        stale.exists() shouldBe false
        // The in-memory cap still holds
        Archives.limits = Archives.Limits(pageBytes = 16)
        LocalFormat.open(File(temp, "a.cbt")).use { format ->
            shouldThrow<Archives.TooLargeException> { format.page(0) }
        }
    }

    @Test
    fun `without the file key a chapter over the memory budget is refused, not unpacked`() {
        Archives.codecSource = { null }
        val total = entries.filter { it.first.endsWith(".png") }.sumOf { it.second.size.toLong() }
        listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
            Archives.limits = Archives.Limits(memoryBytes = total - 1)
            val failure = shouldThrow<Archives.TooLargeException> { LocalFormat.open(file) }
            failure.message!!.contains("without the file key") shouldBe true
            // Within the budget it opens as before
            Archives.limits = Archives.Limits(memoryBytes = total + 4096)
            LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
        }
        cachedFiles() shouldBe emptyList()
    }

    @Test
    fun `the cover fallback keeps only the first page and unpacks nothing to the cache`() {
        listOf<SettingsFileCodec?>(null, cipher).forEach { codec ->
            Archives.codecSource = { codec }
            listOf(tar(File(temp, "a.cbt")), sevenZ(File(temp, "b.cb7"))).forEach { file ->
                ImageIO.read(Archives.firstUnpackedPage(file)!!.inputStream()).width shouldBe 1
            }
            cachedFiles() shouldBe emptyList()
        }
        // An unpacked chapter's cover comes from the cache
        val file = tar(File(temp, "c.cbt"))
        LocalFormat.open(file).use { it.pageCount shouldBe 4 }
        ImageIO.read(Archives.firstUnpackedPage(file)!!.inputStream()).width shouldBe 1
    }

    @Test
    fun `the first-page sink reads in only pages that sort before the one it holds`() {
        val sink = Archives.FirstPageSink()
        val sep = File.separator
        sink.wants("ComicInfo.xml") shouldBe false
        listOf("10", "2", "1").forEach { name ->
            sink.wants("pages${sep}$name.png") shouldBe true
            sink.put("pages${sep}$name.png", png(name.toInt()))
        }
        // Read through, never into memory
        sink.wants("pages${sep}9.png") shouldBe false
        sink.wants("pages${sep}10.png") shouldBe false
        sink.first!!.first shouldBe "pages/1.png"
        sink.reset()
        sink.first shouldBe null
    }

    @Test
    fun `the system's tar streams what the Java readers cannot read, encrypted into the cache`() {
        val version = runCatching {
            ProcessBuilder("tar", "--version").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrDefault("")
        // libarchive's tar (macOS, Windows 10 and later) reads ZIPs and RAR 5; GNU tar does not
        assumeTrue(version.contains("bsdtar"), "no bsdtar on this machine")
        // A ZIP named like a 7z: Commons Compress refuses it, the system's tar reads it
        val file = File(temp, "zip.cb7")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
        Archives.comicInfo(file)!!.values["Title"] shouldBe "Packed"
        cachedFiles().forEach { cached ->
            val stored = cached.readBytes()
            cipher.isEncoded(stored) shouldBe true
            stored.contains(pngSignature) shouldBe false
        }
        // And without the key, into memory
        Archives.codecSource = { null }
        Archives.clearCache()
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
        cachedFiles() shouldBe emptyList()
        // The memory budget holds for what tar streams too
        Archives.limits = Archives.Limits(memoryBytes = 16)
        shouldThrow<Archives.TooLargeException> { Archives.open(file) }
        // Its cover still reads, holding one page at a time
        ImageIO.read(Archives.firstUnpackedPage(file)!!.inputStream()).width shouldBe 1
        Archives.limits = Archives.Limits()
        // The caps hold for what tar streams too
        Archives.codecSource = { cipher }
        Archives.limits = Archives.Limits(entries = 4)
        shouldThrow<Archives.TooLargeException> { Archives.open(file) }
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

    /** A 7z whose LZMA2 dictionary declares [dictionaryBytes]; the reader allocates that much to read it. */
    private fun sevenZWithDictionary(file: File, dictionaryBytes: Int) = file.apply {
        SevenZOutputFile(this).use { out ->
            out.setContentMethods(
                listOf(
                    org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration(
                        org.apache.commons.compress.archivers.sevenz.SevenZMethod.LZMA2,
                        org.tukaani.xz.LZMA2Options().apply { dictSize = dictionaryBytes },
                    ),
                ),
            )
            entries.forEach { (name, bytes) ->
                out.putArchiveEntry(out.createArchiveEntry(File(name), name))
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `a 7z that needs more memory than allowed fails with an exception, not by allocating it`() {
        val file = sevenZWithDictionary(File(temp, "big-dictionary.cb7"), 16 shl 20)
        Archives.limits = Archives.Limits(sevenZMemoryKiB = 1024)
        // An IOException the callers handle (a cover or ComicInfo the entry goes without), not an
        // out-of-memory error from a dictionary of the size the archive asks for
        shouldThrow<org.apache.commons.compress.MemoryLimitException> { Archives.firstImage(file) }
        shouldThrow<org.apache.commons.compress.MemoryLimitException> { Archives.comicInfo(file) }
        // Within the limit it reads as before
        Archives.limits = Archives.Limits()
        Archives.firstImage(file)!!.let { ImageIO.read(it.inputStream()).width } shouldBe 1
    }

    @Test
    fun `a 7z over the memory limit goes to the system's tar when the chapter opens`() {
        val version = runCatching {
            ProcessBuilder("tar", "--version").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrDefault("")
        assumeTrue(version.contains("bsdtar"), "no bsdtar on this machine")
        val file = sevenZWithDictionary(File(temp, "big-dictionary-open.cb7"), 16 shl 20)
        Archives.limits = Archives.Limits(sevenZMemoryKiB = 1024)
        LocalFormat.open(file).use { widths(it) shouldContainExactly listOf(1, 2, 9, 10) }
    }
}

/** An EPUB is untrusted: opening one must not fetch anything its XML points to. */
class EpubExternalEntityTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `an EPUB cannot make the parser fetch an external DTD or entity`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1500
            val contacted = CompletableFuture.supplyAsync {
                try {
                    server.accept().close()
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
            }
            val url = "http://127.0.0.1:${server.localPort}"
            val secret = File(dir, "secret.txt").apply { writeText("SECRET") }
            val container = """
                <?xml version="1.0"?>
                <!DOCTYPE container [
                  <!ENTITY % file SYSTEM "${secret.toURI()}">
                  <!ENTITY % dtd SYSTEM "$url/evil.dtd">
                  %dtd;
                  <!ENTITY general SYSTEM "$url/general">
                ]>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf"/></rootfiles>&general;
                </container>
            """.trimIndent()
            val opf = """
                <?xml version="1.0"?>
                <!DOCTYPE package SYSTEM "$url/package.dtd">
                <package xmlns="http://www.idpf.org/2007/opf">
                  <manifest><item id="p1" href="1.png"/></manifest>
                  <spine><itemref idref="p1"/></spine>
                </package>
            """.trimIndent()
            val epub = File(dir, "book.epub")
            ZipOutputStream(epub.outputStream()).use { zip ->
                mapOf("META-INF/container.xml" to container, "content.opf" to opf, "1.png" to "png").forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }

            // Whether it opens or refuses the file, nothing may be fetched
            runCatching { LocalFormat.open(epub).use { it.pageCount } }

            contacted.get(10, TimeUnit.SECONDS) shouldBe false
        }
    }
}
