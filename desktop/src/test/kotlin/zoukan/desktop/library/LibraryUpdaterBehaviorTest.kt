package zoukan.desktop.library

import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testing.Await
import zoukan.desktop.tray.Notifications
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LibraryUpdaterTest {

    private val updater = LibraryUpdater(
        dirs = mockk(),
        getLibraryManga = mockk(),
        refreshManga = mockk(),
        sourceManager = mockk(),
        preferences = mockk<LibraryPreferences> {
            every { updateCategories() } returns set(emptySet())
            every { updateCategoriesExclude() } returns set(emptySet())
            // The Android defaults
            every { autoUpdateMangaRestrictions() } returns set(
                setOf(
                    LibraryPreferences.MANGA_HAS_UNREAD,
                    LibraryPreferences.MANGA_NON_COMPLETED,
                    LibraryPreferences.MANGA_NON_READ,
                    LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
                ),
            )
        },
        fetchInterval = FetchInterval(mockk()),
        filterChaptersForDownload = mockk(),
        downloadManager = mockk(),
        errors = mockk(relaxed = true),
        getManga = mockk(),
        getMergedMangaForDownloading = mockk(),
        getTracks = mockk(),
        insertTrack = mockk(),
        trackerManager = mockk(),
        syncPreferences = mockk(),
        syncManager = { mockk() },
    )

    private fun set(value: Set<String>) = mockk<Preference<Set<String>>> { every { get() } returns value }

    private fun entry(id: Long, total: Long = 10, read: Long = 0, status: Int = SManga.ONGOING, categories: List<Long> = emptyList()) =
        LibraryManga(
            // Manga.create() reads custom titles through Injekt, so a mock
            manga = mockk<Manga> {
                every { this@mockk.id } returns id
                every { title } returns "M$id"
                every { this@mockk.status } returns status.toLong()
                every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
                every { nextUpdate } returns 0L
                every { dateAdded } returns id
            },
            categories = categories,
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

    @Test
    fun `the Android default skip rules`() {
        val library = listOf(
            entry(1, total = 10, read = 10),
            entry(2, total = 10, read = 3),
            entry(3, total = 10, read = 0),
            entry(4, total = 10, read = 10, status = SManga.COMPLETED),
            entry(5, total = 0),
        )
        val (selected, skipped) = updater.select(library, null)
        selected.map { it.id }.sorted() shouldContainExactly listOf(1L, 5L)
        skipped.map { it.second } shouldContainExactly listOf("Has unread chapters", "Has unread chapters", "Completed")
    }

    @Test
    fun `skip rules off and one category`() {
        val library = listOf(entry(1, read = 0, categories = listOf(7)), entry(2, read = 0), entry(3, read = 10, categories = listOf(8)))
        updater.select(library, null, ignoreRestrictions = true).first.map { it.id }.sorted() shouldContainExactly listOf(1L, 2L, 3L)
        updater.select(library, 7L, ignoreRestrictions = true).first.map { it.id } shouldContainExactly listOf(1L)
        updater.select(library, 0L, ignoreRestrictions = true).first.map { it.id } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a source, status or tracking tab updates its entries with the skip rules`() {
        val library = listOf(entry(1, read = 10, categories = listOf(7)), entry(2, read = 3), entry(3, read = 10))
        val (selected, skipped) = updater.select(library, null, groupIds = setOf(1L, 2L))
        selected.map { it.id } shouldContainExactly listOf(1L)
        skipped.map { it.second } shouldContainExactly listOf("Has unread chapters")
    }
}

class LibraryUpdaterCancelTest {

    @Test
    fun `cancelling an update is not reported as a failure`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val getLibraryManga = mockk<GetLibraryManga> {
            coEvery { await() } coAnswers {
                started.countDown()
                awaitCancellation()
            }
        }
        val preferences = mockk<LibraryPreferences> {
            every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) { every { get() } returns 0L }
        }
        val updater = LibraryUpdater(
            dirs = mockk(),
            getLibraryManga = getLibraryManga,
            refreshManga = mockk(),
            sourceManager = mockk(),
            preferences = preferences,
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = mockk(relaxed = true),
            getManga = mockk(),
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk(),
            // No sync service, so a manual update does not sync first
            syncPreferences = mockk { every { syncService() } returns mockk<Preference<Int>> { every { get() } returns 0 } },
            syncManager = { mockk() },
        )
        val posted = mutableListOf<Notifications.Message>()
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(posted) }
        yield()

        updater.start() shouldBe true
        started.await(10, TimeUnit.SECONDS) shouldBe true
        updater.cancel()
        withTimeout(10_000) { updater.join() }

        updater.isRunning shouldBe false
        updater.progress.value shouldBe null
        collector.cancelAndJoin()
        posted.filter { it.kind == Notifications.Kind.ERROR }.shouldBeEmpty()
    }

    @Test
    fun `a cancel right after a start from another thread stops the update`() = runBlocking<Unit> {
        val inStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        // The update only ends by a cancel
        val getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } coAnswers { awaitCancellation() } }
        val preferences = mockk<LibraryPreferences> {
            every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) { every { get() } returns 0L }
        }
        val sync = mockk<SyncManager>(relaxed = true) { every { running } returns MutableStateFlow(false) }
        val updater = LibraryUpdater(
            dirs = mockk(),
            getLibraryManga = getLibraryManga,
            refreshManga = mockk(),
            sourceManager = mockk(),
            preferences = preferences,
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = mockk(relaxed = true),
            getManga = mockk(),
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk(),
            // SyncYomi, a supported service: a manual start asks the sync manager while it holds its lock
            syncPreferences = mockk { every { syncService() } returns mockk<Preference<Int>> { every { get() } returns 1 } },
            syncManager = {
                // Holds the first start inside its lock until the cancel has been sent
                if (calls.getAndIncrement() == 0) {
                    inStart.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                sync
            },
        )
        try {
            val starter = thread { updater.start() }
            inStart.await(10, TimeUnit.SECONDS) shouldBe true
            val canceller = thread { updater.cancel() }
            // The cancel either finishes at once or waits for the start's lock
            Await.until("the cancel to finish or wait for the start's lock", 2_000, { canceller.state }) {
                !canceller.isAlive || canceller.state == Thread.State.BLOCKED
            }
            release.countDown()
            starter.join(10_000)
            canceller.join(10_000)

            withTimeout(5_000) { updater.join() }
            updater.isRunning shouldBe false
        } finally {
            release.countDown()
            updater.cancel()
        }
    }
}

/**
 * The library update leaves out the sources Android leaves out, and queues a merged entry's new
 * chapters under the entries it merges, as Android's LibraryUpdateJob.
 */
class LibraryUpdaterDownloadsTest {

    @TempDir
    lateinit var temp: File

    private lateinit var excludedBefore: List<Long>

    @BeforeEach
    fun setUp() {
        excludedBefore = LIBRARY_UPDATE_EXCLUDED_SOURCES
        LIBRARY_UPDATE_EXCLUDED_SOURCES = listOf(EH)
    }

    @AfterEach
    fun tearDown() {
        LIBRARY_UPDATE_EXCLUDED_SOURCES = excludedBefore
    }

    private fun manga(id: Long, source: Long) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { title } returns "M$id"
        every { this@mockk.source } returns source
        every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
        every { favorite } returns true
    }

    private fun entry(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 1,
        readCount = 1,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, sourceOrder = id)

    @Test
    fun `excluded sources are not updated and merged chapters are queued under their entries`() = runBlocking<Unit> {
        val merged = manga(1, MERGED_SOURCE_ID)
        val gallery = manga(2, EH)
        val childA = manga(10, 100)
        val childB = manga(11, 101)
        val library = listOf(entry(merged), entry(gallery))
        val newChapters = listOf(chapter(1, 10), chapter(2, 11), chapter(3, 10))

        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(merged, any(), any(), any(), any(), any()) } returns RefreshManga.Result(merged, newChapters)
        }
        val downloads = mockk<DownloadManager>(relaxed = true)
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(temp),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library },
            refreshManga = refreshManga,
            sourceManager = mockk<SourceManager> { every { this@mockk.get(any<Long>()) } returns mockk() },
            preferences = mockk<LibraryPreferences> {
                every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) { every { get() } returns 0L }
                every { autoUpdateMetadata() } returns mockk<Preference<Boolean>> { every { get() } returns false }
                every { newUpdatesCount() } returns mockk<Preference<Int>>(relaxed = true)
            },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk<FilterChaptersForDownload> { coEvery { await(any(), any()) } answers { secondArg() } },
            downloadManager = downloads,
            errors = mockk(relaxed = true),
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { library.first { it.id == firstArg<Long>() }.manga } },
            getMergedMangaForDownloading = mockk<GetMergedMangaForDownloading> { coEvery { await(1L) } returns listOf(childA, childB) },
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        val result = updater.run(mangaIds = setOf(1L, 2L))

        result.entries.map { it.id } shouldContainExactly listOf(1L)
        result.newChapters shouldBe 3
        coVerify(exactly = 0) { refreshManga.invoke(gallery, any(), any(), any(), any(), any()) }
        verify(exactly = 0) { downloads.enqueue(merged, any(), any()) }
        verify { downloads.enqueue(childA, match { list -> list.map { it.id }.sorted() == listOf(1L, 3L) }, false) }
        verify { downloads.enqueue(childB, match { list -> list.map { it.id } == listOf(2L) }, false) }
        // The downloads start once the update ends
        verify(exactly = 1) { downloads.start() }
    }

    private companion object {
        const val EH = 6900L
    }
}

/** The update writes each failure to the Android error tables and clears the entries that updated. */
class LibraryUpdaterErrorsTest {

    @TempDir
    lateinit var temp: File

    private fun entry(id: Long, source: Long) = LibraryManga(
        manga = mockk<Manga> {
            every { this@mockk.id } returns id
            every { title } returns "M$id"
            every { this@mockk.source } returns source
            every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
            every { favorite } returns true
        },
        categories = emptyList(),
        totalChapters = 1,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `failures are recorded, updated entries cleared, and a retry runs only the given entries`() = runBlocking<Unit> {
        val library = listOf(entry(1, 10), entry(2, 10), entry(3, 99), entry(4, 10))
        val errors = mockk<LibraryUpdateErrors>(relaxed = true)
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(match { it.id == 1L }, any(), any(), any(), any(), any()) } answers { RefreshManga.Result(firstArg(), emptyList()) }
            coEvery { this@mockk.invoke(match { it.id == 2L }, any(), any(), any(), any(), any()) } throws NoChaptersException()
            coEvery { this@mockk.invoke(match { it.id == 4L }, any(), any(), any(), any(), any()) } throws IllegalStateException("HTTP error 503")
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(10L) } returns mockk()
            every { get(99L) } returns null
        }
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(temp),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library },
            refreshManga = refreshManga,
            sourceManager = sourceManager,
            preferences = mockk<LibraryPreferences> {
                every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) { every { get() } returns 0L }
                every { autoUpdateMetadata() } returns mockk<Preference<Boolean>> { every { get() } returns false }
            },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = errors,
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { library.first { it.id == firstArg<Long>() }.manga } },
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        val result = updater.run(mangaIds = setOf(1L, 2L, 3L, 4L))

        result.updated shouldBe 4
        result.failures.map { it.manga.id to it.message }.sortedBy { it.first } shouldContainExactly listOf(
            2L to "No chapters found",
            3L to "Source not found",
            4L to "HTTP error 503",
        )
        coVerify(exactly = 1) { errors.clean() }
        coVerify(exactly = 1) { errors.clear(1L) }
        coVerify(exactly = 1) { errors.record(2L, "No chapters found") }
        coVerify(exactly = 1) { errors.record(3L, "Source not found") }
        coVerify(exactly = 1) { errors.record(4L, "HTTP error 503") }

        val retry = updater.run(mangaIds = setOf(1L))
        retry.updated shouldBe 1
        retry.skipped shouldBe emptyList()
        coVerify(exactly = 2) { errors.clear(1L) }
    }
}

/** An update a restore stops is not a finished update: the schedule must run it again. */
class LibraryUpdaterRestoreTest {

    @TempDir
    lateinit var temp: File

    private fun entry(id: Long) = LibraryManga(
        manga = mockk<Manga> {
            every { this@mockk.id } returns id
            every { title } returns "M$id"
            every { source } returns 10L
            every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
            every { favorite } returns true
        },
        categories = emptyList(),
        totalChapters = 1,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `an update stopped for a restore says so and keeps the previous update time`() = runBlocking<Unit> {
        val timestamp = mockk<Preference<Long>>(relaxed = true) { every { get() } returns 1234L }
        val refreshManga = mockk<RefreshManga>()
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(temp),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns listOf(entry(1), entry(2)) },
            refreshManga = refreshManga,
            sourceManager = mockk(relaxed = true),
            preferences = mockk<LibraryPreferences> { every { lastUpdatedTimestamp() } returns timestamp },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = mockk(relaxed = true),
            getManga = mockk(),
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        // A restore is waiting: the update stops before its first entry
        RestoreGate.pending.incrementAndGet()
        val result = try {
            updater.run(mangaIds = setOf(1L, 2L))
        } finally {
            RestoreGate.pending.decrementAndGet()
        }

        result.stoppedForRestore shouldBe true
        coVerify(exactly = 0) { refreshManga.invoke(any(), any(), any(), any(), any(), any()) }
        verify { timestamp.set(1234L) }
    }
}
