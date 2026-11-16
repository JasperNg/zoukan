package zoukan.desktop.domain

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Two refreshes of one entry at once, and the transaction around the chapter writes. */
class SyncChaptersConcurrencyTest {

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { title } returns "Manga"
        every { ogTitle } returns "Manga"
    }
    private val source = mockk<Source>(relaxed = true)

    private fun sChapter(url: String, name: String) = SChapter.create().apply {
        this.url = url
        this.name = name
        date_upload = 1_700_000_000_000L
    }

    private class Fixture {
        val rows: MutableList<Chapter> = Collections.synchronizedList(mutableListOf())
        val reads = AtomicInteger()
        val secondRead = CompletableDeferred<Unit>()

        @Volatile var inTransaction = false
        val insideTransaction = Collections.synchronizedList(mutableListOf<Boolean>())
    }

    private fun sync(f: Fixture, waitForSecondRead: Boolean): SyncChapters {
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            coEvery { removeChaptersWithIds(any()) } answers { f.insideTransaction += f.inTransaction }
            coEvery { updateAll(any()) } answers { f.insideTransaction += f.inTransaction }
            val added = slot<List<Chapter>>()
            coEvery { addAll(capture(added)) } coAnswers {
                f.insideTransaction += f.inTransaction
                if (waitForSecondRead) withTimeoutOrNull(500) { f.secondRead.await() }
                val saved = added.captured.map { it.copy(id = 100L + f.rows.size + added.captured.indexOf(it)) }
                f.rows += saved
                saved
            }
        }
        val getChapters = mockk<GetChaptersByMangaId> {
            coEvery { await(1L, any()) } coAnswers {
                val copy = synchronized(f.rows) { f.rows.toList() }
                if (f.reads.incrementAndGet() >= 2) f.secondRead.complete(Unit)
                copy
            }
        }
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
            every { markDuplicateReadChapterAsRead().get() } returns emptySet()
        }
        val getExcluded = mockk<GetExcludedScanlators> { coEvery { await(1L) } returns emptySet() }
        val handler = object : DatabaseHandler by mockk() {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (!inTransaction) return block(mockk(relaxed = true))
                f.inTransaction = true
                try {
                    return block(mockk(relaxed = true))
                } finally {
                    f.inTransaction = false
                }
            }
        }
        return SyncChapters(
            chapterRepository,
            mockk(relaxed = true),
            ShouldUpdateDbChapter(),
            getChapters,
            mockk(relaxed = true),
            libraryPreferences,
            mockk<DownloadManager>(relaxed = true),
            mockk<DownloadPaths>(relaxed = true),
            getExcluded,
            handler,
        )
    }

    @Test
    fun `two syncs of the same entry at once add each chapter once`() = runBlocking<Unit> {
        val f = Fixture()
        val sync = sync(f, waitForSecondRead = true)
        val list = listOf(sChapter("/1", "Chapter 1"), sChapter("/2", "Chapter 2"))

        listOf(1, 2).map { async(Dispatchers.Default) { sync.await(list, manga, source) } }.awaitAll()

        f.rows.groupBy { it.url }.values.forEach { it.size shouldBe 1 }
        f.rows.size shouldBe 2
    }

    @Test
    fun `the chapter writes of one sync run in one transaction`() = runBlocking<Unit> {
        val f = Fixture()
        val old = Chapter.create().copy(id = 5, mangaId = 1, url = "/old", name = "Old", sourceOrder = 0, dateUpload = 1_700_000_000_000L, chapterNumber = 9.0)
        val kept = Chapter.create().copy(id = 6, mangaId = 1, url = "/1", name = "Ch. 1", sourceOrder = 1, dateUpload = 1_700_000_000_000L, chapterNumber = 1.0)
        f.rows += listOf(old, kept)

        sync(f, waitForSecondRead = false)
            .await(listOf(sChapter("/1", "Chapter 1"), sChapter("/2", "Chapter 2")), manga, source)

        // removeChaptersWithIds, addAll and updateAll all ran
        f.insideTransaction.size shouldBe 3
        f.insideTransaction.all { it } shouldBe true
    }
}
