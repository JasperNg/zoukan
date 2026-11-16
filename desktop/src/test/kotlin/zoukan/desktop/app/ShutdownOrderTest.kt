package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.ChapterUpdate
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.reader.ProgressWriter
import java.util.concurrent.atomic.AtomicBoolean

class ShutdownOrderTest {

    @Test
    fun `quitting stops the background jobs and waits for a slow progress write`() = runBlocking<Unit> {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val written = AtomicBoolean(false)
        val writer = ProgressWriter(appScope) {
            delay(300)
            written.set(true)
        }
        writer.submit(ChapterUpdate(id = 1, lastPageRead = 5))
        writer.close()
        // The loop waits for the extensions, as before they load
        val jobs = BackgroundJobs(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk { every { isInitialized } returns MutableStateFlow(false) },
        )
        jobs.start(appScope)
        jobs.isRunning shouldBe true
        val updater = mockk<LibraryUpdater>(relaxed = true)
        val downloads = mockk<DownloadManager>(relaxed = true)

        AppShutdown.run(jobs, updater, downloads, appScope, databaseIdle = {})

        written.get() shouldBe true
        jobs.isRunning shouldBe false
        verify {
            updater.cancel()
            downloads.pause()
        }
        appScope.cancel()
    }
}
