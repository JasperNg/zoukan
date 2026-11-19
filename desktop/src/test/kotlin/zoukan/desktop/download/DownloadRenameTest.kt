package zoukan.desktop.download

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.tray.Notifications
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.Normalizer
import java.util.Collections
import kotlin.random.Random

/** Renaming a download after the source renamed its chapter, as Android's DownloadManager.renameChapter. */
class DownloadRenameTest {

    @TempDir
    lateinit var temp: File

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { source } returns 7L
        every { ogTitle } returns "Manga"
    }

    private fun setup(): Pair<DownloadManager, DownloadPaths> {
        val source = mockk<Source> { every { this@mockk.toString() } returns "Fake (EN)" }
        val sourceManager = mockk<SourceManager> { every { getOrStub(7L) } returns source }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))
        return manager to paths
    }

    private fun chapter(name: String, scanlator: String? = null) =
        Chapter.create().copy(id = 1, mangaId = 1, url = "/1", name = name, scanlator = scanlator)

    @Test
    fun `a renamed chapter's CBZ follows the new name`() {
        val (manager, paths) = setup()
        val old = chapter("Ch. 1")
        val new = chapter("Chapter 1", "Group")
        val dir = paths.mangaDir(manga).apply { mkdirs() }
        File(dir, "Ch. 1.cbz").writeText("zip")
        manager.isDownloaded(manga, old) shouldBe true
        paths.isChapterDirNameChanged(old, new) shouldBe true

        manager.renameChapter(manga, old, new)

        File(dir, "Group_Chapter 1.cbz").readText() shouldBe "zip"
        File(dir, "Ch. 1.cbz").exists() shouldBe false
        manager.isDownloaded(manga, new) shouldBe true
        manager.isDownloaded(manga, old) shouldBe false
    }

    @Test
    fun `a renamed chapter's folder follows the new name`() {
        val (manager, paths) = setup()
        val old = chapter("Ch. 2")
        val new = chapter("Chapter 2")
        val dir = File(paths.mangaDir(manga), "Ch. 2").apply { mkdirs() }
        File(dir, "001.png").writeText("png")

        manager.renameChapter(manga, old, new)

        File(paths.mangaDir(manga), "Chapter 2/001.png").readText() shouldBe "png"
        manager.isDownloaded(manga, new) shouldBe true
    }

    @Test
    fun `a rename never replaces another chapter's download`() {
        val (manager, paths) = setup()
        val dir = paths.mangaDir(manga).apply { mkdirs() }
        val a = Random(3).nextBytes(4096)
        val b = Random(4).nextBytes(4096)
        File(dir, "Ch. A.cbz").writeBytes(a)
        File(dir, "Chapter 10.cbz").writeBytes(b)

        manager.renameChapter(manga, chapter("Ch. A"), chapter("Chapter 10"))

        File(dir, "Chapter 10.cbz").readBytes() shouldBe b
        File(dir, "Ch. A.cbz").readBytes() shouldBe a
    }

    @Test
    fun `clearing an entry's downloaded data deletes its folder and the emptied source folder`() {
        val (manager, paths) = setup()
        val dir = File(paths.mangaDir(manga), "Ch. 3").apply { mkdirs() }
        File(dir, "001.png").writeText("png")
        manager.isDownloaded(manga, chapter("Ch. 3")) shouldBe true

        runBlocking { manager.deleteManga(manga, listOf(chapter("Ch. 3"))) }

        paths.mangaDir(manga).exists() shouldBe false
        paths.sourceDir(7L).exists() shouldBe false
        manager.isDownloaded(manga, chapter("Ch. 3")) shouldBe false
    }
}

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
        val all = Collections.synchronizedList(mutableListOf<Notifications.Message>())
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
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "Android's device names are not folder names on Windows")
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
            if (++moves == 2) throw IOException("The folder is in use")
            Files.move(from, to)
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
        return other.exists() && Files.isSameFile(other.toPath(), folder.toPath())
    }
}
