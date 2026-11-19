package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.testing.Await
import java.io.File
import java.io.IOException

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
                kotlinx.coroutines.awaitCancellation()
            } finally {
                // Simulate in-flight file close that finishes after cancellation
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
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
            unfinished to DownloadManager.CleanupReason.UNFINISHED,
            readFile to DownloadManager.CleanupReason.READ,
            leftFile to DownloadManager.CleanupReason.NOT_IN_LIBRARY,
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
