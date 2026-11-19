package zoukan.desktop.testkit

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.fakes.FailingSqlDriver
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opens an encrypted temporary SQLite [Database] and [AndroidDatabaseHandler] using
 * [DesktopDatabase]'s production SQLCipher properties and column adapters, optionally wrapping
 * the underlying driver (for example with [FailingSqlDriver]).
 */
class TestDatabaseKit(
    folder: File,
    fileName: String = "zoukan.db",
    key: ByteArray = ByteArray(32) { it.toByte() },
    wrapDriver: (SqlDriver) -> SqlDriver = { it },
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "zoukan-test-database").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    val realDriver: JdbcSqliteDriver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${File(folder, fileName).absolutePath}",
        properties = DesktopDatabase.properties(key),
        schema = Database.Schema,
    )

    val driver: SqlDriver = wrapDriver(realDriver)
    val db: Database = DesktopDatabase.createDatabase(driver)
    val handler: AndroidDatabaseHandler = AndroidDatabaseHandler(
        db = db,
        driver = driver,
        queryDispatcher = dispatcher,
        transactionDispatcher = dispatcher,
    )

    override fun close() {
        realDriver.close()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun withFailingDriver(
            folder: File,
            fileName: String = "zoukan.db",
            key: ByteArray = ByteArray(32) { it.toByte() },
        ): Pair<TestDatabaseKit, FailingSqlDriver> {
            lateinit var failing: FailingSqlDriver
            val kit = TestDatabaseKit(folder, fileName, key) { real ->
                FailingSqlDriver(real).also { failing = it }
            }
            return kit to failing
        }

        fun <T> withHandler(
            folder: File,
            block: suspend (AndroidDatabaseHandler) -> T,
        ): T = TestDatabaseKit(folder).use { kit -> runBlocking { block(kit.handler) } }
    }
}
