package zoukan.desktop.db

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.Database
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat

/**
 * Builds encrypted databases at old schema versions for the migration tests, from the repository's
 * own files rather than committed binaries.
 *
 * From version 28: the oldest schema snapshot the repository holds is SQLDelight's
 * `data/src/commonMain/sqldelight/28.db` (the schema before `28.sqm`). A database at version N is that
 * snapshot's schema and rows (the system category), copied into a new database encrypted as the
 * desktop encrypts it, then migrated with the generated migrations `28.sqm` to `(N-1).sqm`.
 *
 * Before 28: the version-1 schema is the SQL text in the test resource [V1_SCHEMA] (how it was
 * derived is in its header; the cross-check in `DatabaseMigrationTest` proves it against `28.db`),
 * migrated with the generated `1.sqm` to `(N-1).sqm`. Two steps differ from running the migrations
 * alone. First, `21.sqm` to `27.sqm` run with the system category's trigger single-quoted, and the
 * trigger is then put back as the Android app has it ([TRIGGER_VERSION]). Second, a database at 4 to 13 was made by TachiyomiSY's code from before SQLDelight, whose `merged`
 * table names its source column `manga_source`; `3.sqm` names it `COL_MANGA_SOURCE` (a column
 * constant's name written as text), which `13.sqm` cannot read. So from version 4 the column takes
 * the name the real databases had. A database at 1 to 3 keeps `3.sqm`'s name, which is what this
 * build would do to it, and fails at `13.sqm` (pinned by `DatabaseMigrationTest`).
 *
 * Either way the database ends with `PRAGMA user_version = N`, and [seed] then fills it with
 * [MigrationSeed]'s rows as they existed at N.
 */
internal object OldDatabases {
    /** The version of the oldest schema snapshot in the repository. */
    const val SNAPSHOT_VERSION = 28L

    /** The first version `3.sqm` has run on; see the class comment. */
    const val MERGED_SOURCE_COLUMN_VERSION = 4L

    /** The last version whose `history` table still has the `history_` column names (`14.sqm` renames them). */
    const val HISTORY_PREFIX_VERSION = 14L

    /**
     * The first version with the system category's trigger. `20.sqm` raises its error with a
     * double-quoted string, which this build's SQLite (built without double-quoted string
     * literals) rejects when an `ALTER TABLE … RENAME` checks the schema, so the desktop's start-up
     * path cannot run `26.sqm` on a database from before 27 (pinned by `DatabaseMigrationTest`).
     * The trigger is rewritten only after the migrations.
     */
    const val TRIGGER_VERSION = 21L

    /** The version-1 schema, as SQL statements separated by lines holding only `;`. */
    const val V1_SCHEMA = "/db/schema-v1.sql"

    private const val SNAPSHOT_PATH = "data/src/commonMain/sqldelight/28.db"
    private const val MIGRATIONS_PATH = "data/src/commonMain/sqldelight/tachiyomi/migrations"

