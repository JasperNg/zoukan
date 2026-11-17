package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
