package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

/** Android's download queue actions (sort, move, cancel a series) and SY's download clean-up. */
class DownloadQueueOrderTest {

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
    private val sourceB = FakeSource("B", 2)

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    // A real Manga reads its custom title through Injekt, which these tests do not start
    private fun manga(id: Long, source: Long, title: String, favorite: Boolean = true) = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.source } returns source
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } returns favorite
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, uploaded: Long = 0, read: Boolean = false) =
        Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            url = "/c$id",
            name = "Chapter $id",
            chapterNumber = number,
            dateUpload = uploaded,
            read = read,
            sourceOrder = 1000 - id,
        )

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun waitForSaves() = runBlocking {
        withTimeout(10_000) { managers.forEach { it.awaitBackgroundWork() } }
    }

    private fun manager(): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            listOf(sourceA, sourceB).forEach { s ->
                every { get(s.id) } returns s
                every { getOrStub(s.id) } returns s
            }
            every { getOnlineSources() } returns listOf(sourceA, sourceB)
        }
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
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

    private fun DownloadManager.ids() = queue.value.map { it.chapter.id }

    @Test
    fun `moves downloads and series to the top and bottom, and cancels a series`() {
        val manager = manager()
        val one = manga(1, sourceA.id, "One")
        val two = manga(2, sourceA.id, "Two")
        manager.enqueue(one, listOf(chapter(1, 1, 1.0), chapter(2, 1, 2.0)), start = false)
        manager.enqueue(two, listOf(chapter(3, 2, 1.0), chapter(4, 2, 2.0)), start = false)
        manager.ids() shouldBe listOf(1L, 2L, 3L, 4L)

        manager.moveToBottom(manager.queue.value.first())
        manager.ids() shouldBe listOf(2L, 3L, 4L, 1L)

        manager.moveSeries(2, toTop = true)
        manager.ids() shouldBe listOf(3L, 4L, 2L, 1L)

        manager.moveSeries(2, toTop = false)
        manager.ids() shouldBe listOf(2L, 1L, 3L, 4L)

        manager.cancelSeries(1)
        manager.ids() shouldBe listOf(3L, 4L)
    }

    @Test
    fun `sorts within each source, keeping the sources in order`() {
        val manager = manager()
        val a = manga(1, sourceA.id, "One")
        val b = manga(2, sourceB.id, "Two")
        manager.enqueue(b, listOf(chapter(10, 2, 1.0, uploaded = 300), chapter(11, 2, 2.0, uploaded = 100)), start = false)
        manager.enqueue(a, listOf(chapter(1, 1, 3.0, uploaded = 200), chapter(2, 1, 1.0, uploaded = 400)), start = false)
        manager.ids() shouldBe listOf(10L, 11L, 1L, 2L)

        manager.sortQueue(descending = true) { it.chapter.dateUpload }
        manager.ids() shouldBe listOf(10L, 11L, 2L, 1L)

        manager.sortQueue(descending = false) { it.chapter.dateUpload }
        manager.ids() shouldBe listOf(11L, 10L, 1L, 2L)

        manager.sortQueue(descending = false) { it.chapter.chapterNumber }
        manager.ids() shouldBe listOf(10L, 11L, 2L, 1L)
    }

    @Test
    fun `clean up deletes orphaned, unfinished, read and non-library downloads`() = runBlocking<Unit> {
        val manager = manager()
        val downloads = File(temp, "downloads/A (EN)")
        fun make(path: String) = File(downloads, path).apply {
            parentFile.mkdirs()
            if (path.endsWith("/")) mkdirs() else writeText("x")
        }
        val kept = manga(1, sourceA.id, "Kept")
        val left = manga(2, sourceA.id, "Left library", favorite = false)
        val allRead = manga(3, sourceA.id, "All read")
        val chapters = mapOf(
            1L to listOf(chapter(1, 1, 1.0, read = true), chapter(2, 1, 2.0)),
            2L to listOf(chapter(3, 2, 1.0)),
            3L to listOf(chapter(4, 3, 1.0, read = true)),
        )
        make("Kept/Chapter 1.cbz")
        make("Kept/Chapter 2.cbz")
        make("Kept/Chapter 9.cbz") // no such chapter any more
        make("Kept/Chapter 2_tmp/") // an unfinished download
        make("Kept/cover.jpg")
        make("Left library/Chapter 3.cbz")
        make("All read/Chapter 4.cbz")
        make("Nobody/Chapter 1.cbz")

        manager.isDownloaded(kept, chapters.getValue(1L)[0]) shouldBe true

        val cleared = manager.cleanup(
            removeRead = true,
            removeNonFavorite = true,
            allManga = { listOf(kept, left, allRead) },
            chaptersOf = { chapters.getValue(it) },
        )

        downloads.list()!!.toSet() shouldBe setOf("Kept")
        File(downloads, "Kept").list()!!.toSet() shouldBe setOf("Chapter 2.cbz", "cover.jpg")
        // Nobody: folder + 1 file; Left library: folder + 1 file; All read: chapter + folder;
        // Kept: Chapter 9, the unfinished one and Chapter 1
        cleared shouldBe 2 + 2 + 2 + 3
        manager.isDownloaded(kept, chapters.getValue(1L)[0]) shouldBe false
        manager.isDownloaded(kept, chapters.getValue(1L)[1]) shouldBe true
    }

    @Test
    fun `clean up keeps read chapters unless asked, and leaves entries in the queue alone`() = runBlocking<Unit> {
        val manager = manager()
        val downloads = File(temp, "downloads/A (EN)")
        val read = manga(1, sourceA.id, "Read")
        val queued = manga(2, sourceA.id, "Queued")
        File(downloads, "Read").mkdirs()
        File(downloads, "Read/Chapter 1.cbz").writeText("x")
        File(downloads, "Queued/Chapter 3_tmp").mkdirs()
        val chapters = mapOf(1L to listOf(chapter(1, 1, 1.0, read = true)), 2L to listOf(chapter(3, 2, 1.0)))
        manager.enqueue(queued, chapters.getValue(2L), start = false)

        val cleared = manager.cleanup(
            removeRead = false,
            removeNonFavorite = false,
            allManga = { listOf(read, queued) },
            chaptersOf = { chapters.getValue(it) },
        )

        cleared shouldBe 0
        File(downloads, "Read/Chapter 1.cbz").exists() shouldBe true
        File(downloads, "Queued/Chapter 3_tmp").exists() shouldBe true
    }
}
