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
 * The oldest schema the repository holds is SQLDelight's snapshot `data/src/commonMain/sqldelight/28.db`
 * (the schema before `28.sqm`; there is no snapshot or base `.sq` for versions 1 to 27, so those
 * migrations cannot be run from here). A database at version N is that snapshot's schema and rows
 * (the system category), copied into a new database encrypted as the desktop encrypts it, then
 * migrated with the generated migrations `28.sqm` to `(N-1).sqm`, with `PRAGMA user_version = N`.
 * [seed] then fills it with [MigrationSeed]'s rows as they existed at N.
 */
internal object OldDatabases {
    /** The version of the oldest schema snapshot in the repository. */
    const val SNAPSHOT_VERSION = 28L

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
     * category. [migrate] runs the migrations from the snapshot's version to [version]; the
     * self-checks pass one that skips a migration.
     */
    fun create(
        file: File,
        key: ByteArray,
        version: Long,
        migrate: (SqlDriver, Long, Long) -> Unit = { driver, from, to -> Database.Schema.migrate(driver, from, to) },
    ) {
        require(version in SNAPSHOT_VERSION..NEWEST) { "No schema for version $version" }
        check(!file.exists()) { "$file exists" }
        val (statements, rows) = readSnapshot(file.parentFile)
        JdbcSqliteDriver(url(file), DesktopDatabase.properties(key)).use { driver ->
            object : TransacterImpl(driver) {}.transaction {
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
                driver.execute(null, "PRAGMA user_version = $version", 0)
            }
        }
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
     * old Android builds left them). Returns the rows as inserted.
     */
    fun seed(file: File, key: ByteArray): List<SeedRow> {
        val seeded = mutableListOf<SeedRow>()
        connect(file, key).use { c ->
            val columns = SchemaCompare.tables(c).associateWith { SchemaCompare.columnNames(c, it).toSet() }
            c.autoCommit = false
            for (row in MigrationSeed.rows) {
                val tableColumns = columns[row.table] ?: continue
                val values = row.values.filterKeys { it in tableColumns }
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
