package zoukan.desktop.db

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * The one thread that runs the app's database work. SQLDelight's JDBC driver gives each thread its
 * own connection and begins transactions DEFERRED, so two coroutines writing at once (a backup
 * restore does) would fail with SQLITE_BUSY; on Android the driver serializes them, here all
 * database work runs on this thread instead (see DesktopAppModule). [DesktopSqliteDriver] keeps this
 * thread's connection open while it is busy.
 */
class DatabaseThread {
    @Volatile
    private var thread: Thread? = null

    val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, NAME).apply {
            isDaemon = true
            thread = this
        }
    }

    /** Whether the caller runs on this thread. */
    fun isCurrent(): Boolean = Thread.currentThread() === thread

    companion object {
        const val NAME = "zoukan-database"
    }
}
