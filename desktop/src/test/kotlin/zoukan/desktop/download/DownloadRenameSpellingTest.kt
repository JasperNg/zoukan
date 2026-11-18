package zoukan.desktop.download

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.Normalizer

/**
 * An entry's download folder on disk can be spelled differently from the name the desktop computes
 * for its title while the file system treats the two as one name: in letter case (the source changed
 * "One piece" to "One Piece", and a case-only rename keeps the folder as it was) or in Unicode form
 * (a folder in NFD, as some macOS volumes and copies have it, for a title in NFC). A rename must find
 * and move that folder, or every downloaded chapter drops out of view when the new title is saved.
 */
class DownloadRenameSpellingTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a folder whose letter case differs from the title's moves with a rename`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val folder = kit.chapterFolder("A (EN)/One piece/Chapter 1").parentFile
        val manga = kit.manga(1, "One Piece")
        val chapter = kit.chapter(1, 1)
        val renamed = kit.manga(1, "One Piece (Official)")
        val ignoresCase = sameName(folder, "ONE PIECE")

        kit.manager.renameManga(manga, "One Piece (Official)") shouldBe true

        val listed = File(kit.downloads, "A (EN)").list()!!.toList()
        if (ignoresCase) {
            // One name on this disk: the folder is the entry's and moves with it
            listed shouldContain "One Piece (Official)"
            listed shouldNotContain "One piece"
            kit.manager.findChapter(renamed, chapter) shouldNotBe null
        } else {
            // Two names on this disk: "One piece" is not the entry's folder and stays where it is
            listed shouldBe listOf("One piece")
            kit.manager.findChapter(renamed, chapter) shouldBe null
        }
        kit.awaitBackground()
    }

    // On macOS the JDK lists names in NFC, so this holds there either way; it guards a file system
    // that treats the two forms as one name but lists them as written
    @Test
    fun `a folder in another Unicode form than the title moves with a rename`() = runBlocking<Unit> {
        val nfc = Normalizer.normalize("Café", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("Café", Normalizer.Form.NFD)
        val kit = DownloadKit(temp)
        val folder = kit.chapterFolder("A (EN)/$nfd/Chapter 1").parentFile
        assumeTrue(sameName(folder, nfc), "this file system keeps NFC and NFD names apart")
        val manga = kit.manga(1, nfc)
        val chapter = kit.chapter(1, 1)

        kit.manager.renameManga(manga, "Café Renamed") shouldBe true

        val listed = File(kit.downloads, "A (EN)").list()!!.map { Normalizer.normalize(it, Normalizer.Form.NFC) }
        listed shouldBe listOf(Normalizer.normalize("Café Renamed", Normalizer.Form.NFC))
        kit.manager.findChapter(kit.manga(1, "Café Renamed"), chapter) shouldNotBe null
        kit.awaitBackground()
    }

    @Test
    fun `one folder found under two spellings counts once`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val folder = kit.chapterFolder("A (EN)/One piece/Chapter 1").parentFile
        assumeTrue(sameName(folder, "ONE PIECE"), "this file system keeps letter case apart")

        kit.paths.existingMangaDirs(1, "One Piece").size shouldBe 1
        kit.paths.existingMangaDirs(1, "One piece").size shouldBe 1
        kit.awaitBackground()
    }

    /** Whether [name] opens [folder] on this file system. */
    private fun sameName(folder: File, name: String): Boolean {
        val other = File(folder.parentFile, name)
        return other.exists() && java.nio.file.Files.isSameFile(other.toPath(), folder.toPath())
    }
}
