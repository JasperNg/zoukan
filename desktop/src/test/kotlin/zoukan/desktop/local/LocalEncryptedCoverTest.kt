package zoukan.desktop.local

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.CbzWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * `cover.cbi`: the encrypted cover archive Komikku writes into a local entry's folder when the
 * entry's chapters are encrypted. The desktop reads it as the cover and never writes the cover of
 * an encrypted entry in plain.
 */
class LocalEncryptedCoverTest {

    @TempDir
    lateinit var temp: File

    private val password = "secret"

    private fun png(n: Int = 3): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(n, n, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun encryption() = CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)

    private fun source(local: File, withPassword: Boolean = true) =
        LocalSource(folder = { local }, password = { if (withPassword) password.toCharArray() else null })

    /** An entry whose only chapter is an encrypted CBZ. */
    private fun encryptedEntry(): File {
        val entry = File(temp, "local/Secret").apply { mkdirs() }
        CbzWriter(File(entry, "Chapter 1.cbz"), encryption()).use { w -> (1..3).forEach { w.write("$it.png", png(it + 2)) } }
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
        CbzWriter(File(entry, "cover.cbi"), encryption()).use { it.write("cover.jpg", png()) }
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
            reader.read(reader.entries.single()) shouldBe png(3)
        }
        LocalSource.readCoverArchive(archive) { password.toCharArray() } shouldBe png(3)
        // The cover archive is no chapter
        update.chapters.map { it.url } shouldContainExactly listOf("Secret/Chapter 1.cbz")
        // No temporary file is left beside it
        entry.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf(".noxml", "Chapter 1.cbz", "cover.cbi")
    }

    @Test
    fun `an existing cover cbi is kept as it is`() = runBlocking<Unit> {
        val entry = encryptedEntry()
        val archive = File(entry, "cover.cbi")
        CbzWriter(archive, encryption()).use { it.write("cover.jpg", png(9)) }
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
        CbzWriter(archive, encryption()).use { it.write("cover.jpg", png()) }
        runCatching { LocalSource.readCoverArchive(archive) { null } }.exceptionOrNull()!!.message shouldBe
            zoukan.desktop.compat.localized(zoukan.i18n.desktop.DMR.strings.is_password_protected_set_the_download_password, "cover.cbi")
        runCatching { LocalSource.readCoverArchive(archive) { "wrong".toCharArray() } }.isFailure shouldBe true
        // A cbi that is no archive at all
        val broken = File(entry, "broken.cbi").apply { writeBytes(png()) }
        runCatching { LocalSource.readCoverArchive(broken) { password.toCharArray() } }.isFailure shouldBe true
    }

    @Test
    fun `a plain entry still gets a plain cover`() = runBlocking<Unit> {
        val entry = File(temp, "local/Plain").apply { mkdirs() }
        CbzWriter(File(entry, "Chapter 1.cbz")).use { w -> w.write("1.png", png()) }
        val manga = SManga.create().apply {
            url = "Plain"
            title = "Plain"
        }
        source(File(temp, "local")).getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
        LocalSource.findCover(entry)!!.name shouldBe "cover.png"
        File(entry, "cover.cbi").exists() shouldBe false
    }
}
