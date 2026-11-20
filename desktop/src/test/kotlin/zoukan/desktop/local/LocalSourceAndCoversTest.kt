package zoukan.desktop.local

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.download.ComicInfoXml
import zoukan.i18n.desktop.is_password_protected_set_the_download_password
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

private fun testPng(size: Int = 3): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

class LocalSourceTest {

    @TempDir
    lateinit var temp: File

    private fun library(): File {
        val local = File(temp, "local")
        // A folder chapter and a CBZ chapter with its own ComicInfo.xml
        File(local, "Alpha/Chapter 1").apply { mkdirs() }.let { dir ->
            (1..3).forEach { File(dir, "$it.png").writeBytes(testPng()) }
        }
        CbzWriter(File(local, "Alpha/Chapter 2.cbz")).use { w ->
            (1..10).forEach { w.write("$it.png", testPng()) }
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

/**
 * Chapter URLs come from the library DB and are restored verbatim from (possibly hostile) backups,
 * so [LocalSource.chapterFile] and [LocalSource.mangaDir] must never resolve outside the local folder.
 */
class LocalSourcePathTest {

    @TempDir
    lateinit var temp: File

    private fun local(): File = File(temp, "local").apply { File(this, "Alpha/Chapter 1").mkdirs() }

    private fun source(): LocalSource = LocalSource(folder = { local() })

    private fun secret(): File = File(temp, "secret.cbz").apply { writeBytes(ByteArray(0)) }

    @Test
    fun `resolves a chapter url inside the local folder`() {
        val source = source()
        source.chapterFile("Alpha/Chapter 1").canonicalPath shouldBe File(local(), "Alpha/Chapter 1").canonicalPath
        source.mangaDir("Alpha")!!.canonicalPath shouldBe File(local(), "Alpha").canonicalPath
    }

    @Test
    fun `rejects a chapter url escaping the folder with dot-dot`() {
        val outside = secret()
        val source = source()
        val e = shouldThrow<IllegalStateException> { source.chapterFile("Alpha/../../secret.cbz") }
        e.message shouldContain "Invalid chapter path"
        outside.isFile shouldBe true
    }

    @Test
    fun `an absolute-path chapter url never reaches the file outside the folder`() {
        // "Alpha/<absolute path>". On macOS and Linux File(parent, child) keeps the parent, so the path stays inside
        // the folder and simply does not exist; on Windows the embedded drive makes it invalid. Either way it fails,
        // and never resolves to the file outside.
        val absolute = secret().absolutePath.replace(File.separatorChar, '/')
        val source = source()
        val e = shouldThrow<IllegalStateException> { source.chapterFile("Alpha/$absolute") }
        (e.message!!.startsWith("Invalid chapter path") || e.message!!.startsWith("Chapter not found")) shouldBe true
    }

    @Test
    fun `rejects a sibling folder sharing only the folder name prefix`() {
        File(temp, "localX/steal.cbz").apply { parentFile.mkdirs() }.writeBytes(ByteArray(0))
        val source = source()
        source.mangaDir("../localX") shouldBe null
    }
}

/**
 * `cover.cbi`: the encrypted cover archive Komikku writes into a local entry's folder when the
 * entry's chapters are encrypted. The desktop reads it as the cover and never writes the cover of
 * an encrypted entry in plain.
 */
class LocalEncryptedCoverTest {

    @TempDir
    lateinit var temp: File

    private val password = "secret"

    private fun encryption() = CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)

    private fun source(local: File, withPassword: Boolean = true) =
        LocalSource(folder = { local }, password = { if (withPassword) password.toCharArray() else null })

    /** An entry whose only chapter is an encrypted CBZ. */
    private fun encryptedEntry(): File {
        val entry = File(temp, "local/Secret").apply { mkdirs() }
        CbzWriter(File(entry, "Chapter 1.cbz"), encryption()).use { w -> (1..3).forEach { w.write("$it.png", testPng(it + 2)) } }
        return entry
    }

    private fun manga() = SManga.create().apply {
        url = "Secret"
        title = "Secret"
    }

    private fun plainCovers(entry: File) = entry.listFiles().orEmpty().filter { it.nameWithoutExtension == "cover" && it.extension != "cbi" }

    @Test
    fun `a cover cbi is the entry's cover`() {
        val entry = File(temp, "local/A").apply { mkdirs() }
        CbzWriter(File(entry, "cover.cbi"), encryption()).use { it.write("cover.jpg", testPng()) }
        LocalSource.findCover(entry)!!.name shouldBe "cover.cbi"
    }

    @Test
    fun `the cover of an encrypted entry is written encrypted as cover cbi, never in plain`() = runBlocking<Unit> {
        val entry = encryptedEntry()
        val update = source(File(temp, "local")).getMangaUpdate(manga(), emptyList(), fetchDetails = true, fetchChapters = true)

        plainCovers(entry) shouldBe emptyList()
        val archive = File(entry, "cover.cbi")
        archive.isFile shouldBe true
        update.manga.thumbnail_url shouldBe archive.toURI().toString()
        CbzReader(archive) { password.toCharArray() }.use { reader ->
            reader.encrypted shouldBe true
            // One entry named as Android names it, holding the first page
            reader.entries.map { it.fileName } shouldContainExactly listOf("cover.jpg")
            reader.read(reader.entries.single()) shouldBe testPng(3)
        }
        LocalSource.readCoverArchive(archive) { password.toCharArray() } shouldBe testPng(3)
        // The cover archive is no chapter
        update.chapters.map { it.url } shouldContainExactly listOf("Secret/Chapter 1.cbz")
        // No temporary file is left beside it
        entry.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf(".noxml", "Chapter 1.cbz", "cover.cbi")
    }

    @Test
    fun `an existing cover cbi is kept as it is`() = runBlocking<Unit> {
        val entry = encryptedEntry()
        val archive = File(entry, "cover.cbi")
        CbzWriter(archive, encryption()).use { it.write("cover.jpg", testPng(9)) }
        val before = archive.readBytes()

        val update = source(File(temp, "local")).getMangaUpdate(manga(), emptyList(), fetchDetails = true, fetchChapters = true)

        archive.readBytes() shouldBe before
        plainCovers(entry) shouldBe emptyList()
        update.manga.thumbnail_url shouldBe archive.toURI().toString()
        update.chapters.map { it.url } shouldContainExactly listOf("Secret/Chapter 1.cbz")
    }

    @Test
    fun `without the password no cover is written`() = runBlocking<Unit> {
        val entry = encryptedEntry()
        source(File(temp, "local"), withPassword = false).getMangaUpdate(manga(), emptyList(), fetchDetails = false, fetchChapters = true)
        entry.listFiles()!!.map { it.name } shouldContainExactly listOf("Chapter 1.cbz")
    }

    @Test
    fun `a cover cbi without the password or with a wrong one is an error, not a crash`() {
        val entry = File(temp, "local/A").apply { mkdirs() }
        val archive = File(entry, "cover.cbi")
        CbzWriter(archive, encryption()).use { it.write("cover.jpg", testPng()) }
        runCatching { LocalSource.readCoverArchive(archive) { null } }.exceptionOrNull()!!.message shouldBe
            zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.is_password_protected_set_the_download_password, "cover.cbi")
        runCatching { LocalSource.readCoverArchive(archive) { "wrong".toCharArray() } }.isFailure shouldBe true
        // A cbi that is no archive at all
        val broken = File(entry, "broken.cbi").apply { writeBytes(testPng()) }
        runCatching { LocalSource.readCoverArchive(broken) { password.toCharArray() } }.isFailure shouldBe true
    }

    @Test
    fun `a plain entry still gets a plain cover`() = runBlocking<Unit> {
        val entry = File(temp, "local/Plain").apply { mkdirs() }
        CbzWriter(File(entry, "Chapter 1.cbz")).use { w -> w.write("1.png", testPng()) }
        val manga = SManga.create().apply {
            url = "Plain"
            title = "Plain"
        }
        source(File(temp, "local")).getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
        LocalSource.findCover(entry)!!.name shouldBe "cover.png"
        File(entry, "cover.cbi").exists() shouldBe false
    }
}

/**
 * A cover write that was killed half way leaves its hidden temporary file (`.cover.<digits>.tmp`)
 * in the entry's folder, which may have come from the Android app. The next cover write there
 * removes the app's own stale ones, and nothing else.
 */
class LocalCoverTempFilesTest {

