package zoukan.desktop.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * All database work runs on one thread. A transaction started from the UI thread takes that
 * thread, and used to need the UI thread again before it could begin; a blocking read on the UI
 * thread at that moment (the journeys' checks do this) waited for the database thread forever.
 */
class BlockingReadDeadlockTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a blocking read on the thread that started a transaction does not deadlock`() {
        // As DesktopAppModule sets it up: one database thread for queries and transactions
        val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dispatcher, dispatcher)
        val ui = Executors.newSingleThreadExecutor { Thread(it, "ui").apply { isDaemon = true } }
        val uiScope = CoroutineScope(ui.asCoroutineDispatcher())
        try {
            val names = ui.submit<List<String>> {
                // A click handler starts a transaction and suspends while it takes the database thread
                uiScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    handler.await(inTransaction = true) { categoriesQueries.insert("From the UI", 1, 0, 0L) }
                }
                // Long enough for the database thread to be taken
                Thread.sleep(300)
                // Then the UI thread blocks on a read, which runs once the transaction is done
                runBlocking { handler.awaitList { categoriesQueries.getCategories() }.map { it.name } }
            }
            val result = try {
                names.get(20, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                null
            }
            (result != null) shouldBe true
            result!!.contains("From the UI") shouldBe true
        } finally {
            driver.close()
            ui.shutdownNow()
            executor.shutdownNow()
        }
    }
}
