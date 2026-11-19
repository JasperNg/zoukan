package zoukan.desktop.download

import eu.kanade.tachiyomi.util.lang.Hash
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.tray.Notifications
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.Normalizer

/** Names must match the Android app's DownloadProvider, so a downloads folder works on both. */
class DownloadPathsTest {

    private fun name(name: String, scanlator: String? = null, hash: Boolean = false, ascii: Boolean = false) =
        DownloadPaths.chapterDirName(name, scanlator, "/chapter/1", disallowNonAscii = ascii, includeHash = hash)

    @Test
    fun `chapter names as on Android`() {
        name("Chapter 1") shouldBe "Chapter 1"
        name("Chapter 1", scanlator = "Group") shouldBe "Group_Chapter 1"
        name("Chapter 1", scanlator = " ") shouldBe "Chapter 1"
        name("") shouldBe "Chapter"
        name("Vol. 1: Ch. 2? <End>") shouldBe "Vol. 1_ Ch. 2_ _End_"
        name("...Hidden.") shouldBe "Hidden"
        name("Chapter 1", hash = true) shouldBe "Chapter 1_" + Hash.md5("/chapter/1").take(6)
    }

    @Test
    fun `non-ASCII names`() {
        name("第1話") shouldBe "第1話"
        name("第1話", ascii = true) shouldBe "e7acac1e8a9b1"
    }

    @Test
    fun `long names are cut at 229 bytes without splitting a character`() {
        val long = "あ".repeat(200)
        val result = name(long)
        (result.toByteArray().size <= FileNames.MAX_FILE_NAME_BYTES - 11) shouldBe true
        result.all { it == 'あ' } shouldBe true
    }

    @Test
    fun `a computed name that opens a folder no listed name matches is not taken for it`() {
        // On Windows "SOUSOU~1" is the short name of another entry's folder: it exists and is the
        // same file, but it is not that folder's spelling, and that folder is not this entry's
        val listed = setOf("Sousou no Frieren")
        DownloadPaths.spellingOnDisk("SOUSOU~1", listed) { true } shouldBe null
        // The same when the file system cannot say whether the two are one
        DownloadPaths.spellingOnDisk("Sousou No Frieren", listed) { false } shouldBe null
        // A listed name that differs in letter case or Unicode form, and is the same folder, is its spelling
        DownloadPaths.spellingOnDisk("Sousou No Frieren", listed) { it == "Sousou no Frieren" } shouldBe "Sousou no Frieren"
        val nfd = Normalizer.normalize("Café", Normalizer.Form.NFD)
        DownloadPaths.spellingOnDisk("Café", setOf(nfd)) { true } shouldBe nfd
    }

    @Test
    fun `natural order`() {
        listOf("10.jpg", "2.jpg", "001.jpg", "B.png", "a.png").sortedWith(FileNames.naturalOrder) shouldBe
            listOf("001.jpg", "2.jpg", "10.jpg", "a.png", "B.png")
    }

    /**
     * Android's names that are not valid everywhere are looked up on disk only where they can be on
     * disk: on Windows a device name (`NUL`, `com1.txt`) is never a folder, and passing it to the file
     * system would open the device.
     */
    @Test
    fun `device names are no legacy names on Windows`() {
        listOf("NUL", "con", "Aux.txt", "COM1", "lpt9.cbz", "NUL ").forEach { name ->
            DownloadPaths.legacyNames(name, windows = true) shouldContainExactly emptyList()
        }
    }

    @Test
    fun `device names stay legacy names where Android could write them`() {
        DownloadPaths.legacyNames("NUL", windows = false) shouldContainExactly listOf("NUL")
        DownloadPaths.legacyNames("com1.txt", windows = false) shouldContainExactly listOf("com1.txt")
    }

    @Test
    fun `other names not valid everywhere stay legacy names on Windows`() {
        // A trailing dot: not portable, and no device
        DownloadPaths.legacyNames("Title.", windows = true) shouldContainExactly listOf("Title.")
        // A portable name is never a legacy name
        DownloadPaths.legacyNames("Title", windows = true) shouldContainExactly emptyList()
        DownloadPaths.legacyNames("NUL_", windows = true) shouldContainExactly emptyList()
    }
}

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
    fun `an entry with both Android's folder and the desktop's keeps both when renamed`() = runBlocking<Unit> {
        val kit = DownloadKit(temp)
        val manga = kit.manga(1, "aux")
        kit.chapterFolder("A (EN)/aux/Chapter 1")
        kit.chapterFolder("A (EN)/aux_/Chapter 2")

        // Moving only one would leave the other's chapters out of view: neither moves, and the
        // user is told, not only the log
        val notice = async(start = CoroutineStart.UNDISPATCHED) { Notifications.flow.first() }
        kit.manager.renameManga(manga, "Renamed") shouldBe false
        withTimeout(5_000) { notice.await() }.let {
            it.kind shouldBe Notifications.Kind.WARNING
            it.text shouldContain "Renamed"
        }

        File(kit.downloads, "A (EN)/aux/Chapter 1/001.png").exists() shouldBe true
        File(kit.downloads, "A (EN)/aux_/Chapter 2/001.png").exists() shouldBe true
        File(kit.downloads, "A (EN)/Renamed").exists() shouldBe false
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
            Files.move(from, to)
        }

        ops.renameMangaFolders(manga, "Café Two").shouldBeNull()
        File(kit.downloads, "A (EN)").list()!!.toList() shouldContainExactlyInAnyOrder listOf("Café", asciiOld)

        // Reset moveFolder to real move and verify success + manual moveFoldersBack
        ops.moveFolder = { from, to -> Files.move(from, to) }
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
