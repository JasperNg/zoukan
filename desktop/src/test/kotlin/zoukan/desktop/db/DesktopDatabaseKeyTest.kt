package zoukan.desktop.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.util.HexFormat

class DesktopDatabaseKeyTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "zoukan.db")

    private class MapStore : SecretStore {
        val values = mutableMapOf<String, String>()
        override val name = "test"
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
    }

    private fun SqlDriver.userVersion(): Long =
        executeQuery(null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 0).value ?: 0L

    /** A database made and closed by this build, with its key in [store]. */
    private fun createDatabase(store: SecretStore) {
        DesktopDatabase.createDriver(file, store).close()
        (file.length() > 0) shouldBe true
    }

    private fun setUserVersion(store: MapStore, version: Long) {
        val key = HexFormat.of().parseHex(store.values.getValue(DesktopDatabase.KEY_NAME))
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", DesktopDatabase.properties(key)).use {
            it.execute(null, "PRAGMA user_version = $version", 0)
        }
    }

    private fun preCopies() = dir.listFiles().orEmpty().map { it.name }.filter { it.contains(".pre-v") }.sorted()

    @Test
    fun `an existing database without its key is refused and left byte-identical`() {
        createDatabase(MapStore())
        val before = file.readBytes()
        val empty = MapStore()

        shouldThrow<DesktopDatabase.KeyMissingException> { DesktopDatabase.createDriver(file, empty) }

        file.readBytes().contentEquals(before) shouldBe true
        // No new key that could never read the file
        empty.values shouldBe emptyMap()
    }

    @Test
    fun `an empty database file whose WAL is still there is refused without a new key`() {
        file.writeBytes(ByteArray(0))
        val wal = File(dir, "zoukan.db-wal").apply { writeBytes(ByteArray(64) { 7 }) }
        val store = MapStore()

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
        val store = MapStore()

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
        val store = MapStore()
        createDatabase(store)
        val key = store.values.getValue(DesktopDatabase.KEY_NAME)

        DesktopDatabase.createDriver(file, store).use { it.userVersion() shouldBe Database.Schema.version }

        store.values.getValue(DesktopDatabase.KEY_NAME) shouldBe key
        preCopies() shouldBe emptyList()
    }

    @Test
    fun `a database from a newer build is refused without changing the file`() {
        val store = MapStore()
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
        val store = MapStore()
        createDatabase(store)
        val key = HexFormat.of().parseHex(store.values.getValue(DesktopDatabase.KEY_NAME))
        // A crash while a newer build had it open: the new version is only in the WAL. The files are
        // copied while that connection is open, as closing it would checkpoint them
        val crashed = File(dir, "crashed").apply { mkdirs() }
        val copy = File(crashed, "zoukan.db")
        // A plain connection: SQLDelight's driver closes its connection (and checkpoints) after each statement
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", DesktopDatabase.properties(key)).use { connection ->
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
        val store = MapStore()
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
