package tachiyomi.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A database file in [folder] with the newest schema, on the SQLite build the desktop app ships,
 * opened the way the desktop opens it apart from the key: one thread for queries and
 * transactions, foreign keys on, and the column adapters the desktop's database uses.
 */
class TestDatabase(folder: File) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "test-database") }

    val driver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${File(folder, "test.db").absolutePath}",
        properties = Properties().apply { setProperty("foreign_keys", "true") },
        schema = Database.Schema,
    )

    val db: Database = Database(
        driver = driver,
        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
            memoAdapter = MemoColumnAdapter,
        ),
        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
    )

    val handler: AndroidDatabaseHandler = executor.asCoroutineDispatcher().let { dispatcher ->
        AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
    }

    override fun close() {
        driver.close()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * A library [tachiyomi.domain.manga.model.Manga] looks up the user's own title and cover through
 * Injekt when it is created; here no entry has any.
 */
fun registerNoCustomMangaInfo() {
    val info = mockk<GetCustomMangaInfo>()
    every { info.get(any()) } returns null
    Injekt.addSingleton(info)
}
