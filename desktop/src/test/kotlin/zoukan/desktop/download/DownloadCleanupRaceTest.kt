package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import zoukan.desktop.testing.Await
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val finishedOne = kotlin.random.Random(6).nextBytes(2048)
        val finished = kotlin.random.Random(7).nextBytes(2048)
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
