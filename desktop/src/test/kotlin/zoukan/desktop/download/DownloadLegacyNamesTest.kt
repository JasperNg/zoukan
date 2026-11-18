package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A name the Android app builds that is not valid on every OS (a Windows device name, a name cut to
 * end in a dot, half a surrogate pair) is written by the desktop in a portable form, but a folder
 * Android or an older desktop wrote under Android's name is still found, counted, deleted, cleaned
 * up and renamed. These folders cannot exist on Windows, so the tests run on macOS and Linux.
 */
class DownloadLegacyNamesTest {

    @TempDir
    lateinit var temp: File

    @BeforeEach
    fun notOnWindows() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "Android's names are not valid folder names on Windows")
    }

    @Test
    fun `an entry and chapter Android named with a device name are found and written under portable names`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val manga = kit.manga(1, "nul")
        val chapter = kit.chapter(1, 1, name = "CON.1")
        val legacy = kit.chapterFolder("A (EN)/nul/CON.1")

        kit.manager.isDownloaded(manga, chapter) shouldBe true
        kit.manager.findChapter(manga, chapter) shouldBe legacy
        kit.manager.downloadCount(manga) shouldBe 1
        kit.paths.findChapter(manga, chapter) shouldBe legacy

        // A new download goes to the portable names
        kit.paths.mangaDir(manga).name shouldBe "nul_"
        kit.paths.chapterDirName(chapter) shouldBe "CON_.1"

        // Both folders are the entry's: the chapter in either is found, and both are counted once
        kit.chapterFolder("A (EN)/nul_/CON_.1")
        kit.manager.downloadCount(manga) shouldBe 1

        kit.manager.delete(manga, listOf(chapter))
        legacy.exists() shouldBe false
        File(kit.downloads, "A (EN)/nul_/CON_.1").exists() shouldBe false
        kit.manager.isDownloaded(manga, chapter) shouldBe false
        kit.awaitBackground()
    }

    @Test
    fun `a clean-up keeps Android's folder of an entry in the library`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val manga = kit.manga(1, "nul")
        val chapter = kit.chapter(1, 1, name = "CON.1")
        kit.chapterFolder("A (EN)/nul/CON.1")

        val plan = kit.manager.planCleanup(removeRead = true, removeNonFavorite = true, { listOf(manga) }, { listOf(chapter) })

        plan.items shouldBe emptyList()
        kit.awaitBackground()
    }

    @Test
    fun `renaming an entry moves the folder Android named`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val manga = kit.manga(1, "aux")
        kit.chapterFolder("A (EN)/aux/Chapter 1")

        kit.manager.renameManga(manga, "Renamed") shouldBe true

        File(kit.downloads, "A (EN)/Renamed/Chapter 1/001.png").exists() shouldBe true
        File(kit.downloads, "A (EN)/aux").exists() shouldBe false
        kit.awaitBackground()
    }

    @Test
    fun `a name cut to end in a dot, and half a surrogate pair written as a question mark, are found`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        // Android cuts the chapter name at 229 bytes, leaving a dot at the end
        val cut = kit.chapter(1, 1, name = "a".repeat(228) + ".b")
        // An older desktop on macOS and Linux wrote the half pair as '?'
        val half = kit.chapter(2, 1, name = "x\ud800y")
        val manga = kit.manga(1, "Entry")
        val cutFolder = kit.chapterFolder("A (EN)/Entry/" + "a".repeat(228) + ".")
        val halfFolder = kit.write("A (EN)/Entry/x?y.cbz")

        kit.paths.chapterDirName(cut) shouldBe "a".repeat(228)
        kit.paths.chapterDirName(half) shouldBe "x_y"
        kit.manager.findChapter(manga, cut) shouldBe cutFolder
        kit.manager.findChapter(manga, half) shouldBe halfFolder
        kit.awaitBackground()
    }
}
