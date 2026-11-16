package zoukan.desktop.library

import eu.kanade.tachiyomi.data.sync.SyncManager
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import zoukan.desktop.tray.Notifications
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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
            val end = System.currentTimeMillis() + 2_000
            while (canceller.isAlive && canceller.state != Thread.State.BLOCKED && System.currentTimeMillis() < end) Thread.sleep(5)
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
