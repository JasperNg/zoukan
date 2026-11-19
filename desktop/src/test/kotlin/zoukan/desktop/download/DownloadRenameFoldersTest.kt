package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Moving an entry's download folders when its title changes ([DownloadManager.renameManga]): each
 * folder to the name the new title gives it, never over anything on disk, all or none.
 */
class DownloadRenameFoldersTest {

    @TempDir
    lateinit var temp: File

    private fun listed(kit: DownloadKit) = File(kit.downloads, "A (EN)").list()!!.sorted()

    // --- One folder: what a rename did before several folders could move ---

    @Test
    fun `one folder moves to the new title's name`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Old/Chapter 1")

        kit.manager.renameManga(kit.manga(1, "Old"), "New") shouldBe true

        listed(kit) shouldBe listOf("New")
        kit.manager.findChapter(kit.manga(1, "New"), kit.chapter(1, 1)) shouldNotBe null
        kit.awaitBackground()
    }

    @Test
    fun `nothing to move is a rename that worked`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.manager.renameManga(kit.manga(1, "Old"), "New") shouldBe true
        kit.awaitBackground()
    }

    @Test
    fun `a folder of the new name on disk keeps both where they are`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Old/Chapter 1")
        kit.chapterFolder("A (EN)/New/Chapter 9")

        kit.manager.renameManga(kit.manga(1, "Old"), "New") shouldBe false

        File(kit.downloads, "A (EN)/Old/Chapter 1/001.png").exists() shouldBe true
        File(kit.downloads, "A (EN)/New/Chapter 9/001.png").exists() shouldBe true
        File(kit.downloads, "A (EN)/New/Chapter 1").exists() shouldBe false
        kit.awaitBackground()
    }

    @Test
    fun `a queued entry's folder does not move`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Old/Chapter 1")
        val manga = kit.manga(1, "Old")
        kit.manager.enqueue(manga, listOf(kit.chapter(2, 1)), start = false)

        kit.manager.renameManga(manga, "New") shouldBe false

        listed(kit) shouldBe listOf("Old")
        kit.manager.clearQueue()
        kit.awaitBackground()
    }
}
