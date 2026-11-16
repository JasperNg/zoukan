package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import zoukan.desktop.db.DesktopDatabase
import java.io.File
import java.util.concurrent.Executors

/** Restoring the same backup again must not add the searches and feeds the database already has. */
class SavedSearchRestoreTest {

    @TempDir
    lateinit var temp: File

    private fun withHandler(block: suspend (AndroidDatabaseHandler) -> Unit) {
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
            runBlocking { block(handler) }
        } finally {
            driver.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `restoring the same saved searches twice keeps one of each`() = withHandler { handler ->
        val backup = SyntheticBackup.create()
        val restorer = SavedSearchRestorer(handler)
        restorer.restoreSavedSearches(backup.backupSavedSearches)
        restorer.restoreSavedSearches(backup.backupSavedSearches)
        handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe 2
    }

    @Test
    fun `restoring the same feeds twice keeps one of each`() = withHandler { handler ->
        val backup = SyntheticBackup.create()
        val filterless = backup.backupSavedSearches.first { it.filterList.isBlank() }
        val feeds = backup.backupFeeds + BackupFeed(source = filterless.source, global = false, savedSearch = filterless)
        SavedSearchRestorer(handler).restoreSavedSearches(backup.backupSavedSearches)
        val feedRestorer = FeedRestorer(handler)
        feedRestorer.restoreFeeds(feeds)
        val feedsAfterOne = handler.awaitList { feed_saved_searchQueries.selectAllFeedWithSavedSearch() }.size
        val searchesAfterOne = handler.awaitList { saved_searchQueries.selectAll() }.size

        feedRestorer.restoreFeeds(feeds)

        handler.awaitList { feed_saved_searchQueries.selectAllFeedWithSavedSearch() }.size shouldBe feedsAfterOne
        handler.awaitList { saved_searchQueries.selectAll() }.size shouldBe searchesAfterOne
    }
}
