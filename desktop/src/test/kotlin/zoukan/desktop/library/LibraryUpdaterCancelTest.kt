package zoukan.desktop.library

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
            every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true)
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
}
