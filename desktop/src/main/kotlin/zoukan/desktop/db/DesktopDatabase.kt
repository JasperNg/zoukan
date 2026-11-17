package zoukan.desktop.db

import app.cash.sqldelight.db.QueryResult
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
import zoukan.desktop.compat.localized
import zoukan.desktop.secrets.SecretStore
import zoukan.i18n.desktop.DMR
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        localized(DMR.strings.database_key_missing, file.path),
    )

    /** The database file is empty but its WAL or shared-memory file is there, and there is no key. */
    class JournalWithoutKeyException(file: File) : IllegalStateException(
        localized(DMR.strings.database_journal_without_key, file.path),
    )

    /** The database was made by a newer build (a downgrade); it is left as it is. */
    class TooNewException(file: File, val version: Long, val supported: Long) : IllegalStateException(
        localized(DMR.strings.database_too_new, file.path, version, supported),
    )

    /** The copy made before a schema migration could not be saved; the database was not migrated. */
    class CopyFailedException(cause: Throwable) : IllegalStateException(
        localized(DMR.strings.database_copy_failed, cause.message ?: cause::class.simpleName.orEmpty()),
        cause,
    )

    /** The copy kept before an automatic migration to [version]: `zoukan.db.pre-v<version>`. */
    fun preMigrationCopy(file: File, version: Long): File = File(file.parentFile, "${file.name}$PRE_MIGRATION$version")

    private const val PRE_MIGRATION = ".pre-v"

    fun createDriver(file: File, secrets: SecretStore): SqlDriver = createDriver(file, secrets, Database.Schema.version)

    /** [schemaVersion] is the version this build migrates to; tests pass another to check the copy. */
    internal fun createDriver(file: File, secrets: SecretStore, schemaVersion: Long): SqlDriver {
        val key = databaseKey(file, secrets)
        val url = "jdbc:sqlite:${file.absolutePath}"
        val properties = properties(key)
        // Checked before SQLDelight sees the file: it would leave a newer database open as it is
        // (and fixTriggers would then write to it), and it migrates without a copy
        if (file.isFile && file.length() > 0) checkVersion(file, url, properties, schemaVersion)
        return JdbcSqliteDriver(url = url, properties = properties, schema = Database.Schema).also(::fixTriggers)
    }

    /**
     * Refuses a database newer than [schemaVersion] without writing to it, and copies one that is
     * about to be migrated to [preMigrationCopy] first, keeping only the latest such copy.
     */
    private fun checkVersion(file: File, url: String, properties: Properties, schemaVersion: Long) {
        // Only the key: no journal_mode or other setting that would write to the file
        val probeProperties = Properties().apply {
            properties.forEach { (k, v) -> if (k != "journal_mode" && k != "foreign_keys") setProperty(k.toString(), v.toString()) }
        }
        val probe = JdbcSqliteDriver(url, probeProperties)
        try {
            val version = probe.executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
            }, 0).value ?: 0L
            if (version > schemaVersion) throw TooNewException(file, version, schemaVersion)
            if (version == 0L || version == schemaVersion) return
            // Move the WAL's pages into the file, so the copy is the whole database
            probe.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { QueryResult.Value(Unit) }, 0)
        } finally {
            probe.close()
        }
        copyBeforeMigration(file, schemaVersion)
    }

    private fun copyBeforeMigration(file: File, schemaVersion: Long) {
        val copy = preMigrationCopy(file, schemaVersion)
        try {
            // Copied under a temporary name, then renamed, so a copy with the final name is whole
            copyAtomically(file, copy)
            val wal = File(file.parentFile, "${file.name}-wal")
            val copyWal = File(copy.parentFile, "${copy.name}-wal")
            // Normally empty after the checkpoint; kept beside the copy (SQLite reads it as the copy's WAL)
            if (wal.isFile && wal.length() > 0) copyAtomically(wal, copyWal) else copyWal.delete()
        } catch (e: Exception) {
            throw CopyFailedException(e)
        }
        // Only the latest copy is kept
        file.parentFile.listFiles().orEmpty()
            .filter { it.name.startsWith("${file.name}$PRE_MIGRATION") && it != copy && it.name != "${copy.name}-wal" }
            .forEach { it.delete() }
    }

    private fun copyAtomically(from: File, to: File) {
        val tmp = File(to.parentFile, "${to.name}.tmp")
        Files.copy(from.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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

    /**
     * The key from the secret store; a new one only when neither the key nor the database exists.
     * A secret store error is thrown before any key is made. An empty file whose WAL or shared
     * memory file is still there may hold data in the WAL, so it is refused rather than given a new
     * key that could never read it.
     */
    private fun databaseKey(file: File, secrets: SecretStore): ByteArray {
        secrets.get(KEY_NAME)?.let { return HexFormat.of().parseHex(it) }
        if (file.exists() && file.length() > 0) throw KeyMissingException(file)
        val journals = listOf("-wal", "-shm").map { File(file.parentFile, file.name + it) }
        if (journals.any { it.exists() }) throw JournalWithoutKeyException(file)
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
