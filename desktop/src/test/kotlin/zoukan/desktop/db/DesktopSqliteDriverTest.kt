package zoukan.desktop.db

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import zoukan.desktop.testing.Await
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopSqliteDriverTest {

    @TempDir
    lateinit var temp: File

    private val thread = DatabaseThread()
    private lateinit var driver: DesktopSqliteDriver

    private fun open(idleCloseMs: Long = 60_000): AndroidDatabaseHandler {
        driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = thread,
            schema = Database.Schema,
            idleCloseMs = idleCloseMs,
        )
        // The schema is created on the calling thread, with a connection of its own
        driver.opened.set(0)
        val dispatcher = thread.executor.asCoroutineDispatcher()
        return AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dispatcher, dispatcher)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
        thread.executor.shutdownNow()
        thread.executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `the database thread uses one connection for many transactions and queries`() = runBlocking<Unit> {
        val handler = open()
        repeat(50) { i ->
            handler.await(inTransaction = true) { categoriesQueries.insert("Category $i", i.toLong(), 0L, 0L) }
            handler.awaitList { categoriesQueries.getCategories() }
        }
        driver.opened.get() shouldBe 1
        handler.awaitList { categoriesQueries.getCategories() }.count { it.name.startsWith("Category ") } shouldBe 50
    }

    @Test
    fun `a failed transaction rolls back and the connection goes on working`() = runBlocking<Unit> {
        val handler = open()
        runCatching {
            handler.await(inTransaction = true) {
                categoriesQueries.insert("Rolled back", 1, 0, 0L)
                error("fails")
            }
        }.isFailure shouldBe true
        handler.await(inTransaction = true) { categoriesQueries.insert("Kept", 2, 0, 0L) }
        handler.awaitList { categoriesQueries.getCategories() }.map { it.name }.let {
            ("Rolled back" in it) shouldBe false
            ("Kept" in it) shouldBe true
        }
        driver.opened.get() shouldBe 1
    }

    @Test
    fun `the connection closes once idle, checkpointing the WAL, and reopens when needed`() = runBlocking<Unit> {
        val handler = open(idleCloseMs = 100)
        handler.await(inTransaction = true) { categoriesQueries.insert("Before", 1, 0, 0L) }
        val wal = File(temp, "zoukan.db-wal")
        wal.exists() shouldBe true
        Await.untilSuspending("the idle connection to close", 1_000) { !wal.exists() }
        // Closing the last connection moves the WAL into the file and deletes it, as before
        wal.exists() shouldBe false
        handler.awaitList { categoriesQueries.getCategories() }.any { it.name == "Before" } shouldBe true
        driver.opened.get() shouldBe 2
    }

    @Test
    fun `an open transaction keeps the connection past the idle time`() = runBlocking<Unit> {
        val handler = open(idleCloseMs = 50)
        handler.await(inTransaction = true) {
            categoriesQueries.insert("Slow", 1, 0, 0L)
            delay(500)
            categoriesQueries.insert("Slower", 2, 0, 0L)
        }
        handler.awaitList { categoriesQueries.getCategories() }.count { it.name.startsWith("Slow") } shouldBe 2
    }

    @Test
    fun `other threads open and close a connection for each use`() = runBlocking<Unit> {
        val handler = open()
        handler.await { categoriesQueries.insert("On the thread", 1, 0, 0L) }
        val elsewhere = DesktopDatabase.createDatabase(driver)
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            elsewhere.categoriesQueries.getCategories().executeAsList().any { it.name == "On the thread" } shouldBe true
            elsewhere.categoriesQueries.getCategories().executeAsList()
        }
        driver.opened.get() shouldBe 3
    }

    @Test
    fun `close from another thread closes the database thread's connection`() = runBlocking<Unit> {
        val handler = open()
        handler.await(inTransaction = true) { categoriesQueries.insert("Saved", 1, 0, 0L) }
        driver.close()
        File(temp, "zoukan.db-wal").exists() shouldBe false
        // Still usable after: a new connection opens
        handler.awaitList { categoriesQueries.getCategories() }.any { it.name == "Saved" } shouldBe true
    }

    @Test
    fun `kept statements hold no read transaction, so another connection can checkpoint`() = runBlocking<Unit> {
        val handler = open()
        repeat(20) { i ->
            handler.await(inTransaction = true) { categoriesQueries.insert("Category $i", i.toLong(), 0, 0L) }
            handler.awaitList { categoriesQueries.getCategories() }
        }
        // The insert and the select; BEGIN and END are not kept
        withContext(thread.executor.asCoroutineDispatcher()) { driver.cachedStatements() } shouldBe 2
        // A checkpoint that has to wait for a reader reports busy (1) in the first column
        val busy = withContext(kotlinx.coroutines.Dispatchers.IO) {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
            }, 0).value
        }
        busy shouldBe 0L
    }

    @Test
    fun `a statement that fails is not kept, and the same statement works after`() = runBlocking<Unit> {
        open()
        val insert = "INSERT INTO categories(_id, name, sort, flags, manga_order, hidden) VALUES (?, ?, 0, 0, '', 0)"
        fun add(id: Long, name: String) = driver.execute(null, insert, 2) {
            bindLong(0, id)
            bindString(1, name)
        }
        withContext(thread.executor.asCoroutineDispatcher()) {
            add(100, "First")
            // The same id again breaks the primary key
            runCatching { add(100, "Again") }.isFailure shouldBe true
            add(101, "Second")
            driver.executeQuery(null, "SELECT name FROM categories WHERE _id >= 100 ORDER BY _id", { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Value(names)
            }, 0).value shouldBe listOf("First", "Second")
        }
    }

    @Test
    fun `closing closes the kept statements with the connection`() = runBlocking<Unit> {
        val handler = open()
        handler.awaitList { categoriesQueries.getCategories() }
        val dispatcher = thread.executor.asCoroutineDispatcher()
        withContext(dispatcher) { driver.cachedStatements() } shouldBe 1
        driver.close()
        withContext(dispatcher) { driver.cachedStatements() } shouldBe 0
        handler.awaitList { categoriesQueries.getCategories() }
        withContext(dispatcher) { driver.cachedStatements() } shouldBe 1
    }

    @Test
    fun `while deferred, watched queries hear of changes once per interval and at the end`() = runBlocking<Unit> {
        val handler = open()
        val heard = java.util.concurrent.atomic.AtomicInteger()
        driver.addListener("categories", listener = app.cash.sqldelight.Query.Listener { heard.incrementAndGet() })

        handler.await(inTransaction = true) { categoriesQueries.insert("Heard", 1, 0, 0L) }
        heard.get() shouldBe 1

        val deferred = driver.deferNotifications()
        repeat(20) { i -> handler.await(inTransaction = true) { categoriesQueries.insert("Quiet $i", i + 2L, 0, 0L) } }
        heard.get() shouldBe 1
        deferred.close()
        heard.get() shouldBe 2
        // Every insert is in the database, whenever the listeners heard
        handler.awaitList { categoriesQueries.getCategories() }.count { it.name.startsWith("Quiet ") } shouldBe 20

        handler.await(inTransaction = true) { categoriesQueries.insert("Heard again", 99, 0, 0L) }
        heard.get() shouldBe 3
    }
}
