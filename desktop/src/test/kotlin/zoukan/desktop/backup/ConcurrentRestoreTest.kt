package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import zoukan.desktop.db.DesktopDatabase
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A backup restore runs its restorers side by side. Each must write through the database handler,
 * whose single thread holds the one connection: a write from another thread uses that thread's own
 * connection, and a restore transaction that already read then fails its next write with
 * SQLITE_BUSY.
 */
class ConcurrentRestoreTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `restoring extension stores during another restore transaction does not fail it`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        try {
            val read = CompletableFuture<Unit>()
            val write = CompletableFuture<Unit>()
            // Like SavedSearchRestorer: read in a transaction, then write
            val transaction = async {
                handler.await(true) {
                    handler.awaitList { saved_searchQueries.selectAll() }
                    read.complete(Unit)
                    write.get(10, TimeUnit.SECONDS)
                    saved_searchQueries.insert(1, "name", "query", null)
                }
            }
            withTimeout(10_000) { read.await() }
            val store = async {
                ExtensionStoreRestorer(handler)(
                    BackupExtensionStore(
                        indexUrl = "https://example.org/repo.json",
                        name = "Store",
                        badgeLabel = null,
                        signingKey = "key",
                        contactWebsite = "https://example.org",
                        contactDiscord = null,
                        isLegacy = false,
                        extensionListUrl = null,
                    ),
                )
            }
            // Give a direct write time to land inside the transaction
            delay(200)
            write.complete(Unit)

            withTimeout(10_000) {
                transaction.await()
                store.await()
            }
            handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe 1
            handler.awaitList { extension_storeQueries.getAll() }.size shouldBe 1
        } finally {
            driver.close()
            executor.shutdownNow()
        }
    }
}
