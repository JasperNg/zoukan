package zoukan.desktop.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.Await
import java.io.File
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DesktopDatabaseKeyTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "zoukan.db")

    private fun SqlDriver.userVersion(): Long =
        executeQuery(null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 0).value ?: 0L

    /** A database made and closed by this build, with its key in [store]. */
    private fun createDatabase(store: SecretStore) {
        DesktopDatabase.createDriver(file, store).close()
        (file.length() > 0) shouldBe true
    }

    private fun setUserVersion(store: OldDatabases.MapStore, version: Long) {
        val key = HexFormat.of().parseHex(store.values.getValue(DesktopDatabase.KEY_NAME))
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", DesktopDatabase.properties(key)).use {
            it.execute(null, "PRAGMA user_version = $version", 0)
        }
    }

    private fun preCopies() = dir.listFiles().orEmpty().map { it.name }.filter { it.contains(".pre-v") }.sorted()

    @Test
    fun `an existing database without its key is refused and left byte-identical`() {
        createDatabase(OldDatabases.MapStore())
        val before = file.readBytes()
        val empty = OldDatabases.MapStore()

        shouldThrow<DesktopDatabase.KeyMissingException> { DesktopDatabase.createDriver(file, empty) }

        file.readBytes().contentEquals(before) shouldBe true
        // No new key that could never read the file
        empty.values shouldBe emptyMap()
    }

    @Test
    fun `an empty database file whose WAL is still there is refused without a new key`() {
        file.writeBytes(ByteArray(0))
        val wal = File(dir, "zoukan.db-wal").apply { writeBytes(ByteArray(64) { 7 }) }
        val store = OldDatabases.MapStore()

        shouldThrow<DesktopDatabase.JournalWithoutKeyException> { DesktopDatabase.createDriver(file, store) }

        store.values shouldBe emptyMap()
        file.length() shouldBe 0
        wal.readBytes().contentEquals(ByteArray(64) { 7 }) shouldBe true
    }

    @Test
    fun `a stale shared-memory file alone, or an empty WAL, does not block start-up`() {
        file.writeBytes(ByteArray(0))
        File(dir, "zoukan.db-shm").writeBytes(ByteArray(32))
        File(dir, "zoukan.db-wal").writeBytes(ByteArray(0))
        val store = OldDatabases.MapStore()

        DesktopDatabase.createDriver(file, store).use { it.userVersion() shouldBe Database.Schema.version }

        store.values.keys shouldBe setOf(DesktopDatabase.KEY_NAME)
    }

    @Test
    fun `a secret store error creates no key and no database`() {
        var setCalled = false
        val failing = object : SecretStore {
            override val name = "failing"
            override fun get(key: String): String = throw IllegalStateException("SecKeychainFindGenericPassword failed")
            override fun set(key: String, value: String) {
                setCalled = true
            }
            override fun delete(key: String) = Unit
        }

        shouldThrow<IllegalStateException> { DesktopDatabase.createDriver(file, failing) }

        setCalled shouldBe false
        file.exists() shouldBe false
    }

    @Test
    fun `a new database gets a new key and opens again with it`() {
        val store = OldDatabases.MapStore()
        createDatabase(store)
        val key = store.values.getValue(DesktopDatabase.KEY_NAME)

        DesktopDatabase.createDriver(file, store).use { it.userVersion() shouldBe Database.Schema.version }

        store.values.getValue(DesktopDatabase.KEY_NAME) shouldBe key
        preCopies() shouldBe emptyList()
    }

    @Test
    fun `a database from a newer build is refused without changing the file`() {
        val store = OldDatabases.MapStore()
        createDatabase(store)
        val newer = Database.Schema.version + 3
        setUserVersion(store, newer)
        val before = file.readBytes()

        val e = shouldThrow<DesktopDatabase.TooNewException> { DesktopDatabase.createDriver(file, store) }

        e.version shouldBe newer
        file.readBytes().contentEquals(before) shouldBe true
        preCopies() shouldBe emptyList()
    }

    @Test
    fun `a newer database with pages still in its WAL is refused and its files left byte-identical`() {
        val store = OldDatabases.MapStore()
        createDatabase(store)
        val key = HexFormat.of().parseHex(store.values.getValue(DesktopDatabase.KEY_NAME))
        // A crash while a newer build had it open: the new version is only in the WAL. The files are
        // copied while that connection is open, as closing it would checkpoint them
        val crashed = File(dir, "crashed").apply { mkdirs() }
        val copy = File(crashed, "zoukan.db")
        // A plain connection: SQLDelight's driver closes its connection (and checkpoints) after each statement
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", DesktopDatabase.properties(key)).use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA wal_autocheckpoint = 0")
                it.execute("PRAGMA user_version = ${Database.Schema.version + 3}")
            }
            file.copyTo(copy)
            File(dir, "zoukan.db-wal").copyTo(File(crashed, "zoukan.db-wal"))
        }
        val wal = File(crashed, "zoukan.db-wal")
        (wal.length() > 0) shouldBe true
        val before = copy.readBytes()
        val walBefore = wal.readBytes()

        shouldThrow<DesktopDatabase.TooNewException> { DesktopDatabase.createDriver(copy, store) }

        copy.readBytes().contentEquals(before) shouldBe true
        wal.readBytes().contentEquals(walBefore) shouldBe true
    }

    @Test
    fun `a copy is made before a migration and only the latest is kept`() {
        val store = OldDatabases.MapStore()
        createDatabase(store)
        val current = Database.Schema.version
        val before = file.readBytes()

        // As if this build migrated to the next version
        DesktopDatabase.createDriver(file, store, current + 1).close()

        preCopies() shouldBe listOf("zoukan.db.pre-v${current + 1}")
        val copy = DesktopDatabase.preMigrationCopy(file, current + 1)
        copy.readBytes().contentEquals(before) shouldBe true
        // Encrypted with the same key: it opens with it, at the version it had before
        val key = HexFormat.of().parseHex(store.values.getValue(DesktopDatabase.KEY_NAME))
        JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}", DesktopDatabase.properties(key)).use {
            it.userVersion() shouldBe current
        }

        DesktopDatabase.createDriver(file, store, current + 2).close()

        preCopies() shouldBe listOf("zoukan.db.pre-v${current + 2}")
    }
}

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
        withContext(Dispatchers.IO) {
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
        val busy = withContext(Dispatchers.IO) {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0))
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
                QueryResult.Value(names)
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
        val heard = AtomicInteger()
        driver.addListener("categories", listener = Query.Listener { heard.incrementAndGet() })

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
        val thread = DatabaseThread()
        val driver = DesktopSqliteDriver.withSchema(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            databaseThread = thread,
            schema = Database.Schema,
        )
        val dispatcher = thread.executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(DesktopDatabase.createDatabase(driver), driver, dispatcher, dispatcher)
        val ui = Executors.newSingleThreadExecutor { Thread(it, "ui").apply { isDaemon = true } }
        val uiScope = CoroutineScope(ui.asCoroutineDispatcher())
        val transactionRuns = CountDownLatch(1)
        try {
            val names = ui.submit<List<String>> {
                // A click handler starts a transaction and suspends while it takes the database thread
                uiScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    handler.await(inTransaction = true) {
                        transactionRuns.countDown()
                        categoriesQueries.insert("From the UI", 1, 0, 0L)
                    }
                }
                // Once the database thread has been taken
                transactionRuns.await(10, TimeUnit.SECONDS) shouldBe true
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
            thread.executor.shutdownNow()
        }
    }
}