    /** The newest version, the one this build migrates to. */
    val NEWEST: Long get() = Database.Schema.version

    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, SNAPSHOT_PATH).isFile }
            ?: error("$SNAPSHOT_PATH not found above ${System.getProperty("user.dir")}")
    }

    val snapshot: File get() = File(repoRoot, SNAPSHOT_PATH)

    /** The `.sqm` migration files, by number. */
    val migrationFiles: List<File>
        get() = File(repoRoot, MIGRATIONS_PATH).listFiles { f -> f.name.endsWith(".sqm") }.orEmpty()
            .sortedBy { it.nameWithoutExtension.toInt() }

    /** A secret store holding [key] as the database key, as the OS secret store would. */
    class MapStore(key: ByteArray? = null) : SecretStore {
        val values = mutableMapOf<String, String>()
        override val name = "test"

        init {
            if (key != null) values[DesktopDatabase.KEY_NAME] = HexFormat.of().formatHex(key)
        }

        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
    }

    fun key(seed: Int): ByteArray = ByteArray(32) { (it * 7 + seed).toByte() }

    fun url(file: File) = "jdbc:sqlite:${file.absolutePath}"

    /** A plain JDBC connection to an encrypted database, foreign keys off unless [foreignKeys]. */
    fun connect(file: File, key: ByteArray, foreignKeys: Boolean = false): Connection =
        DriverManager.getConnection(
            url(file),
            DesktopDatabase.properties(key).apply { setProperty("foreign_keys", foreignKeys.toString()) },
        )

    /**
     * Makes [file], encrypted with [key], with the schema at [version] and no rows but the system
     * category (where the schema has one). It starts from the version-1 schema when [fromVersion1]
     * (always below 28), otherwise from the snapshot. [migrate] runs the migrations from the start
     * to [version]; the self-checks pass one that skips a migration.
     */
    fun create(
        file: File,
        key: ByteArray,
        version: Long,
        fromVersion1: Boolean = version < SNAPSHOT_VERSION,
        migrate: (SqlDriver, Long, Long) -> Unit = { driver, from, to -> Database.Schema.migrate(driver, from, to) },
    ) {
        require(version in 1..NEWEST) { "No schema for version $version" }
        require(fromVersion1 || version >= SNAPSHOT_VERSION) { "The snapshot is at $SNAPSHOT_VERSION, after $version" }
        check(!file.exists()) { "$file exists" }
        // Foreign keys off, as the app (and Android's driver) migrates
        JdbcSqliteDriver(url(file), migrationProperties(key)).use { driver ->
            object : TransacterImpl(driver) {}.transaction {
                if (fromVersion1) createFromVersion1(file, driver, version, migrate) else createFromSnapshot(file, driver, version, migrate)
                driver.execute(null, "PRAGMA user_version = $version", 0)
            }
        }
    }

    /**
     * Migrates [file] (with rows) from [from] to [to] as [create] does below 28, getting past the
     * trigger (see [TRIGGER_VERSION]) the desktop's own start-up path cannot.
     */
    fun migrate(file: File, key: ByteArray, from: Long, to: Long) {
        require(from in MERGED_SOURCE_COLUMN_VERSION..to)
        JdbcSqliteDriver(url(file), migrationProperties(key)).use { driver ->
            object : TransacterImpl(driver) {}.transaction {
                migratePastTrigger(file, driver, from, to) { d, a, b -> Database.Schema.migrate(d, a, b) }
                driver.execute(null, "PRAGMA user_version = $to", 0)
            }
        }
    }

    /** The app's connection settings with foreign keys off, as it migrates. */
    private fun migrationProperties(key: ByteArray) = DesktopDatabase.properties(key).apply { setProperty("foreign_keys", "false") }

    private fun createFromSnapshot(file: File, driver: SqlDriver, version: Long, migrate: (SqlDriver, Long, Long) -> Unit) {
        val (statements, rows) = readSnapshot(file.parentFile)
        statements.forEach { driver.execute(null, it, 0) }
        rows.forEach { (table, tableRows) ->
            tableRows.forEach { values ->
                val marks = values.joinToString { "?" }
                driver.execute(null, "INSERT INTO $table VALUES ($marks)", values.size) {
                    values.forEachIndexed { i, v -> bind(i, v) }
                }
            }
        }
        if (version > SNAPSHOT_VERSION) migrate(driver, SNAPSHOT_VERSION, version)
    }

    private fun createFromVersion1(file: File, driver: SqlDriver, version: Long, migrate: (SqlDriver, Long, Long) -> Unit) {
        version1Statements().forEach { driver.execute(null, it, 0) }
        val merged = MERGED_SOURCE_COLUMN_VERSION
        if (version > 1) migrate(driver, 1, minOf(version, merged))
        if (version >= merged) {
            // As TachiyomiSY's own upgrade named it; see the class comment
            driver.execute(null, "ALTER TABLE merged RENAME COLUMN COL_MANGA_SOURCE TO manga_source", 0)
        }
        if (version > merged) migratePastTrigger(file, driver, merged, version, migrate)
    }

    /**
     * Runs [migrate] from [from] to [to] with the system category's trigger single-quoted, as the
     * desktop's start-up rewrites it, while `21.sqm` to `27.sqm` run; then puts back the trigger as
     * `20.sqm` and the snapshot have it, so the database is what the Android app leaves.
     */
    private fun migratePastTrigger(file: File, driver: SqlDriver, from: Long, to: Long, migrate: (SqlDriver, Long, Long) -> Unit) {
        val trigger = TRIGGER_VERSION
        if (from < trigger) migrate(driver, from, minOf(to, trigger))
        if (to <= trigger) return
        driver.execute(null, "DROP TRIGGER IF EXISTS system_category_delete_trigger", 0)
        driver.execute(null, SINGLE_QUOTED_TRIGGER, 0)
        migrate(driver, maxOf(from, trigger), to)
        driver.execute(null, "DROP TRIGGER system_category_delete_trigger", 0)
        driver.execute(null, snapshotTrigger(file.parentFile), 0)
    }

    /** The system category's trigger as `20.sqm` makes it and the snapshot holds it. */
    private fun snapshotTrigger(tempDir: File): String =
        readSnapshot(tempDir).first.single { it.startsWith("CREATE TRIGGER system_category_delete_trigger") }

    /** The trigger as `DesktopDatabase` rewrites it at start-up. */
    private val SINGLE_QUOTED_TRIGGER = """
        CREATE TRIGGER system_category_delete_trigger BEFORE DELETE
        ON categories
        BEGIN SELECT CASE
            WHEN old._id <= 0 THEN
                RAISE(ABORT, 'System category can''t be deleted')
            END;
        END
    """.trimIndent()

    /** [V1_SCHEMA]'s statements, without its comment lines. */
    fun version1Statements(): List<String> {
        val text = OldDatabases::class.java.getResourceAsStream(V1_SCHEMA)?.use { it.readBytes().decodeToString() }
            ?: error("$V1_SCHEMA not found")
        return text.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(Regex("(?m)^;\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The snapshot's schema statements (tables, indexes, views, triggers, in that order) and its rows. */
    private fun readSnapshot(tempDir: File): Pair<List<String>, List<Pair<String, List<List<Any?>>>>> {
        // Read from a copy, so the repository's file is never opened for writing
        val copy = File.createTempFile("schema-28-", ".db", tempDir)
        try {
            snapshot.copyTo(copy, overwrite = true)
            DriverManager.getConnection(url(copy)).use { c ->
                val statements = mutableListOf<String>()
                val tables = mutableListOf<String>()
                c.createStatement().use { st ->
                    st.executeQuery(
                        """
                        SELECT type, name, sql FROM sqlite_master
                        WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                        ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END, rowid
                        """.trimIndent(),
                    ).use { rs ->
                        while (rs.next()) {
                            statements += rs.getString(3)
                            if (rs.getString(1) == "table") tables += rs.getString(2)
                        }
                    }
                }
                val rows = tables.map { table -> table to SchemaCompare.rows(c, table).map { it.values.toList() } }
                    .filter { it.second.isNotEmpty() }
                return statements to rows
            }
        } finally {
            copy.delete()
        }
    }

    /**
     * Inserts [MigrationSeed.rows] into [file] (at [version]), each with only the columns and tables
     * that exist at that version, foreign keys off (so the orphans the rows include can be made, as
     * old Android builds left them; without [orphans], [MigrationSeed.orphans] are left out). A whole
     * REAL value for a column with INTEGER affinity is inserted as the integer SQLite stores. Returns
     * the rows as inserted.
     */
    fun seed(file: File, key: ByteArray, orphans: Boolean = true): List<SeedRow> {
        val seeded = mutableListOf<SeedRow>()
        connect(file, key).use { c ->
            val tables = SchemaCompare.tables(c)
            val columns = tables.associateWith { SchemaCompare.columnNames(c, it).toSet() }
            val info = tables.associateWith { table -> SchemaCompare.query(c, "PRAGMA table_info($table)") }
            val integerColumns = info.mapValues { (_, cols) ->
                cols.filter { (it["type"] as String).contains("INT", ignoreCase = true) }.map { it["name"] as String }.toSet()
            }
            // From 14.sqm to the snapshot, history.last_read is NOT NULL: 14.sqm wrote 0 for never read
            val lastReadNotNull = info["history"]?.any { it["name"] == "last_read" && it["notnull"] == 1L } == true
            c.autoCommit = false
            for (row in MigrationSeed.rows) {
                if (!orphans && row in MigrationSeed.orphans) continue
                val tableColumns = columns[row.table] ?: continue
                val values = row.values.filterKeys { it in tableColumns }.mapValues { (column, v) ->
                    when {
                        v is Double && column in integerColumns.getValue(row.table) && v % 1.0 == 0.0 -> v.toLong()
                        v == null && row.table == "history" && column == "last_read" && lastReadNotNull -> 0L
                        else -> v
                    }
                }
                // Every column the table has at this version, so [MigrationSeed.expected] knows which a migration added
                val missing = tableColumns - values.keys
                check(missing.isEmpty()) { "The seed row ${row.values} of ${row.table} sets no value for $missing" }
                val names = values.keys.toList()
                c.prepareStatement(
                    "INSERT INTO ${row.table} (${names.joinToString()}) VALUES (${names.joinToString { "?" }})",
                ).use { st ->
                    names.forEachIndexed { i, name ->
                        when (val v = values[name]) {
                            is Blob -> st.setBytes(i + 1, v.bytes)
                            else -> st.setObject(i + 1, v)
                        }
                    }
                    st.executeUpdate()
                }
                seeded += SeedRow(row.table, values)
            }
            c.commit()
        }
        return seeded
    }

    fun userVersion(file: File, key: ByteArray): Long = connect(file, key).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun SqlPreparedStatement.bind(i: Int, v: Any?) {
        when (v) {
            null -> bindString(i, null)
            is Long -> bindLong(i, v)
            is Int -> bindLong(i, v.toLong())
            is Double -> bindDouble(i, v)
            is String -> bindString(i, v)
            is Blob -> bindBytes(i, v.bytes)
            is ByteArray -> bindBytes(i, v)
            else -> error("Cannot bind ${v::class}")
        }
    }
}

/** A row as inserted: the columns that existed at the seeded version. */
internal data class SeedRow(val table: String, val values: Map<String, Any?>)

/** A BLOB value, compared by content. */
internal class Blob(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is Blob && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "Blob(${bytes.decodeToString()})"

    companion object {
        fun of(text: String) = Blob(text.encodeToByteArray())
    }
}

/** Reads schemas and rows through JDBC, normalised for comparison. */
internal object SchemaCompare {

    fun tables(c: Connection): List<String> = query(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .map { it.getValue("name") as String }

    fun columnNames(c: Connection, table: String): List<String> = query(c, "PRAGMA table_info($table)").map { it.getValue("name") as String }

    /** Every row of [table], values normalised: integers as Long, reals as Double, BLOBs as [Blob]. */
    fun rows(c: Connection, table: String): List<Map<String, Any?>> = query(c, "SELECT * FROM $table")

    fun count(c: Connection, table: String): Long = query(c, "SELECT count(*) AS n FROM $table").single().getValue("n") as Long

    fun query(c: Connection, sql: String): List<Map<String, Any?>> = c.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val meta = rs.metaData
            buildList {
                while (rs.next()) {
                    add(
                        (1..meta.columnCount).associate { i ->
                            meta.getColumnLabel(i) to when (val v = rs.getObject(i)) {
                                is Int -> v.toLong()
                                is Short -> v.toLong()
                                is Float -> v.toDouble()
                                is ByteArray -> Blob(v)
                                else -> v
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * The schema as comparable lines, by object: each table's columns (in order, with type, NOT
     * NULL, default and primary key), foreign keys, AUTOINCREMENT and indexes (including the ones
     * UNIQUE makes); each index's, view's and trigger's SQL with comments and spacing removed.
     */
    fun schema(c: Connection): Map<String, String> {
        val result = sortedMapOf<String, String>()
        val objects = query(c, "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")
        for (o in objects) {
            val type = o["type"] as String
            val name = o["name"] as String
            val sql = o["sql"] as String?
            result["$type $name"] = when (type) {
                "table" -> tableDescription(c, name, sql.orEmpty())
                else -> normalise(sql.orEmpty())
            }
        }
        return result
    }

    private fun tableDescription(c: Connection, table: String, sql: String): String = buildString {
        query(c, "PRAGMA table_info($table)").forEach {
            appendLine("column ${it["cid"]} ${it["name"]} ${it["type"]} notnull=${it["notnull"]} default=${it["dflt_value"]} pk=${it["pk"]}")
        }
        query(c, "PRAGMA foreign_key_list($table)")
            .map { "foreign key ${it["from"]} -> ${it["table"]}.${it["to"]} on delete ${it["on_delete"]} on update ${it["on_update"]}" }
            .sorted().forEach(::appendLine)
        query(c, "PRAGMA index_list($table)").map { index ->
            val columns = query(c, "PRAGMA index_info(\"${index["name"]}\")").sortedBy { it["seqno"] as Long }.map { it["name"] }
            "index ${index["name"]} unique=${index["unique"]} origin=${index["origin"]} partial=${index["partial"]} $columns"
        }.sorted().forEach(::appendLine)
        if (sql.contains("AUTOINCREMENT", ignoreCase = true)) appendLine("autoincrement")
    }

    /** SQL without comments, with spacing and case normalised and `IF NOT EXISTS` dropped. */
    fun normalise(sql: String): String = sql.lines().joinToString(" ") { it.substringBefore("--") }
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*([(),;=<>])\\s*"), "$1")
        .replace(Regex("(?i)if not exists "), "")
        .trim()
        .lowercase()

    /** One line per difference between two [schema] maps. */
    fun differences(actual: Map<String, String>, expected: Map<String, String>): List<String> =
        (actual.keys + expected.keys).toSortedSet().mapNotNull { key ->
            val a = actual[key]
            val e = expected[key]
            when {
                a == null -> "missing $key"
                e == null -> "unexpected $key"
                a != e -> "different $key:\n    migrated: ${a.replace("\n", "\n              ")}\n    fresh:    ${e.replace("\n", "\n              ")}"
                else -> null
            }
        }
}
