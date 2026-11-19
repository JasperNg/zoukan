package zoukan.desktop.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * A schema is created or migrated with foreign keys off and they are on again after, as Android's
 * driver (AndroidxSqliteDriver with foreign keys on) does and as SQLite's documented procedure for
 * schema changes says: a migration that rebuilds a parent table must not cascade-delete its
 * children, and an old row whose parent is gone must not stop the migration.
 */
class MigrationForeignKeysTest {

    @TempDir
    lateinit var temp: File

    private val url get() = "jdbc:sqlite:${File(temp, "fk.db").absolutePath}"
    private val properties get() = DesktopDatabase.properties(ByteArray(32) { 7 })

    /** Version 1: a parent and a child that cascades; 1 to 2 rebuilds the parent, as 14.sqm-style migrations do. */
    private object Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 2L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE parent (_id INTEGER PRIMARY KEY, name TEXT, extra TEXT)", 0)
            driver.execute(null, "CREATE TABLE child (_id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent(_id) ON DELETE CASCADE)", 0)
            return QueryResult.Unit
        }

        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            if (oldVersion <= 1 && newVersion > 1) {
                driver.execute(null, "ALTER TABLE parent RENAME TO parent_old", 0)
                driver.execute(null, "CREATE TABLE parent (_id INTEGER PRIMARY KEY, name TEXT, extra TEXT)", 0)
                driver.execute(null, "INSERT INTO parent (_id, name) SELECT _id, name FROM parent_old", 0)
                driver.execute(null, "DROP TABLE parent_old", 0)
            }
            return QueryResult.Unit
        }
    }

    /** A version-1 database with a parent, its child and an orphan child. */
    private fun versionOne() {
        DriverManager.getConnection(url, properties.apply { setProperty("foreign_keys", "false") }).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE parent (_id INTEGER PRIMARY KEY, name TEXT)")
                it.execute("CREATE TABLE child (_id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent(_id) ON DELETE CASCADE)")
                it.execute("INSERT INTO parent VALUES (1, 'kept')")
                it.execute("INSERT INTO child VALUES (10, 1)")
                it.execute("INSERT INTO child VALUES (11, 999)")
                it.execute("PRAGMA user_version = 1")
            }
        }
    }

    private fun SqlDriver.long(sql: String): Long =
        executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value!!

    @Test
    fun `a migration that rebuilds a parent table keeps its children and the old orphan`() {
        versionOne()
        val driver = DesktopSqliteDriver.withSchema(url, properties, null, Schema)
        try {
            driver.long("PRAGMA user_version") shouldBe 2
            driver.long("SELECT count(*) FROM child") shouldBe 2
            driver.long("SELECT count(*) FROM parent") shouldBe 1
            // Foreign keys are on again for the app
            driver.long("PRAGMA foreign_keys") shouldBe 1
        } finally {
            driver.close()
        }
    }
}
