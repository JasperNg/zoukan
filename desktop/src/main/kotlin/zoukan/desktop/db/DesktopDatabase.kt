package zoukan.desktop.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Properties

/**
 * The app's SQLDelight database, always encrypted.
 *
 * The driver is sqlite-jdbc built with SQLite3MultipleCiphers (io.github.willena:sqlite-jdbc),
 * with the SQLCipher 4 scheme, under SQLDelight's JdbcSqliteDriver. The key is a random 256-bit
 * raw key made on first start and kept in the OS secret store, never on disk next to the file.
 * The schema and its migrations are Komikku's own, versioned with PRAGMA user_version as on
 * Android.
 */
object DesktopDatabase {
    const val KEY_NAME = "database-key"

    class KeyMissingException(file: File) : IllegalStateException(
        "The database ${file.path} exists but its key is not in the secret store. " +
            "It cannot be opened; restore a backup into a new database.",
    )

    fun createDriver(file: File, secrets: SecretStore): SqlDriver {
        val key = databaseKey(file, secrets)
        return JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            properties = properties(key),
            schema = Database.Schema,
        ).also(::fixTriggers)
    }

    /**
     * The schema's trigger that keeps the system category raises its error with a double-quoted
     * string. SQLCipher here is built without double-quoted string literals, so every category
     * delete failed with "no such column". Recreate it with a single-quoted string; the trigger
     * text is stored in the file, so this also fixes databases made on Android.
     */
    private fun fixTriggers(driver: SqlDriver) {
        driver.execute(null, "DROP TRIGGER IF EXISTS system_category_delete_trigger", 0)
        driver.execute(
            null,
            """
            CREATE TRIGGER system_category_delete_trigger BEFORE DELETE
            ON categories
            BEGIN SELECT CASE
                WHEN old._id <= 0 THEN
                    RAISE(ABORT, 'System category can''t be deleted')
                END;
            END
            """.trimIndent(),
            0,
        )
    }

    fun createDatabase(driver: SqlDriver): Database = Database(
        driver = driver,
        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
        mangasAdapter = Mangas.Adapter(
            genreAdapter = StringListColumnAdapter,
            update_strategyAdapter = UpdateStrategyColumnAdapter,
            memoAdapter = MemoColumnAdapter,
        ),
        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
    )

    /** The key from the secret store; a new one when neither the key nor the database exists. */
    private fun databaseKey(file: File, secrets: SecretStore): ByteArray {
        secrets.get(KEY_NAME)?.let { return HexFormat.of().parseHex(it) }
        if (file.exists() && file.length() > 0) throw KeyMissingException(file)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secrets.set(KEY_NAME, HexFormat.of().formatHex(key))
        return key
    }

    fun properties(key: ByteArray): Properties =
        SQLiteMCSqlCipherConfig.getV4Defaults()
            .withRawUnsaltedKey(key)
            .build()
            .toProperties()
            .apply {
                setProperty("foreign_keys", "true")
                // WAL lets the few reads that bypass the database thread (see DesktopAppModule) go
                // on while it writes
                setProperty("journal_mode", "WAL")
                setProperty("busy_timeout", "30000")
            }
}
