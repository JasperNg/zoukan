package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.download.DownloadManager.CleanupReason
import java.io.File
import java.nio.file.Files

/**
 * "Clean up downloaded chapters" never deletes what may not be the desktop's: the Android app's
 * downloads in a shared folder, folders of no entry (unless ticked), other entries' chapters in a
 * shared folder, merged entries' parts, what a link points to, or anything outside the downloads folder.
 */
class DownloadCleanupSafetyTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun kit(
        sources: List<DownloadKit.TestSource> = listOf(DownloadKit.TestSource("A", 1)),
        installed: List<DownloadKit.TestSource> = sources,
    ) = DownloadKit(temp, sources, installed).also { kits += it }

    @AfterEach
    fun waitForSaves() = kits.forEach { it.awaitBackground() }

    private fun DownloadKit.plan(
        mangas: List<Manga>,
        chapters: Map<Long, List<Chapter>>,
        removeRead: Boolean = true,
        removeNonFavorite: Boolean = true,
        protectedIds: Set<Long> = emptySet(),
    ) = runBlocking {
        manager.planCleanup(removeRead, removeNonFavorite, { mangas }, { chapters[it].orEmpty() }, protectedIds)
    }

    @Test
    fun `an orphaned folder is listed and only deleted when ticked`() = runBlocking<Unit> {
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        val orphan = kit.write("A (EN)/Nobody/Chapter 1.cbz").parentFile

        val plan = kit.plan(listOf(entry), mapOf(1L to listOf(kit.chapter(1, 1))))
        plan.orphans.map { it.file } shouldBe listOf(orphan)
        plan.orphans.single().bytes shouldBe 3

        kit.manager.applyCleanup(plan) shouldBe 0
        orphan.isDirectory shouldBe true

        kit.manager.applyCleanup(plan, orphansToDelete = setOf(orphan)) shouldBe 1
        orphan.exists() shouldBe false
    }

    @Test
    fun `files that are no chapter the desktop knows are kept, unfinished downloads are not`() = runBlocking<Unit> {
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        val android = kit.write("A (EN)/Entry/Chapter 7.cbz") // a chapter only the Android app knows
        val folder = kit.chapterFolder("A (EN)/Entry/Other_Chapter 8")
        val notes = kit.write("A (EN)/Entry/notes.txt")
        val tmp = kit.write("A (EN)/Entry/Chapter 1_tmp/001.png").parentFile
        val cbzTmp = kit.write("A (EN)/Entry/Chapter 1.cbz_tmp")

        val plan = kit.plan(listOf(entry), mapOf(1L to listOf(kit.chapter(1, 1, read = true))))
        plan.items.map { it.file }.shouldContainExactlyInAnyOrder(tmp, cbzTmp)
        plan.items.forEach { it.reason shouldBe CleanupReason.UNFINISHED }
        kit.manager.applyCleanup(plan) shouldBe 2

        android.exists() shouldBe true
        folder.isDirectory shouldBe true
        notes.exists() shouldBe true
    }

    @Test
    fun `a source folder with no entry in the database is left alone`() = runBlocking<Unit> {
        val kit = kit()
        val file = kit.write("A (EN)/Somebody/Chapter 1.cbz")
        val tmp = kit.write("A (EN)/Somebody/Chapter 2_tmp/")

        kit.plan(emptyList(), emptyMap()).items.shouldBeEmpty()
        kit.manager.cleanup(true, true, { emptyList() }, { emptyList() }) shouldBe 0

        file.exists() shouldBe true
        tmp.exists() shouldBe true
    }

    @Test
    fun `two sources with the same name share a folder, and the other source's entries own theirs`() = runBlocking<Unit> {
        val installed = DownloadKit.TestSource("A", 1)
        val other = DownloadKit.TestSource("A", 2) // same name and language: the same folder
        val kit = kit(listOf(installed, other), installed = listOf(installed))
        val one = kit.manga(1, "One", source = 1)
        val two = kit.manga(2, "Two", source = 2)
        val twosChapter = kit.write("A (EN)/Two/Chapter 5.cbz")

        val plan = kit.plan(listOf(one, two), mapOf(2L to listOf(kit.chapter(5, 2))))

        plan.orphans.shouldBeEmpty()
        kit.manager.applyCleanup(plan, plan.orphans.map { it.file }.toSet())
        twosChapter.exists() shouldBe true
    }

    @Test
    fun `entries whose titles give one folder keep each other's chapters`() = runBlocking<Unit> {
        val kit = kit()
        val left = kit.manga(1, "Same", favorite = false)
        val kept = kit.manga(2, "Same.") // the trailing dot is dropped: the same folder
        kit.paths.mangaDir(left) shouldBe kit.paths.mangaDir(kept)
        val shared = kit.write("A (EN)/Same/Chapter 1.cbz") // read by one entry, unread by the other
        val onlyLeft = kit.write("A (EN)/Same/Chapter 2.cbz")
        val keptsOwn = kit.write("A (EN)/Same/Chapter 3.cbz")
        val chapters = mapOf(
            1L to listOf(kit.chapter(1, 1, read = true), kit.chapter(2, 1, read = true)),
            2L to listOf(kit.chapter(11, 2, name = "Chapter 1"), kit.chapter(3, 2)),
        )

        val plan = kit.plan(listOf(left, kept), chapters)

        // Not "not in library": one of the folder's entries is in the library
        plan.items.map { it.reason }.toSet() shouldBe setOf(CleanupReason.READ)
        plan.items.map { it.file } shouldBe listOf(onlyLeft)
        kit.manager.applyCleanup(plan) shouldBe 1
        shared.exists() shouldBe true
        keptsOwn.exists() shouldBe true
        onlyLeft.exists() shouldBe false
    }

    @Test
    fun `the parts of a merged entry are not taken for entries that left the library`() = runBlocking<Unit> {
        val kit = kit()
        val part = kit.manga(5, "Part", favorite = false)
        val file = kit.write("A (EN)/Part/Chapter 1.cbz")

        val plan = kit.plan(listOf(part), mapOf(5L to listOf(kit.chapter(1, 5))), removeRead = false, protectedIds = setOf(5L))

        plan.items.shouldBeEmpty()
        kit.manager.applyCleanup(plan)
        file.exists() shouldBe true
    }

    @Test
    fun `a link is deleted as a link and what it points to survives`() = runBlocking<Unit> {
        // Windows makes symbolic links only with admin rights or developer mode; the junction test below covers it there
        assumeFalse(isWindows, "symbolic links need rights on Windows; see the junction test")
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        val outside = File(temp, "outside/Chapter 1").apply { mkdirs() }
        val page = File(outside, "001.png").apply { writeText("page") }
        File(kit.downloads, "A (EN)/Entry").mkdirs()
        val link = File(kit.downloads, "A (EN)/Entry/Chapter 1")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        // A linked folder of no entry is not walked into, nor offered for deleting
        val linkedOrphan = File(kit.downloads, "A (EN)/Linked")
        Files.createSymbolicLink(linkedOrphan.toPath(), File(temp, "outside").toPath())

        val plan = kit.plan(listOf(entry), mapOf(1L to listOf(kit.chapter(1, 1, read = true))))
        plan.orphans.shouldBeEmpty()
        kit.manager.applyCleanup(plan) shouldBe 2 // the link, then the emptied entry folder

        Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
        page.readText() shouldBe "page"
        Files.isSymbolicLink(linkedOrphan.toPath()) shouldBe true
    }

    @Test
    fun `a Windows junction is deleted as a link and the folder it points to survives`() = runBlocking<Unit> {
        assumeTrue(isWindows, "junctions are Windows only; the symbolic link test covers other systems")
        val kit = kit()
        val entry = kit.manga(1, "Entry")
        val outside = File(temp, "outside/Chapter 1").apply { mkdirs() }
        val page = File(outside, "001.png").apply { writeText("page") }
        File(kit.downloads, "A (EN)/Entry").mkdirs()
        val junction = File(kit.downloads, "A (EN)/Entry/Chapter 1")
        // A junction needs no rights, unlike a symbolic link
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, outside.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        assumeTrue(made == 0 && junction.exists(), "mklink /J failed")

        val plan = kit.plan(listOf(entry), mapOf(1L to listOf(kit.chapter(1, 1, read = true))))
        kit.manager.applyCleanup(plan, toTrash = true)

        junction.exists() shouldBe false
        page.readText() shouldBe "page"
    }

    @Test
    fun `nothing outside an entry folder in the downloads folder is touched`() = runBlocking<Unit> {
        val kit = kit()
        val entry = kit.manga(1, "Entry", favorite = false)
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        val outside = File(temp, "outside.cbz").apply { writeText("x") }
        val atRoot = kit.write("Chapter 1.cbz")
        val inSource = kit.write("A (EN)/Chapter 1.cbz")

        val plan = kit.plan(listOf(entry), mapOf(1L to listOf(kit.chapter(1, 1, read = true))))
        kit.manager.applyCleanup(plan, plan.orphans.map { it.file }.toSet())

        outside.exists() shouldBe true
        atRoot.exists() shouldBe true
        inSource.exists() shouldBe true
        File(kit.downloads, "A (EN)").isDirectory shouldBe true
    }

    @Test
    fun `the delete helper refuses paths outside the downloads folder or too high in it`() {
        val kit = kit()
        val outside = File(temp, "outside/file").apply {
            parentFile.mkdirs()
            writeText("x")
        }
        val sourceDir = kit.write("A (EN)/Entry/Chapter 1.cbz").parentFile.parentFile

        SafeDelete.delete(kit.downloads, outside) shouldBe false
        SafeDelete.delete(kit.downloads, File(kit.downloads, "A (EN)/../../outside/file")) shouldBe false
        SafeDelete.delete(kit.downloads, sourceDir, minDepth = 2) shouldBe false
        SafeDelete.delete(kit.downloads, kit.downloads, minDepth = 2) shouldBe false

        outside.exists() shouldBe true
        sourceDir.isDirectory shouldBe true
    }
}
