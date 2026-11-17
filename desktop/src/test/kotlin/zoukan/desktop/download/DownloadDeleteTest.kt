package zoukan.desktop.download

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.storage.StorageUnavailableException
import java.io.File

/** Deleting an entry's or a chapter's downloads deletes those and nothing else. */
class DownloadDeleteTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `deleting an entry's downloads keeps another entry's chapters in the same folder`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val one = kit.manga(1, "Same")
        val two = kit.manga(2, "Same.")
        val ones = listOf(kit.chapter(1, 1), kit.chapter(2, 1))
        kit.write("A (EN)/Same/Chapter 1.cbz")
        kit.chapterFolder("A (EN)/Same/Chapter 2")
        kit.write("A (EN)/Same/Chapter 2_tmp/001.png")
        val twos = kit.write("A (EN)/Same/Chapter 3.cbz")
        val unknown = kit.write("A (EN)/Same/Copied by hand.cbz")

        kit.manager.deleteManga(one, ones)

        File(kit.downloads, "A (EN)/Same").list()!!.toSet() shouldBe setOf("Chapter 3.cbz", "Copied by hand.cbz")
        twos.exists() shouldBe true
        unknown.exists() shouldBe true
        kit.manager.isDownloaded(two, kit.chapter(3, 2)) shouldBe true
        kit.awaitBackground()
    }

    @Test
    fun `deleting the last chapters removes the emptied folders`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val entry = kit.manga(1, "Entry")
        val chapters = listOf(kit.chapter(1, 1))
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        kit.chapterFolder("A (EN)/Entry/Chapter 1") // the same chapter as a folder too

        kit.manager.deleteManga(entry, chapters)

        File(kit.downloads, "A (EN)").exists() shouldBe false
        kit.downloads.isDirectory shouldBe true
        kit.awaitBackground()
    }

    @Test
    fun `cancelling deletes the unfinished download, unless the chapter was queued again`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val entry = kit.manga(1, "Entry")
        val cancelled = kit.chapter(1, 1)
        val requeued = kit.chapter(2, 1)
        val cancelledTmp = kit.write("A (EN)/Entry/Chapter 1_tmp/001.png").parentFile
        val requeuedTmp = kit.write("A (EN)/Entry/Chapter 2_tmp/001.png").parentFile
        kit.manager.enqueue(entry, listOf(cancelled, requeued), start = false)

        // The clean-up of cancelled chapters waits for the entry's lock; the chapter is queued again meanwhile
        kit.manager.entryLock(1).withLock {
            kit.manager.cancel(setOf(1L, 2L))
            kit.manager.enqueue(entry, listOf(requeued), start = false)
        }
        kit.awaitBackground()

        cancelledTmp.exists() shouldBe false
        requeuedTmp.isDirectory shouldBe true
        kit.manager.queue.value.map { it.chapter.id } shouldBe listOf(2L)
    }

    @Test
    fun `nothing is deleted or created when the storage folder is missing`() = runBlocking<Unit> {
        var available = true
        val kit = DownloadKit(temp, available = { available })
        val entry = kit.manga(1, "Entry")
        val chapters = listOf(kit.chapter(1, 1, read = true))
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        available = false

        shouldThrow<StorageUnavailableException> { kit.manager.deleteManga(entry, chapters) }
        shouldThrow<StorageUnavailableException> { kit.manager.delete(entry, chapters) }
        shouldThrow<StorageUnavailableException> { kit.manager.cleanup(true, true, { listOf(entry) }, { chapters }) }
        file.exists() shouldBe true

        // A download fails with the error instead of creating the folders on the system disk
        File(kit.downloads, "A (EN)").deleteRecursively()
        kit.manager.enqueue(entry, listOf(kit.chapter(2, 1)))
        kotlinx.coroutines.withTimeout(10_000) { kit.manager.awaitIdle() }
        kit.manager.queue.value.single().state.value shouldBe Download.State.ERROR
        File(kit.downloads, "A (EN)").exists() shouldBe false
        kit.awaitBackground()
    }

    @Test
    fun `deleting one entry's chapter keeps a same-named chapter of another entry in the shared folder`() = runBlocking<Unit> {
        lateinit var two: tachiyomi.domain.manga.model.Manga
        lateinit var twos: List<tachiyomi.domain.chapter.model.Chapter>
        val kit = DownloadKit(temp, coOwners = { manga -> if (manga.id == 1L) listOf(two to twos) else emptyList() })
        val one = kit.manga(1, "Same")
        two = kit.manga(2, "Same.")
        // Titles that give one folder, chapters with the same name: one file on disk for both
        val ones = listOf(kit.chapter(1, 1, name = "Chapter 1"), kit.chapter(3, 1, name = "Chapter 3"))
        twos = listOf(kit.chapter(2, 2, name = "Chapter 1"))
        val shared = kit.write("A (EN)/Same/Chapter 1.cbz")
        val own = kit.write("A (EN)/Same/Chapter 3.cbz")

        kit.manager.delete(one, ones)
        shared.exists() shouldBe true
        own.exists() shouldBe false

        // Clear manga (deleteManga) keeps it too
        kit.manager.deleteManga(one, ones)
        shared.exists() shouldBe true
        kit.manager.isDownloaded(two, twos.single()) shouldBe true
        kit.awaitBackground()
    }

    @Test
    fun `an automatic deletion with the storage folder missing deletes nothing and does not fail`() = runBlocking<Unit> {
        var available = true
        val kit = DownloadKit(temp, available = { available })
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        available = false

        // Marking chapters read must not fail because the drive is unplugged
        kit.manager.deleteRead(listOf(kit.chapter(1, 1, read = true)))

        file.exists() shouldBe true
        kit.awaitBackground()
    }
}
