package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.DownloadManager.CleanupReason
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Unit tests for [DownloadCleanup]: stopping-job/waiter coordination, category filtering, and plan/apply recheck. */
class DownloadCleanupUnitTest {

    @TempDir
    lateinit var temp: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val kits = mutableListOf<DownloadKit>()

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
        kits.forEach { it.awaitBackground() }
    }

    private fun cleanup(
        kit: DownloadKit,
        getManga: GetManga = mockk(relaxed = true),
        getCategories: GetCategories = mockk(relaxed = true),
        queuedChapterIds: () -> Set<Long> = { emptySet() },
        queuedMangaIds: () -> Set<Long> = { emptySet() },
        coOwners: (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = null,
    ): Pair<DownloadCleanup, Any> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val lock = Any()
        val ops = DownloadFolderOps(kit.paths, kit.downloadPreferences)
        return DownloadCleanup(
            paths = kit.paths,
            sourceManager = kit.sourceManager,
            preferences = kit.downloadPreferences,
            getManga = getManga,
            getCategories = getCategories,
            folderOps = ops,
            scope = scope,
            queueLock = lock,
            queuedChapterIds = queuedChapterIds,
            queuedMangaIds = queuedMangaIds,
            coOwnerLookup = { coOwners },
        ) to lock
    }

    @Test
    fun `cancelAndTrack and awaitStopping coordinate waiters including child jobs`() = runBlocking<Unit> {
        val kit = DownloadKit(temp).also { kits += it }
        val (c, lock) = cleanup(kit)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val stoppingJob = launch(Dispatchers.IO) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                // Simulate in-flight file close that finishes after cancellation
                withContext(NonCancellable) {
                    release.await()
                }
            }
        }
        withTimeout(5_000) { started.await() }

        synchronized(lock) {
            c.cancelAndTrack(stoppingJob)
        }
        c.stoppingCount() shouldBe 1

        val parentWaiter = async(Dispatchers.IO) {
            // Nested child coroutine calls awaitStopping
            launch { c.awaitStopping() }.join()
        }
        withTimeout(5_000) {
            Await.untilSuspending("waiter to register on stoppingJob") {
                c.isWaitingFor(parentWaiter, stoppingJob)
            }
        }

        release.complete(Unit)
        withTimeout(5_000) { parentWaiter.await() }
        c.isWaitingFor(parentWaiter, stoppingJob) shouldBe false
        c.stoppingCount() shouldBe 0
    }

    @Test
    fun `deleteCancelledTempFiles waits for stopped jobs and preserves re-queued chapters`() = runBlocking<Unit> {
        val kit = DownloadKit(temp).also { kits += it }
        val manga = kit.manga(1, "Entry")
        val ch1 = kit.chapter(1, 1)
        val ch2 = kit.chapter(2, 1)
        val tmp1 = kit.write("A (EN)/Entry/Chapter 1_tmp/001.png").parentFile
        val tmp2 = kit.write("A (EN)/Entry/Chapter 2.cbz_tmp")
        val queued = mutableSetOf(2L)
        val (c, _) = cleanup(kit, queuedChapterIds = { queued })

        val blocker = Job()
        val cleanupJob = c.deleteCancelledTempFiles(
            stopped = listOf(blocker),
            removed = listOf(Download(manga, ch1), Download(manga, ch2)),
        )

        // While blocker is active, neither temp path is deleted
        tmp1.exists() shouldBe true
        tmp2.exists() shouldBe true

        blocker.complete()
        withTimeout(5_000) { cleanupJob.join() }

        tmp1.exists() shouldBe false
        tmp2.exists() shouldBe true
    }

    @Test
    fun `chaptersToDelete respects excluded categories, bookmark setting, and fails closed on error`() = runBlocking<Unit> {
        val kit = DownloadKit(temp).also { kits += it }
        val manga = kit.manga(1, "Entry")
        val readPlain = kit.chapter(1, 1, read = true).copy(bookmark = false)
        val readBookmarked = kit.chapter(2, 1, read = true).copy(bookmark = true)
        val unreadPlain = kit.chapter(3, 1, read = false).copy(bookmark = false)
        val chapters = listOf(readPlain, readBookmarked, unreadPlain)

        val getCategories = mockk<GetCategories>()
        val (c, _) = cleanup(kit, getCategories = getCategories)

        // Uncategorized defaults to 0L: excluding "0" keeps read chapters
        kit.downloadPreferences.removeExcludeCategories().set(setOf("0"))
        kit.downloadPreferences.removeBookmarkedChapters().set(false)
        coEvery { getCategories.await(1L) } returns emptyList()
        c.chaptersToDelete(manga, chapters).map { it.id } shouldContainExactly listOf(3L)

        // Category not excluded + allow bookmarked -> all allowed
        val cat = mockk<Category> { every { id } returns 5L }
        coEvery { getCategories.await(1L) } returns listOf(cat)
        kit.downloadPreferences.removeBookmarkedChapters().set(true)
        c.chaptersToDelete(manga, chapters).map { it.id } shouldContainExactly listOf(1L, 2L, 3L)

        // Disallow bookmarked -> chapter 2 kept
        kit.downloadPreferences.removeBookmarkedChapters().set(false)
        c.chaptersToDelete(manga, chapters).map { it.id } shouldContainExactly listOf(1L, 3L)

        // Failure reading categories fails closed (deletes nothing)
        coEvery { getCategories.await(1L) } throws IOException("db failed")
        c.chaptersToDelete(manga, chapters).shouldBeEmpty()
    }

    @Test
    fun `planCleanup and applyCleanupResult identify unfinished, read, non-library, and orphan folders with recheck`() = runBlocking<Unit> {
        val kit = DownloadKit(temp).also { kits += it }
        val inLib = kit.manga(1, "InLib", favorite = true)
        val leftLib = kit.manga(2, "LeftLib", favorite = false)
        val ch1 = kit.chapter(10, 1, name = "Chapter 1", read = true)
        val ch2 = kit.chapter(20, 2, name = "Chapter 1", read = false)

        val unfinished = kit.write("A (EN)/InLib/Chapter 2_tmp/001.png").parentFile
        val readFile = kit.write("A (EN)/InLib/Chapter 1.cbz")
        val leftFile = kit.write("A (EN)/LeftLib/Chapter 1.cbz")
        kit.chapterFolder("A (EN)/Stray/Chapter 1")
        val orphanDir = File(kit.downloads, "A (EN)/Stray")

        var leftStillFavorite = false
        val (c, _) = cleanup(kit)
        val plan = c.planCleanup(
            removeRead = true,
            removeNonFavorite = true,
            allManga = { listOf(inLib, leftLib.let { if (leftStillFavorite) kit.manga(2, "LeftLib", favorite = true) else it }) },
            chaptersOf = { id -> if (id == 1L) listOf(ch1) else listOf(ch2) },
        )

        plan.others.map { it.file to it.reason }.toSet() shouldBe setOf(
            unfinished to CleanupReason.UNFINISHED,
            readFile to CleanupReason.READ,
            leftFile to CleanupReason.NOT_IN_LIBRARY,
        )
        plan.orphans.map { it.file } shouldContainExactly listOf(orphanDir)

        // Re-favorite LeftLib before applying: recheck skips its file, deletes InLib's read+unfinished and the ticked orphan
        leftStillFavorite = true
        val result = c.applyCleanupResult(plan, orphansToDelete = setOf(orphanDir), toTrash = false)

        result.skipped shouldBe 1
        result.deleted shouldBe 4 // orphanDir, unfinished, readFile, plus emptied InLib folder
        result.notDeleted shouldBe 0
        leftFile.exists() shouldBe true
        orphanDir.exists() shouldBe false
        readFile.exists() shouldBe false
        unfinished.exists() shouldBe false
    }
}

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

        Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS) shouldBe false
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

