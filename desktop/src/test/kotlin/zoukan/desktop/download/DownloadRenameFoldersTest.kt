package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.tray.Notifications
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

    // --- Several folders ---

    /** What [block] posts that names [title], collected while it runs. */
    private suspend fun posted(title: String, block: suspend () -> Unit): List<Notifications.Message> = coroutineScope {
        val all = java.util.Collections.synchronizedList(mutableListOf<Notifications.Message>())
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(all) }
        yield()
        try {
            block()
        } finally {
            collector.cancelAndJoin()
        }
        all.filter { title in it.text }
    }

    private fun asciiName(title: String) = FileNames.buildValidFilename(title, disallowNonAscii = true)

    @Test
    fun `downloads made before and after the non-ASCII setting changed both follow the title`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Café/Chapter 1")
        kit.chapterFolder("A (EN)/${asciiName("Café")}/Chapter 2")

        val messages = posted("Café") { kit.manager.renameManga(kit.manga(1, "Café"), "Café Two") shouldBe true }

        messages.shouldBeEmpty()
        listed(kit) shouldBe listOf("Café Two", asciiName("Café Two")).sorted()
        val renamed = kit.manga(1, "Café Two")
        kit.manager.findChapter(renamed, kit.chapter(1, 1)) shouldNotBe null
        kit.manager.findChapter(renamed, kit.chapter(2, 1)) shouldNotBe null
        kit.awaitBackground()
    }

    @Test
    fun `two folders that would get one name stay, and the user is told once, not at every refresh`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"), "Android's device names are not folder names on Windows")
        val kit = DownloadKit(temp)
        // Android's name and the desktop's: both would be "Renamed", and a merge is not done here
        kit.chapterFolder("A (EN)/aux/Chapter 1")
        kit.chapterFolder("A (EN)/aux_/Chapter 2")
        val manga = kit.manga(1, "aux")

        val first = posted("Renamed") { kit.manager.renameManga(manga, "Renamed") shouldBe false }
        val again = posted("Renamed") { kit.manager.renameManga(manga, "Renamed") shouldBe false }

        first shouldHaveSize 1
        again.shouldBeEmpty()
        listed(kit) shouldBe listOf("aux", "aux_")
        kit.awaitBackground()
    }

    @Test
    fun `a folder whose new name is taken keeps every folder where it is`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Café/Chapter 1")
        kit.chapterFolder("A (EN)/${asciiName("Café")}/Chapter 2")
        kit.chapterFolder("A (EN)/${asciiName("Café Two")}/Chapter 9")

        kit.manager.renameManga(kit.manga(1, "Café"), "Café Two") shouldBe false

        listed(kit) shouldBe listOf("Café", asciiName("Café"), asciiName("Café Two")).sorted()
        File(kit.downloads, "A (EN)/${asciiName("Café Two")}").list()!!.toList() shouldBe listOf("Chapter 9")
        kit.awaitBackground()
    }

    @Test
    fun `a move that fails puts the folders already moved back`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Café/Chapter 1")
        kit.chapterFolder("A (EN)/${asciiName("Café")}/Chapter 2")
        var moves = 0
        kit.manager.moveFolder = { from, to ->
            if (++moves == 2) throw java.io.IOException("The folder is in use")
            java.nio.file.Files.move(from, to)
        }

        kit.manager.renameManga(kit.manga(1, "Café"), "Café Two") shouldBe false

        listed(kit) shouldBe listOf("Café", asciiName("Café")).sorted()
        val old = kit.manga(1, "Café")
        kit.manager.findChapter(old, kit.chapter(1, 1)) shouldNotBe null
        kit.manager.findChapter(old, kit.chapter(2, 1)) shouldNotBe null
        kit.awaitBackground()
    }

    @Test
    fun `moving back after a title that was not saved returns every folder to its name, and tells no one`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        kit.chapterFolder("A (EN)/Café/Chapter 1")
        kit.chapterFolder("A (EN)/${asciiName("Café")}/Chapter 2")
        val moves = kit.manager.renameMangaFolders(kit.manga(1, "Café"), "Café Two").shouldNotBeNull()
        moves shouldHaveSize 2

        val messages = posted("Café") { kit.manager.moveFoldersBack(moves) shouldBe true }

        messages.shouldBeEmpty()
        listed(kit) shouldBe listOf("Café", asciiName("Café")).sorted()
        kit.awaitBackground()
    }

    @Test
    fun `a folder another entry's downloads are in too does not move with one of them`() = runBlocking<Unit> {
        // "A/B" and "A:B" both download to "A_B"
        lateinit var kit: DownloadKit
        kit = DownloadKit(temp, coOwners = { m -> listOf(kit.manga(2, "A:B") to listOf(kit.chapter(9, 2))).filter { m.id != 2L } })
        kit.chapterFolder("A (EN)/A_B/Chapter 1")
        kit.chapterFolder("A (EN)/A_B/Chapter 9")

        kit.manager.renameManga(kit.manga(1, "A/B"), "A-B") shouldBe false

        listed(kit) shouldBe listOf("A_B")
        kit.manager.findChapter(kit.manga(2, "A:B"), kit.chapter(9, 2)) shouldNotBe null
        kit.awaitBackground()
    }
}