    @TempDir
    lateinit var temp: File

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 0)

    private fun File.aged(hours: Long) = apply {
        Files.setLastModifiedTime(toPath(), FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours)))
    }

    @Test
    fun `a cover write removes the app's stale cover temporary files and keeps everything else`() {
        val entry = File(temp, "local/Entry").apply { mkdirs() }
        val stale = File(entry, ".cover.1234567890.tmp").apply { writeText("half a cover") }.aged(48)
        // Just written, perhaps by another cover write right now
        val fresh = File(entry, ".cover.987.tmp").apply { writeText("being written") }.aged(0)
        // Not the app's pattern, or not a file the app wrote
        val other = listOf(".cover.abc.tmp", "cover.123.tmp", ".cover.123.tmp.bak", ".Cover.123.tmp")
            .map { File(entry, it).apply { writeText("not ours") }.aged(48) }
        val folder = File(entry, ".cover.555.tmp").apply { mkdirs() }.aged(48)
        val chapter = File(entry, "Chapter 1.cbz").apply { writeText("chapter") }.aged(48)

        LocalSource(folder = { File(temp, "local") }).writeCover(entry, png, encrypted = false)

        stale.exists() shouldBe false
        fresh.exists() shouldBe true
        other.forEach { it.exists() shouldBe true }
        folder.isDirectory shouldBe true
        chapter.readText() shouldBe "chapter"
        File(entry, "cover.png").exists() shouldBe true
    }

    @Test
    fun `a stale-looking link is never followed or removed`() {
        val entry = File(temp, "local/Entry").apply { mkdirs() }
        val target = File(temp, "elsewhere.txt").apply { writeText("the user's file") }.aged(48)
        val link = try {
            Files.createSymbolicLink(File(entry, ".cover.42.tmp").toPath(), target.toPath())
        } catch (e: Exception) {
            null
        }
        assumeTrue(link != null, "symbolic links cannot be made here")
        // The link itself as old as a stale file, where the OS lets its time be set
        runCatching {
            val old = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(48))
            Files.getFileAttributeView(link!!, java.nio.file.attribute.BasicFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .setTimes(old, null, null)
        }

        LocalSource(folder = { File(temp, "local") }).writeCover(entry, png, encrypted = false)

        Files.isSymbolicLink(link!!) shouldBe true
        target.readText() shouldBe "the user's file"
    }
}
