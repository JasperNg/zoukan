package zoukan.desktop.download

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.StorageUnavailableException
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
        withTimeout(10_000) { kit.manager.awaitIdle() }
        kit.manager.queue.value.single().state.value shouldBe Download.State.ERROR
        File(kit.downloads, "A (EN)").exists() shouldBe false
        kit.awaitBackground()
    }

    @Test
    fun `deleting one entry's chapter keeps a same-named chapter of another entry in the shared folder`() = runBlocking<Unit> {
        lateinit var two: Manga
        lateinit var twos: List<Chapter>
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

/**
 * A delete the user was told goes to the trash never deletes for good: when the trash refuses a
 * file (a network share, a volume without one) the file stays and the clean-up says so.
 */
class DownloadTrashTest {

    @TempDir
    lateinit var temp: File

    private val originalTrash = SafeDelete.trash
    private val kits = mutableListOf<DownloadKit>()

    @AfterEach
    fun restore() {
        SafeDelete.trash = originalTrash
        kits.forEach { it.awaitBackground() }
    }

    @Test
    fun `a file the trash refuses is kept, not deleted for good`() {
        val kit = DownloadKit(temp).also { kits += it }
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        SafeDelete.trash = { false }

        SafeDelete.delete(kit.downloads, file, minDepth = 3, toTrash = true) shouldBe false

        file.readBytes().size shouldBe 3
    }

    @Test
    fun `a file the trash takes is gone, and a permanent delete still deletes`() {
        val kit = DownloadKit(temp).also { kits += it }
        val trashed = mutableListOf<File>()
        SafeDelete.trash = { f ->
            trashed += f
            f.delete()
        }
        val one = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val two = kit.write("A (EN)/Entry/Chapter 2.cbz")

        SafeDelete.delete(kit.downloads, one, minDepth = 3, toTrash = true) shouldBe true
        SafeDelete.delete(kit.downloads, two, minDepth = 3, toTrash = false) shouldBe true

        trashed.map { it.name } shouldBe listOf("Chapter 1.cbz")
        one.exists() shouldBe false
        two.exists() shouldBe false
    }

    @Test
    fun `the clean-up counts what the trash refused and keeps it`() = runBlocking<Unit> {
        val kit = DownloadKit(temp).also { kits += it }
        val entry = kit.manga(1, "Entry")
        val chapters = listOf(kit.chapter(1, 1, read = true), kit.chapter(2, 1, read = true))
        val one = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val two = kit.write("A (EN)/Entry/Chapter 2.cbz")
        SafeDelete.trash = { f -> f.name == "Chapter 1.cbz" && f.delete() }

        val plan = kit.manager.planCleanup(true, false, { listOf(entry) }, { chapters })
        // A replaced trash counts as working, so the preview says "trash"
        plan.toTrash shouldBe true
        val result = kit.manager.applyCleanupResult(plan, toTrash = plan.toTrash)

        result.deleted shouldBe 1
        result.notDeleted shouldBe 1
        one.exists() shouldBe false
        two.exists() shouldBe true
    }

    @Test
    fun `network shares have no trash`() {
        SafeDelete.volumeHasTrash("/Volumes/nas/Zoukan", "smbfs") shouldBe false
        SafeDelete.volumeHasTrash("/Volumes/nas/Zoukan", "afpfs") shouldBe false
        SafeDelete.volumeHasTrash("/mnt/share", "nfs") shouldBe false
        SafeDelete.volumeHasTrash("""\\server\share\Zoukan""", "NTFS") shouldBe false
        SafeDelete.volumeHasTrash("/Users/me/Zoukan", "apfs") shouldBe true
        SafeDelete.volumeHasTrash("""D:\Zoukan""", "NTFS") shouldBe true
    }
}

class DownloadSpaceTest {

    @Test
    fun `a disk that reports no free space is full`() {
        val min = DownloadManager.MIN_FREE_SPACE
        DownloadManager.hasRoom(0L) shouldBe false
        DownloadManager.hasRoom(min - 1) shouldBe false
        DownloadManager.hasRoom(min) shouldBe true
        DownloadManager.hasRoom(null) shouldBe true
    }

    @Test
    fun `a full disk error reads as not enough space`() {
        val full = localized(DMR.strings.download_disk_full)
        DownloadManager.errorText(IOException("No space left on device")) shouldBe full
        DownloadManager.errorText(IOException("There is not enough space on the disk")) shouldBe full
        DownloadManager.errorText(RuntimeException("wrapped", IOException("No space left on device"))) shouldBe full
        DownloadManager.errorText(IOException("HTTP 500")) shouldBe "HTTP 500"
    }
}

class ReadDeletionPolicyTest {

    private fun chapter(id: Long, read: Boolean, manga: Long = 1, number: Double = id.toDouble()) =
        Chapter.create().copy(id = id, mangaId = manga, chapterNumber = number, read = read)

    /** Chapters 1..5, all read, the fifth just read. */
    private val list = (1L..5L).map { chapter(it, read = true) }
    private val justRead get() = list[4]

    private fun decide(
        slots: Int,
        navigable: List<Chapter> = list,
        chapters: List<Chapter> = navigable,
        read: Chapter = justRead,
        duplicates: Boolean = false,
    ) = ReadDeletionPolicy.afterRead(read, navigable, chapters, slots, duplicates)

    @Test
    fun `slot 0 chooses the chapter just read`() {
        decide(0).toDelete.map { it.id } shouldContainExactly listOf(5L)
    }

    @Test
    fun `slot 1 chooses the read chapter before it, slot 2 the one two back`() {
        decide(1).toDelete.map { it.id } shouldContainExactly listOf(4L)
        decide(2).toDelete.map { it.id } shouldContainExactly listOf(3L)
    }

    @Test
    fun `an unread chapter in the slot is never chosen`() {
        val navigable = list.map { if (it.id == 4L) it.copy(read = false) else it }
        decide(1, navigable).toDelete.shouldBeEmpty()
    }

    @Test
    fun `with slots of 1 or more the open chapter is never chosen`() {
        for (slots in 1..3) decide(slots).toDelete.map { it.id } shouldNotContain 5L
    }

    @Test
    fun `slots off chooses nothing`() {
        decide(-1).toDelete.shouldBeEmpty()
    }

    @Test
    fun `a merged entry chapters are chosen from the chapter's own entry`() {
        val navigable = listOf(
            chapter(1, true, manga = 10),
            chapter(2, true, manga = 20),
            chapter(3, true, manga = 10),
        )
        val chosen = decide(1, navigable, read = navigable[2]).toDelete
        chosen.map { it.id } shouldContainExactly listOf(2L)
        chosen.single().mangaId shouldBe 20L
    }

    @Test
    fun `unread duplicates of the chapter number are marked read, and deleted only with slot 0`() {
        val dup = chapter(9, read = false, number = 5.0)
        val chapters = list + dup
        val withSlot1 = decide(1, chapters = chapters, duplicates = true)
        withSlot1.markRead.map { it.id } shouldContainExactly listOf(9L)
        withSlot1.toDelete.map { it.id } shouldNotContain 9L
        val withSlot0 = decide(0, chapters = chapters, duplicates = true)
        withSlot0.markRead.map { it.id } shouldContainExactly listOf(9L)
        withSlot0.toDelete.map { it.id } shouldContainExactly listOf(5L, 9L)
        decide(0, chapters = chapters, duplicates = false).markRead.shouldBeEmpty()
    }

    @Test
    fun `a slot before the first chapter chooses nothing`() {
        decide(5).toDelete.shouldBeEmpty()
    }
}

/**
 * What counts as a link when deleting: a folder named in another letter case than on disk, or a
 * Windows reparse point that is not a link, is the folder itself. Taken for a link, an interrupted
 * download's folder was refused on every retry, and trash and deletes refused it too.
 */
class SafeDeleteLinkDetectionTest {
    @TempDir
    lateinit var temp: File

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Whether [dir]'s file system finds a name in any letter case (macOS's and Windows' default). */
    private fun ignoresCase(dir: File): Boolean {
        val probe = File(dir, "CaseProbe").apply { mkdirs() }
        return File(dir, "caseprobe").exists().also { probe.delete() }
    }

    @Test
    fun `a folder named in another letter case than on disk is no link`() {
        assumeTrue(ignoresCase(temp), "the file system tells names apart by letter case")
        val onDisk = File(temp, "Entry/Chapter 1").apply { mkdirs() }
        File(onDisk, "001.png").writeText("page")
        // A source renamed the chapter by letter case only after an interrupted download
        val asListed = File(temp, "Entry/CHAPTER 1")

        SafeDelete.isLink(asListed.toPath()) shouldBe false
        SafeDelete.delete(temp, asListed) shouldBe true
        onDisk.exists() shouldBe false
    }

    @Test
    fun `a parent named in another letter case than on disk is no link either`() {
        assumeTrue(ignoresCase(temp), "the file system tells names apart by letter case")
        File(temp, "Source (EN)/Entry/Chapter 1").mkdirs()
        SafeDelete.isLink(File(temp, "source (en)/ENTRY/chapter 1").toPath()) shouldBe false
    }

    @Test
    fun `a symbolic link to a folder is still a link, also named in another letter case`() {
        assumeFalse(isWindows, "symbolic links need rights on Windows; the junction test covers it")
        val target = File(temp, "outside").apply { mkdirs() }
        File(temp, "Entry").mkdirs()
        val link = File(temp, "Entry/Linked")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        SafeDelete.isLink(link.toPath()) shouldBe true
        if (ignoresCase(temp)) SafeDelete.isLink(File(temp, "Entry/LINKED").toPath()) shouldBe true
    }

    @Test
    fun `a folder listed through a linked parent is still seen through the link`() {
        assumeFalse(isWindows, "symbolic links need rights on Windows")
        val real = File(temp, "real/Chapter 1").apply { mkdirs() }
        val linkedParent = File(temp, "linked")
        Files.createSymbolicLink(linkedParent.toPath(), real.parentFile.toPath())
        // The folder itself is not a link: only its parent is, which the callers check on their own
        SafeDelete.isLink(File(linkedParent, "Chapter 1").toPath()) shouldBe false
        real.exists() shouldBe true
    }

    @Test
    fun `a Windows junction is still a link, also named in another letter case`() {
        assumeTrue(isWindows, "junctions are Windows only")
        val target = File(temp, "outside").apply { mkdirs() }
        val junction = File(temp, "Junction")
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, target.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        assumeTrue(made == 0 && junction.exists(), "mklink /J failed")

        SafeDelete.isLink(junction.toPath()) shouldBe true
        SafeDelete.isLink(File(temp, "JUNCTION").toPath()) shouldBe true
    }
}