/**
 * What the clean-up preview listed is checked again when the user confirms: a chapter read state,
 * a library change or a new entry since the preview keeps the file, and every entry of a shared
 * folder is locked while its files go.
 */
class DownloadCleanupRecheckTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private fun kit() = DownloadKit(temp).also { kits += it }

    @AfterEach
    fun waitForSaves() = kits.forEach { it.awaitBackground() }

    /** An entry whose library state the test changes. */
    private fun entry(id: Long, title: String, favorite: () -> Boolean): Manga = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { source } returns 1L
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } answers { favorite() }
    }

    @Test
    fun `a chapter marked unread since the preview is kept`() = runBlocking<Unit> {
        val kit = kit()
        val manga = kit.manga(1, "Entry")
        var chapters = listOf(kit.chapter(1, 1, read = true))
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(true, false, { listOf(manga) }, { chapters })
        plan.others.map { it.file } shouldBe listOf(file)

        chapters = listOf(kit.chapter(1, 1, read = false))
        val result = kit.manager.applyCleanupResult(plan)

        file.exists() shouldBe true
        result.deleted shouldBe 0
        result.skipped shouldBe 1
    }

    @Test
    fun `an entry added back to the library since the preview keeps its chapters`() = runBlocking<Unit> {
        val kit = kit()
        var favorite = false
        val manga = entry(1, "Entry") { favorite }
        val chapters = listOf(kit.chapter(1, 1, read = false))
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(false, true, { listOf(manga) }, { chapters })
        plan.others.single().reason shouldBe CleanupReason.NOT_IN_LIBRARY

        favorite = true
        kit.manager.applyCleanup(plan) shouldBe 0

        file.exists() shouldBe true
    }

    @Test
    fun `a ticked orphaned folder that has an entry now is kept`() = runBlocking<Unit> {
        val kit = kit()
        val owner = kit.manga(1, "Entry")
        val folder = kit.write("A (EN)/Nobody/Chapter 1.cbz").parentFile
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        var mangas = listOf(owner)
        val plan = kit.manager.planCleanup(true, true, { mangas }, { emptyList<Chapter>() })
        plan.orphans.map { it.file } shouldBe listOf(folder)

        mangas = listOf(owner, kit.manga(2, "Nobody"))
        val result = kit.manager.applyCleanupResult(plan, orphansToDelete = setOf(folder))

        folder.isDirectory shouldBe true
        result.skipped shouldBe 1
    }

    @Test
    fun `a shared folder waits for every owner's lock, not only the first`() = runBlocking<Unit> {
        val kit = kit()
        val one = kit.manga(1, "Same")
        val two = kit.manga(2, "Same.")
        val chapters = mapOf(1L to listOf(kit.chapter(1, 1, read = true)), 2L to listOf(kit.chapter(2, 2)))
        val file = kit.write("A (EN)/Same/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(true, false, { listOf(one, two) }, { chapters[it].orEmpty() })
        plan.others.map { it.file } shouldBe listOf(file)

        // Entry 2's download is writing into the shared folder; its lock says when the clean-up asks for it
        val lock = Mutex()
        val asked = CompletableDeferred<Unit>()
        kit.manager.entryLocks[2] = object : Mutex by lock {
            override suspend fun lock(owner: Any?) {
                asked.complete(Unit)
                lock.lock(owner)
            }
        }
        lock.lock()
        val applying = async(Dispatchers.IO) { kit.manager.applyCleanup(plan) }
        // The owners' locks are taken in id order: holding entry 1's, the clean-up asks for entry 2's,
        // which this test holds, so nothing can be deleted yet
        withTimeout(10_000) { asked.await() }
        kit.manager.entryLock(1).isLocked shouldBe true
        applying.isCompleted shouldBe false
        file.exists() shouldBe true
        lock.unlock()

        applying.await() shouldBe 2 // the chapter, then the emptied folder
        file.exists() shouldBe false
    }
}

