package zoukan.desktop.download

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException

/** Unit tests for [DownloadFolderOps]: unified [findChapter], folder resolution, renames, rollbacks, and disk checks. */
class DownloadFolderOpsTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private fun kit(
        coOwners: (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = null,
        ignoreCase: Boolean? = null,
    ) = DownloadKit(temp, ignoreCase = ignoreCase, coOwners = coOwners).also { kits += it }

    @AfterEach
    fun tearDown() {
        kits.forEach { it.awaitBackground() }
    }

    @Test
    fun `DownloadPaths, DownloadFolderOps, and DownloadManager findChapter agree via one canonical implementation`() {
        val kit = kit()
        val manga = kit.manga(1, "Café")
        val c1 = kit.chapter(1, 1, name = "Chapter 1")
        val c2 = kit.chapter(2, 1, name = "Chapter 2")
        val emptyDir = kit.chapter(3, 1, name = "Chapter 3")
        val emptyCbz = kit.chapter(4, 1, name = "Chapter 4")

        val cbzFile = kit.write("A (EN)/Café/Chapter 1.cbz")
        val asciiAlt = FileNames.buildValidFilename("Café", disallowNonAscii = true)
        val folderInAlt = kit.chapterFolder("A (EN)/$asciiAlt/Chapter 2")
        kit.write("A (EN)/Café/Chapter 3/")
        kit.write("A (EN)/Café/Chapter 4.cbz", ByteArray(0))

        kit.paths.findChapter(manga, c1) shouldBe cbzFile
        kit.manager.folderOps.findChapter(manga, c1) shouldBe cbzFile
        kit.manager.findChapter(manga, c1) shouldBe cbzFile

        kit.paths.findChapter(manga, c2) shouldBe folderInAlt
        kit.manager.folderOps.findChapter(manga, c2) shouldBe folderInAlt
        kit.manager.findChapter(manga, c2) shouldBe folderInAlt

        // Empty folder and 0-byte CBZ are not valid downloads under the unified lookup
        kit.paths.findChapter(manga, emptyDir).shouldBeNull()
        kit.manager.folderOps.findChapter(manga, emptyDir).shouldBeNull()
        kit.paths.findChapter(manga, emptyCbz).shouldBeNull()
        kit.manager.folderOps.findChapter(manga, emptyCbz).shouldBeNull()
    }

    @Test
    fun `downloadedIds, downloadCount, and sharesDownloadFolder reflect disk state`() {
        val kit = kit()
        val m1 = kit.manga(1, "Same")
        val m2 = kit.manga(2, "Same.")
        val m3 = kit.manga(3, "Other")
        val ch1 = kit.chapter(1, 1, name = "Chapter 1")
        val ch2 = kit.chapter(2, 1, name = "Chapter 2")
        val ch3 = kit.chapter(3, 1, name = "Chapter 3")

        kit.write("A (EN)/Same/Chapter 1.cbz")
        kit.chapterFolder("A (EN)/Same/Chapter 1") // duplicate format counts once
        kit.chapterFolder("A (EN)/Same/Chapter 2")

        val ops = kit.manager.folderOps
        ops.downloadedIds(m1, listOf(ch1, ch2, ch3)) shouldBe setOf(1L, 2L)
        ops.downloadCount(m1) shouldBe 2
        ops.downloadCounts(listOf(m1, m3)) shouldBe mapOf(1L to 2, 3L to 0)
        ops.sharesDownloadFolder(m1, m2) shouldBe true
        ops.sharesDownloadFolder(m1, m3) shouldBe false
    }

    @Test
    fun `renameChapter renames CBZ and skips if target already exists`() {
        val kit = kit()
        val manga = kit.manga(1, "Entry")
        val old1 = kit.chapter(1, 1, name = "Ch 1")
        val new1 = kit.chapter(1, 1, name = "Chapter 1")
        val old2 = kit.chapter(2, 1, name = "Ch 2")
        val new2 = kit.chapter(2, 1, name = "Chapter 2")

        kit.write("A (EN)/Entry/Ch 1.cbz")
        kit.write("A (EN)/Entry/Ch 2.cbz")
        kit.write("A (EN)/Entry/Chapter 2.cbz")

        val ops = kit.manager.folderOps
        ops.renameChapter(manga, old1, new1) shouldBe true
        ops.findChapter(manga, new1).shouldNotBeNull().name shouldBe "Chapter 1.cbz"
        ops.findChapter(manga, old1).shouldBeNull()

        ops.renameChapter(manga, old2, new2) shouldBe false
        File(kit.downloads, "A (EN)/Entry/Ch 2.cbz").exists() shouldBe true
        File(kit.downloads, "A (EN)/Entry/Chapter 2.cbz").exists() shouldBe true
    }

    @Test
    fun `renameMangaFolders moves all folders and rolls back on failure`() = runBlocking<Unit> {
        val kit = kit()
        val manga = kit.manga(1, "Café")
        val asciiOld = FileNames.buildValidFilename("Café", disallowNonAscii = true)
        val asciiNew = FileNames.buildValidFilename("Café Two", disallowNonAscii = true)
        kit.chapterFolder("A (EN)/Café/Chapter 1")
        kit.chapterFolder("A (EN)/$asciiOld/Chapter 2")

        var moves = 0
        val ops = kit.manager.folderOps
        ops.moveFolder = { from, to ->
            if (++moves == 2) throw IOException("simulated failure")
            java.nio.file.Files.move(from, to)
        }

        ops.renameMangaFolders(manga, "Café Two").shouldBeNull()
        File(kit.downloads, "A (EN)").list()!!.toList() shouldContainExactlyInAnyOrder listOf("Café", asciiOld)

        // Reset moveFolder to real move and verify success + manual moveFoldersBack
        ops.moveFolder = { from, to -> java.nio.file.Files.move(from, to) }
        val completed = ops.renameMangaFolders(manga, "Café Two").shouldNotBeNull()
        File(kit.downloads, "A (EN)").list()!!.toList() shouldContainExactlyInAnyOrder listOf("Café Two", asciiNew)

        ops.moveFoldersBack(completed) shouldBe true
        File(kit.downloads, "A (EN)").list()!!.toList() shouldContainExactlyInAnyOrder listOf("Café", asciiOld)
    }

    @Test
    fun `renameMangaFolders blocks when entry is queued or co-owned`() = runBlocking<Unit> {
        var queued = true
        var coOwned = false
        val kit = DownloadKit(temp)
        val other = kit.manga(2, "Café")
        val ops = DownloadFolderOps(
            paths = kit.paths,
            preferences = kit.downloadPreferences,
            index = DownloadIndex(),
            coOwnerLookup = { { if (coOwned) listOf(other to emptyList()) else emptyList() } },
            isEntryQueued = { queued },
        )
        val manga = kit.manga(1, "Café")
        kit.chapterFolder("A (EN)/Café/Chapter 1")

        ops.renameMangaFolders(manga, "Renamed").shouldBeNull()

        queued = false
        coOwned = true
        ops.renameMangaFolders(manga, "Renamed").shouldBeNull()

        coOwned = false
        ops.renameManga(manga, "Renamed") shouldBe true
        File(kit.downloads, "A (EN)/Renamed/Chapter 1/001.png").exists() shouldBe true
    }

    @Test
    fun `usableSpace resolves nearest existing ancestor and invalidateCache bumps change listener`() {
        var changes = 0
        val kit = kit()
        val ops = DownloadFolderOps(
            paths = kit.paths,
            preferences = kit.downloadPreferences,
            onCacheChanged = { changes++ },
        )
        val nonExistentNested = File(temp, "missing/sub/dir")
        ops.usableSpace(nonExistentNested).shouldNotBeNull()

        ops.invalidateCache()
        changes shouldBe 1
    }
}
