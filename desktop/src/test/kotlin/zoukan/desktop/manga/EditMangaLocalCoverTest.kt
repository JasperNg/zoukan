package zoukan.desktop.manga

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.download.CbzEncryption
import zoukan.desktop.download.CbzReader
import zoukan.desktop.download.CbzWriter
import zoukan.desktop.local.LocalSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Changing a local entry's cover writes it into the entry's folder, as Android's LocalCoverManager. */
class EditMangaLocalCoverTest {

    @TempDir
    lateinit var temp: File

    private val password = "secret"
    private val updates = mutableListOf<MangaUpdate>()

    private fun image(format: String, n: Int = 4): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(n, n, BufferedImage.TYPE_INT_RGB), format, it) }.toByteArray()

    private val local get() = File(temp, "local")
    private val entry get() = File(local, "Alpha").apply { mkdirs() }

    private fun edit(withPassword: Boolean = true): EditManga {
        entry.isDirectory shouldBe true
        val source = LocalSource(folder = { local }, password = { if (withPassword) password.toCharArray() else null })
        val sources = mockk<SourceManager> { every { get(LocalSource.ID) } returns source }
        val updateManga = mockk<UpdateManga> { coEvery { await(capture(updates)) } returns true }
        return EditManga(mockk<SetCustomMangaInfo>(relaxed = true), updateManga, sources)
    }

    private val manga = Manga.create().copy(id = 1, source = LocalSource.ID, url = "Alpha", ogTitle = "Alpha")

    private fun picked(name: String, bytes: ByteArray) = File(temp, "picked/$name").apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    private fun names() = entry.listFiles()!!.map { it.name }.sorted()

    @Test
    fun `the cover is named after the image's type, not the picked file's extension`() = runBlocking<Unit> {
        val png = image("png")
        edit().setCover(manga, picked("misnamed.jpg", png))
        names() shouldContainExactly listOf("cover.png")
        File(entry, "cover.png").readBytes() shouldBe png
        updates.mapNotNull { it.thumbnailUrl } shouldContainExactly listOf(File(entry, "cover.png").toURI().toString())
    }

    @Test
    fun `an image of a type neither app finds as a cover is saved as a PNG both read`() = runBlocking<Unit> {
        edit().setCover(manga, picked("scan.bmp", image("bmp")))
        names() shouldContainExactly listOf("cover.png")
        ImageIO.read(File(entry, "cover.png")) shouldNotBe null
        LocalSource.findCover(entry)!!.name shouldBe "cover.png"
    }

    @Test
    fun `a file that is not an image is refused and the cover is kept`() = runBlocking<Unit> {
        val old = File(entry, "cover.jpg").apply { writeBytes(image("jpg")) }
        val before = old.readBytes()
        assertThrows<java.io.IOException> { runBlocking { edit().setCover(manga, picked("notes.png", "not an image".toByteArray())) } }
        names() shouldContainExactly listOf("cover.jpg")
        old.readBytes() shouldBe before
        updates shouldBe emptyList()
    }

    @Test
    fun `a cover that cannot be read leaves the old cover in place`() = runBlocking<Unit> {
        val old = File(entry, "cover.jpg").apply { writeBytes(image("jpg")) }
        val before = old.readBytes()
        assertThrows<java.io.IOException> { runBlocking { edit().setCover(manga, File(temp, "gone.png")) } }
        old.readBytes() shouldBe before
        names() shouldContainExactly listOf("cover.jpg")
    }

    @Test
    fun `a read-only entry folder keeps its cover`() = runBlocking<Unit> {
        val old = File(entry, "cover.jpg").apply { writeBytes(image("jpg")) }
        val before = old.readBytes()
        entry.setWritable(false)
        try {
            assertThrows<java.io.IOException> { runBlocking { edit().setCover(manga, picked("new.png", image("png"))) } }
        } finally {
            entry.setWritable(true)
        }
        old.readBytes() shouldBe before
        names() shouldContainExactly listOf("cover.jpg")
    }

    @Test
    fun `a new cover of the same type replaces the old one and only it`() = runBlocking<Unit> {
        // An entry folder from Android: chapters, details and its own files stay as they are
        File(entry, "cover.png").writeBytes(image("png", 2))
        File(entry, "ComicInfo.xml").writeText("<ComicInfo/>")
        File(entry, ".nomedia").writeText("")
        File(entry, "Chapter 1").mkdirs()
        File(entry, "Chapter 1/1.jpg").writeBytes(image("jpg"))
        val others = listOf("ComicInfo.xml", ".nomedia", "Chapter 1/1.jpg").associateWith { File(entry, it).readBytes() }

        val png = image("png", 8)
        edit().setCover(manga, picked("new.png", png))

        File(entry, "cover.png").readBytes() shouldBe png
        names() shouldContainExactly listOf(".nomedia", "Chapter 1", "ComicInfo.xml", "cover.png")
        others.forEach { (name, bytes) -> File(entry, name).readBytes() shouldBe bytes }
    }

    @Test
    fun `a cover of another type replaces the old file`() = runBlocking<Unit> {
        File(entry, "cover.jpg").writeBytes(image("jpg"))
        edit().setCover(manga, picked("new.png", image("png")))
        names() shouldContainExactly listOf("cover.png")
    }

    @Test
    fun `an encrypted cover stays encrypted`() = runBlocking<Unit> {
        val archive = File(entry, "cover.cbi")
        CbzWriter(archive, CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)).use {
            it.write("cover.jpg", image("jpg"))
        }
        val png = image("png", 8)
        edit().setCover(manga, picked("new.png", png))

        names() shouldContainExactly listOf("cover.cbi")
        CbzReader(archive) { password.toCharArray() }.use { reader ->
            reader.encrypted shouldBe true
            reader.read(reader.entries.single()) shouldBe png
        }
        updates.mapNotNull { it.thumbnailUrl } shouldContainExactly listOf(archive.toURI().toString())
    }

    @Test
    fun `an encrypted cover is not replaced in plain when the password is missing`() = runBlocking<Unit> {
        val archive = File(entry, "cover.cbi")
        CbzWriter(archive, CbzEncryption(password.toCharArray(), SecurityPreferences.EncryptionType.AES_256)).use {
            it.write("cover.jpg", image("jpg"))
        }
        val before = archive.readBytes()
        assertThrows<IllegalStateException> { runBlocking { edit(withPassword = false).setCover(manga, picked("new.png", image("png"))) } }
        archive.readBytes() shouldBe before
        names() shouldContainExactly listOf("cover.cbi")
    }
}
