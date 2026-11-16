package zoukan.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.BackgroundJobs
import zoukan.desktop.library.LibraryUpdater

/**
 * One ordered shutdown step, run before the process exits: stops the background work, then waits
 * for the writes already started (the reader's progress and history, queued saves, the database's
 * queue). Waiting is bounded: after [run]'s timeout it logs what was left and returns, so quitting
 * never hangs. Downloads are paused, not cancelled; they resume from their temporary files at the
 * next start.
 */
object AppShutdown {
    /**
     * [run] with the app's own services, on the calling thread (main, once the windows have
     * closed). A failure is logged and the exit goes on.
     */
    fun runForApp(appScope: CoroutineScope) {
        try {
            runBlocking {
                run(
                    Injekt.get(),
                    Injekt.get(),
                    Injekt.get(),
                    appScope,
                    databaseIdle = { Injekt.get<DatabaseHandler>().await { } },
                )
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "The shutdown step failed; quitting anyway" }
        }
    }

    /**
     * @param databaseIdle returns once every database write queued before the call has run
     * @param timeoutMs the longest the whole step may take
     */
    suspend fun run(
        backgroundJobs: BackgroundJobs,
        libraryUpdater: LibraryUpdater,
        downloads: DownloadManager,
        appScope: CoroutineScope,
        databaseIdle: suspend () -> Unit,
        timeoutMs: Long = 5_000,
    ) {
        var stage = "stopping background work"
        val finished = withTimeoutOrNull(timeoutMs) {
            backgroundJobs.stop()
            libraryUpdater.cancel()
            libraryUpdater.join()
            downloads.pause()
            downloads.awaitBackgroundWork()

            stage = "waiting for the app's background work"
            while (true) {
                val pending = appScope.coroutineContext.job.children.filter { it.isActive }.toList()
                if (pending.isEmpty()) break
                pending.forEach { it.join() }
            }

            stage = "waiting for the database"
            databaseIdle()
        }
        if (finished == null) {
            val pending = appScope.coroutineContext.job.children.count { it.isActive }
            logcat(LogPriority.WARN) { "Quitting did not wait any longer than ${timeoutMs}ms; stopped while $stage ($pending background tasks still running)" }
        }
    }
}