/** A clean-up that runs while the library update or the user queues a chapter must leave that download's files alone. */
class DownloadCleanupRaceTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource(override val name: String, override val id: Long) : HttpSource() {
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    private val sourceA = FakeSource("A", 1)

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    // A real Manga reads its custom title through Injekt, which these tests do not start
    private fun manga(id: Long, source: Long, title: String, favorite: Boolean = true) = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.source } returns source
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } returns favorite
    }

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, url = "/c$id", name = "Chapter $id", chapterNumber = number, sourceOrder = 1000 - id)

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun waitForSaves() = runBlocking {
        withTimeout(10_000) { managers.forEach { it.awaitBackgroundWork() } }
    }

    private fun manager(): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(sourceA.id) } returns sourceA
            every { getOrStub(sourceA.id) } returns sourceA
            every { getOnlineSources() } returns listOf(sourceA)
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(-1)
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(false)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
    }

    @Test
    fun `a chapter queued while clean up runs keeps its unfinished download and the entry keeps its chapters`() = runBlocking<Unit> {
        val manager = manager()
        val downloads = File(temp, "downloads/A (EN)")
        val one = manga(1, sourceA.id, "One")
        val two = manga(2, sourceA.id, "Two")
        // Each entry: one finished chapter and one unfinished download (not downloaded yet, so enqueue takes it)
        val chapters = mapOf(1L to listOf(chapter(1, 1, 1.0), chapter(4, 1, 2.0)), 2L to listOf(chapter(2, 2, 1.0), chapter(3, 2, 2.0)))
        val finishedOne = Random(6).nextBytes(2048)
        val finished = Random(7).nextBytes(2048)
        File(downloads, "One/Chapter 1.cbz").apply { parentFile.mkdirs() }.writeBytes(finishedOne)
        File(downloads, "Two/Chapter 2.cbz").apply { parentFile.mkdirs() }.writeBytes(finished)
        File(downloads, "One/Chapter 4_tmp").mkdirs()
        File(downloads, "One/Chapter 4_tmp/001.png").writeBytes(byteArrayOf(1, 2, 3))
        File(downloads, "Two/Chapter 3_tmp").mkdirs()
        File(downloads, "Two/Chapter 3_tmp/001.png").writeBytes(byteArrayOf(1, 2, 3))

        // The folder walk's first chaptersOf call waits until the test has queued a chapter of the other entry
        val firstEntry = CompletableDeferred<Long>()
        val queued = CompletableDeferred<Unit>()
        val run = async(Dispatchers.IO) {
            manager.cleanup(removeRead = false, removeNonFavorite = false, allManga = { listOf(one, two) }) { id ->
                if (firstEntry.complete(id)) queued.await()
                chapters.getValue(id)
            }
        }
        val first = withTimeout(10_000) { firstEntry.await() }
        val other = if (first == 1L) two else one
        val unfinished = chapters.getValue(other.id).last() // chapter 4 of One or chapter 3 of Two
        manager.enqueue(other, listOf(unfinished), start = false)
        manager.queue.value.map { it.chapter.id } shouldBe listOf(unfinished.id)
        queued.complete(Unit)
        run.await()

        // The entry queued during the walk is left alone: its unfinished download survives
        val otherTmp = if (other.id == 2L) "Two/Chapter 3_tmp/001.png" else "One/Chapter 4_tmp/001.png"
        File(downloads, otherTmp).readBytes() shouldBe byteArrayOf(1, 2, 3)
        // Finished chapters are never touched by a clean-up with both options off
        File(downloads, "One/Chapter 1.cbz").readBytes() shouldBe finishedOne
        File(downloads, "Two/Chapter 2.cbz").readBytes() shouldBe finished
    }

    private fun raceManager(
        coOwners: suspend (Manga) -> List<Pair<Manga, List<Chapter>>>,
        newDownloader: (
            pageList: suspend () -> List<Page>,
            imageUrl: suspend (Page) -> String,
            image: suspend (Page) -> ByteArray,
            parallelPages: Int,
        ) -> ChapterDownloader,
    ): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(sourceA.id) } returns sourceA
            every { getOrStub(sourceA.id) } returns sourceA
            every { getOnlineSources() } returns listOf(sourceA)
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(true)
            every { parallelSourceLimit() } returns pref(5)
            every { parallelPageLimit() } returns pref(5)
            every { splitTallImages() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(-1)
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(false)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(
            ZoukanDirs(temp),
            paths,
            sourceManager,
            downloadPreferences,
            password,
            mockk(),
            mockk(),
            mockk(relaxed = true),
            coOwners = coOwners,
            newDownloader = newDownloader,
        ).also { managers += it }
    }

    private val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `delete waits for an earlier paused download even after a second pause overwrote stopping`() = runBlocking<Unit> {
        val one = manga(1, sourceA.id, "One")
        val ch1 = chapter(1, 1, 1.0)
        val writeEntered = CompletableDeferred<Unit>()
        val writeLatch = CountDownLatch(1)
        val coOwnersEntered = CompletableDeferred<Unit>()

        val manager = raceManager(
            coOwners = {
                coOwnersEntered.complete(Unit)
                emptyList()
            },
            newDownloader = { _, _, _, parallelPages ->
                ChapterDownloader(
                    pageList = { listOf(Page(0, imageUrl = "http://127.0.0.1:9/0.png")) },
                    imageUrl = { it.imageUrl!! },
                    image = { pngHeader },
                    parallelPages = parallelPages,
                    writeEntry = { writer, name, bytes ->
                        writeEntered.complete(Unit)
                        writeLatch.await(15, TimeUnit.SECONDS)
                        writer.write(name, bytes)
                    },
                )
            },
        )

        try {
            manager.enqueue(one, listOf(ch1), start = true)
            withTimeout(10_000) { writeEntered.await() }
            val job1 = manager.activeJob()!!

            // job1 is paused while blocked in writeEntry; start() launches job2 waiting on job1;
            // pause() cancels job2 while job1 is still blocked
            manager.pause()
            manager.start()
            manager.pause()

            val deleting = async(Dispatchers.IO) { manager.delete(one, listOf(ch1)) }
            Await.untilSuspending("delete to wait for the paused download") {
                manager.isWaitingFor(deleting, job1) || coOwnersEntered.isCompleted || deleting.isCompleted
            }
            coOwnersEntered.isCompleted shouldBe false
            deleting.isCompleted shouldBe false

            writeLatch.countDown()
            withTimeout(10_000) { deleting.await() }
            coOwnersEntered.isCompleted shouldBe true
            withTimeout(10_000) { manager.awaitBackgroundWork() }
            manager.stoppingCount() shouldBe 0
        } finally {
            writeLatch.countDown()
        }
    }

    @Test
    fun `deleteManga waits for an earlier paused download even after a second pause overwrote stopping`() = runBlocking<Unit> {
        val one = manga(1, sourceA.id, "One")
        val ch1 = chapter(1, 1, 1.0)
        val writeEntered = CompletableDeferred<Unit>()
        val writeLatch = CountDownLatch(1)
        val coOwnersEntered = CompletableDeferred<Unit>()

        val manager = raceManager(
            coOwners = {
                coOwnersEntered.complete(Unit)
                emptyList()
            },
            newDownloader = { _, _, _, parallelPages ->
                ChapterDownloader(
                    pageList = { listOf(Page(0, imageUrl = "http://127.0.0.1:9/0.png")) },
                    imageUrl = { it.imageUrl!! },
                    image = { pngHeader },
                    parallelPages = parallelPages,
                    writeEntry = { writer, name, bytes ->
                        writeEntered.complete(Unit)
                        writeLatch.await(15, TimeUnit.SECONDS)
                        writer.write(name, bytes)
                    },
                )
            },
        )

        try {
            manager.enqueue(one, listOf(ch1), start = true)
            withTimeout(10_000) { writeEntered.await() }
            val job1 = manager.activeJob()!!

            manager.pause()
            manager.start()
            manager.pause()

            val deleting = async(Dispatchers.IO) { manager.deleteManga(one, listOf(ch1)) }
            Await.untilSuspending("deleteManga to wait for the paused download") {
                manager.isWaitingFor(deleting, job1) || coOwnersEntered.isCompleted || deleting.isCompleted
            }
            coOwnersEntered.isCompleted shouldBe false
            deleting.isCompleted shouldBe false

            writeLatch.countDown()
            withTimeout(10_000) { deleting.await() }
            coOwnersEntered.isCompleted shouldBe true
            withTimeout(10_000) { manager.awaitBackgroundWork() }
            manager.stoppingCount() shouldBe 0
        } finally {
            writeLatch.countDown()
        }
    }

    @Test
    fun `start waits for all earlier paused downloads after a second pause`() = runBlocking<Unit> {
        val one = manga(1, sourceA.id, "One")
        val two = manga(2, sourceA.id, "Two")
        val ch1 = chapter(1, 1, 1.0)
        val ch2 = chapter(2, 2, 1.0)
        val writeEntered = CompletableDeferred<Unit>()
        val writeLatch = CountDownLatch(1)
        val secondStarted = CompletableDeferred<Unit>()
        var downloadCount = 0

        val manager = raceManager(
            coOwners = { emptyList() },
            newDownloader = { _, _, _, parallelPages ->
                val index = ++downloadCount
                ChapterDownloader(
                    pageList = {
                        if (index > 1) secondStarted.complete(Unit)
                        listOf(Page(0, imageUrl = "http://127.0.0.1:9/0.png"))
                    },
                    imageUrl = { it.imageUrl!! },
                    image = { pngHeader },
                    parallelPages = parallelPages,
                    writeEntry = { writer, name, bytes ->
                        if (index == 1) {
                            writeEntered.complete(Unit)
                            writeLatch.await(15, TimeUnit.SECONDS)
                        }
                        writer.write(name, bytes)
                    },
                )
            },
        )

        try {
            manager.enqueue(one, listOf(ch1), start = true)
            withTimeout(10_000) { writeEntered.await() }
            val job1 = manager.activeJob()!!

            manager.pause()
            manager.cancel(setOf(ch1.id))
            manager.enqueue(two, listOf(ch2), start = true)
            manager.pause()
            manager.start()

            manager.activeJob()?.let { job3 ->
                Await.untilSuspending("start to wait for the paused download") {
                    manager.isWaitingFor(job3, job1) || secondStarted.isCompleted || job3.isCompleted
                }
            }
            secondStarted.isCompleted shouldBe false

            writeLatch.countDown()
            withTimeout(10_000) { secondStarted.await() }
            withTimeout(10_000) { manager.awaitIdle() }
            withTimeout(10_000) { manager.awaitBackgroundWork() }
            manager.stoppingCount() shouldBe 0
        } finally {
            writeLatch.countDown()
        }
    }
}
