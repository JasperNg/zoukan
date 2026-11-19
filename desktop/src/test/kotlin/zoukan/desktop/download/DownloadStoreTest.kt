package zoukan.desktop.download

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.testing.Await
import zoukan.desktop.tray.Notifications
import java.io.File
import java.io.IOException

/** Unit tests for [DownloadStore]: atomic queue persistence, unreadable recovery, and held-item retry. */
class DownloadStoreTest {

    @TempDir
    lateinit var temp: File

    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(
        queue: MutableStateFlow<List<Download>> = MutableStateFlow(emptyList()),
        getManga: GetManga = mockk(relaxed = true),
        getChapter: GetChapter = mockk(relaxed = true),
        canDownload: (Manga) -> Boolean = { true },
        onRestored: () -> Unit = {},
        repositories: (() -> Pair<MangaRepository, ChapterRepository>?) = { null },
    ): DownloadStore {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DownloadStore(
            dirs = ZoukanDirs(temp),
            scope = scope,
            getManga = getManga,
            getChapter = getChapter,
            canDownload = canDownload,
            queueLock = Any(),
            queue = queue,
            onRestored = onRestored,
            restoreRepositoriesProvider = repositories,
        )
    }

    private fun manga(id: Long) = Manga.create().copy(id = id, source = 1L, ogTitle = "Manga $id")

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, url = "/c$id", name = "Chapter $id")

    @Test
    fun `saveNow writes queue atomically and leaves no temp file`() = runBlocking<Unit> {
        val m = manga(1)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val queue = MutableStateFlow(listOf(Download(m, c1), Download(m, c2)))
        val s = store(queue = queue)

        s.saveNow()

        s.queueFile.exists() shouldBe true
        File(s.queueFile.path + ".tmp").exists() shouldBe false
        s.peekSavedQueue() shouldContainExactly listOf(
            DownloadStore.SavedDownload(1L, 10L),
            DownloadStore.SavedDownload(1L, 20L),
        )
    }

    @Test
    fun `saveNow before restore preserves pre-existing saved entries alongside new queued items`() = runBlocking<Unit> {
        val m = manga(1)
        val existing = chapter(10, 1)
        val added = chapter(20, 1)
        val queue = MutableStateFlow(listOf(Download(m, added)))
        val s = store(queue = queue)
        s.queueFile.writeText("""[{"mangaId":1,"chapterId":10},{"mangaId":1,"chapterId":20}]""")

        s.saveNow()

        s.peekSavedQueue() shouldContainExactly listOf(
            DownloadStore.SavedDownload(1L, 20L),
            DownloadStore.SavedDownload(1L, 10L),
        )
        // Reorder check: added is now first, existing is preserved once without duplication
        queue.value.map { it.chapter.id } shouldContainExactly listOf(added.id)
        existing.id shouldBe 10L
    }

    @Test
    fun `restore quarantines unreadable queue file and posts warning without losing it on later save`() = runBlocking<Unit> {
        val m = manga(1)
        val c = chapter(11, 1)
        val queue = MutableStateFlow(listOf(Download(m, c)))
        val s = store(queue = queue)
        s.queueFile.writeText("{not valid json")

        val posted = coroutineScope {
            val messages = java.util.Collections.synchronizedList(mutableListOf<Notifications.Message>())
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(messages) }
            yield()
            try {
                s.restore(start = false)
            } finally {
                collector.cancelAndJoin()
            }
            messages.filter { it.kind == Notifications.Kind.WARNING }
        }

        posted shouldHaveSize 1
        s.queueFile.exists() shouldBe false
        val quarantined = temp.listFiles().orEmpty().single { it.name.startsWith("downloads-queue.unreadable-") }
        quarantined.name shouldStartWith "downloads-queue.unreadable-"
        quarantined.readText() shouldBe "{not valid json"

        // Saving the new queue does not touch the quarantined file
        s.saveNow()
        quarantined.readText() shouldBe "{not valid json"
        s.peekSavedQueue() shouldContainExactly listOf(DownloadStore.SavedDownload(1L, 11L))
    }

    @Test
    fun `restore drops missing chapters and un-downloadable sources while keeping order`() = runBlocking<Unit> {
        val m1 = manga(1)
        val m2 = manga(2)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val c3 = chapter(30, 2)
        val mangaRepo = mockk<MangaRepository> {
            coEvery { getMangaById(1) } returns m1
            coEvery { getMangaById(2) } returns m2
        }
        val chapterRepo = mockk<ChapterRepository> {
            coEvery { getChapterById(10) } returns c1
            coEvery { getChapterById(20) } returns c2
            coEvery { getChapterById(30) } returns c3
            coEvery { getChapterById(99) } returns null
        }
        var restoredCalls = 0
        val queue = MutableStateFlow<List<Download>>(emptyList())
        val s = store(
            queue = queue,
            canDownload = { it.id == 1L },
            onRestored = { restoredCalls++ },
            repositories = { mangaRepo to chapterRepo },
        )
        s.queueFile.writeText(
            """[{"mangaId":1,"chapterId":20},{"mangaId":1,"chapterId":99},{"mangaId":2,"chapterId":30},{"mangaId":1,"chapterId":10}]""",
        )

        s.restore(start = true)

        queue.value.map { it.chapter.id } shouldContainExactly listOf(20L, 10L)
        restoredCalls shouldBe 1
    }

    @Test
    fun `failed db read holds unread downloads until retryHeldIfNeeded succeeds and cancelHeld removes cancelled ones`() = runBlocking<Unit> {
        val m1 = manga(1)
        val c1 = chapter(10, 1)
        val c2 = chapter(20, 1)
        val c3 = chapter(30, 1)
        var failSecond = true
        val mangaRepo = mockk<MangaRepository> {
            coEvery { getMangaById(1) } returns m1
        }
        val chapterRepo = mockk<ChapterRepository> {
            coEvery { getChapterById(10) } returns c1
            coEvery { getChapterById(20) } answers {
                if (failSecond) throw IOException("db locked") else c2
            }
            coEvery { getChapterById(30) } returns c3
        }
        var startTriggered = 0
        val queue = MutableStateFlow<List<Download>>(emptyList())
        val s = store(
            queue = queue,
            onRestored = { startTriggered++ },
            repositories = { mangaRepo to chapterRepo },
        )
        s.queueFile.writeText("""[{"mangaId":1,"chapterId":10},{"mangaId":1,"chapterId":20},{"mangaId":1,"chapterId":30}]""")

        s.restore(start = true)

        queue.value.map { it.chapter.id } shouldContainExactly listOf(10L)
        s.heldChapterIds() shouldContainExactly listOf(20L, 30L)
        startTriggered shouldBe 1

        // Cancelling chapter 30 while held leaves only chapter 20 to be retried
        s.cancelHeld(setOf(30L)) shouldBe true
        s.cancelHeld(setOf(30L)) shouldBe false
        s.heldChapterIds() shouldContainExactly listOf(20L)

        // Heal DB and retry held items
        failSecond = false
        s.retryHeldIfNeeded()
        withTimeout(5_000) {
            Await.untilSuspending("held chapter to be restored") { queue.value.map { it.chapter.id } == listOf(10L, 20L) }
        }
        s.heldChapterIds() shouldBe emptyList()
        startTriggered shouldBe 2
    }

    @Test
    fun `peekSavedQueue returns null on corrupt file without moving it`() {
        val s = store()
        s.queueFile.writeText("corrupt")

        s.peekSavedQueue().shouldBeNull()
        s.queueFile.exists() shouldBe true
    }
}
