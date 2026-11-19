package zoukan.desktop.db

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat

/**
 * Old databases opened through the desktop's own start-up path ([DesktopDatabase.createDriver]:
 * the encrypted driver, the version check, the copy made before a migration, SQLDelight's
 * migrations and the trigger fix) end up with every row and the newest schema.
 *
 * The databases are built by [OldDatabases] from the version-1 schema in the test resources (proven
 * against the `28.db` snapshot by the cross-check below) or from that snapshot, and seeded with
 * [MigrationSeed]'s rows. The start-up path migrates 27 (the first after the trigger's versions), the
 * snapshot's (28), one in the middle (38) and the one before the newest (47), and older ones
 * through the system category's trigger: 4 (the oldest the migrations themselves can take), 14
 * (the one before `14.sqm` rebuilds history), 20 (before `20.sqm` makes the trigger), 21 and 26
 * (the trigger there, and `26.sqm` renaming a table, which makes SQLite check every trigger).
 * Versions 1 to 3 fail at `13.sqm` (pinned below). Every version from 1 is built and seeded.
 *
 * Which versions can occur: the desktop never opens an Android database (data arrives in
 * `.tachibk` backups), and its first release already had the newest schema, 48, so no desktop
 * database in the field is older. Every old version here is tested for completeness: the
 * migrations are Komikku's own, shared with the Android app, and a future migration runs after
 * them. The Android app does reach these versions, but with its own driver, which this test does
 * not cover.
 *
 * What a database that went through `14.sqm` keeps, unlike a new install (inherited from upstream;
 * pinned by [withoutHistoryLeftovers]): `history.last_read` is NOT NULL, and the old history table
 * stays as `history_temp`, a stale copy of the reading history as it was before `14.sqm`, which
 * clearing history does not clear. Every database built here below 28 went through it, as one made
 * by TachiyomiSY before SQLDelight did; one made by SQLDelight at 15 to 27 did not.
 */
class DatabaseMigrationTest {

    @TempDir
    lateinit var dir: File

    private val started = System.currentTimeMillis() / 1000

    private fun file(name: String = "zoukan.db") = File(dir, name)

    /** The newest schema as the desktop creates it on a new install, and its columns by table. */
    private val fresh: Pair<Map<String, String>, Map<String, List<String>>> by lazy {
        val key = OldDatabases.key(99)
        val file = File(dir, "fresh/zoukan.db").apply { parentFile.mkdirs() }
        DesktopDatabase.createDriver(file, OldDatabases.MapStore(key)).close()
        OldDatabases.connect(file, key).use { c ->
            SchemaCompare.schema(c) to SchemaCompare.tables(c).associateWith { SchemaCompare.columnNames(c, it) }
        }
    }

    /**
     * An old database at [version] seeded with [MigrationSeed.rows]; returns the rows as inserted.
     * Up to version 14 without the orphans, which [MigrationSeed.expected] does not follow through
     * `13.sqm` and `14.sqm` (the orphan cases of the failure tests do).
     */
    private fun oldDatabase(file: File, key: ByteArray, version: Long): List<SeedRow> {
        OldDatabases.create(file, key, version)
        return OldDatabases.seed(file, key, orphans = version > OldDatabases.HISTORY_PREFIX_VERSION)
    }

    /** Opens [file] as the desktop does at start-up and runs [block] on the driver. */
    private fun <T> open(file: File, key: ByteArray, block: (SqlDriver) -> T): T {
        val driver = DesktopDatabase.createDriver(file, OldDatabases.MapStore(key))
        return try {
            block(driver)
        } finally {
            driver.close()
        }
    }

    /** Every difference between [file]'s rows and what [MigrationSeed.expected] says they must be. */
    private fun rowDifferences(file: File, key: ByteArray, from: Long, seeded: List<SeedRow>): List<String> {
        val expected = MigrationSeed.expected(from, seeded, fresh.second)
        val differences = mutableListOf<String>()
        OldDatabases.connect(file, key).use { c ->
            val tables = SchemaCompare.tables(c)
            for ((table, rows) in expected) {
                if (table !in tables) {
                    differences += "missing table $table"
                    continue
                }
                val keyColumns = MigrationSeed.keyOf(table)
                val actual = SchemaCompare.rows(c, table).associateBy { row -> keyColumns.map { row[it] } }
                val wanted = rows.associateBy { row -> keyColumns.map { row[it] } }
                (wanted.keys - actual.keys).forEach { differences += "$table: row $it was lost" }
                (actual.keys - wanted.keys).forEach { differences += "$table: unexpected row $it ${actual[it]}" }
                for ((id, row) in wanted) {
                    val got = actual[id] ?: continue
                    (got.keys - row.keys).forEach { differences += "$table $id: unexpected column $it" }
                    for ((column, value) in row) {
                        val actualValue = got[column]
                        val matches = if (value === MigrationSeed.Now) {
                            actualValue is Long && actualValue in (started - 60)..(System.currentTimeMillis() / 1000 + 60)
                        } else {
                            actualValue == value
                        }
                        if (!matches) differences += "$table $id.$column: expected $value, was $actualValue"
                    }
                }
            }
        }
        return differences
    }

    /** Rows of [seeded] not in [file] exactly as written. */
    private fun seedDifferences(file: File, key: ByteArray, seeded: List<SeedRow>): List<String> =
        OldDatabases.connect(file, key).use { c ->
            seeded.groupBy { it.table }.flatMap { (table, rows) ->
                val keyColumns = MigrationSeed.keyOf(table, rows.first().values.keys)
                val actual = SchemaCompare.rows(c, table).associateBy { row -> keyColumns.map { row[it] } }
                rows.flatMap { row ->
                    val id = keyColumns.map { row.values[it] }
                    val got = actual[id] ?: return@flatMap listOf("$table $id: not inserted")
                    row.values.filter { (column, value) -> got[column] != value }.map { (column, value) -> "$table $id.$column: wrote $value, read ${got[column]}" }
                }
            }
        }

    /** Every difference between [file]'s schema and a new install's, but the pinned ones a database from [from] keeps. */
    private fun schemaDifferences(file: File, key: ByteArray, from: Long = OldDatabases.NEWEST): List<String> =
        OldDatabases.connect(file, key).use { c ->
            val schema = SchemaCompare.schema(c)
            // Every database built from version 1 went through 14.sqm
            val compared = if (from < OldDatabases.SNAPSHOT_VERSION) withoutHistoryLeftovers(schema) else schema
            SchemaCompare.differences(compared, fresh.first)
        }

    /**
     * [schema] without what `14.sqm` leaves behind, after checking it is there: it rebuilds `history`
     * with `last_read` NOT NULL (the `.sq` file, and so a new install, has it nullable) and renames
     * the old table to `history_temp` without dropping it.
     */
    private fun withoutHistoryLeftovers(schema: Map<String, String>): Map<String, String> {
        val history = schema.getValue("table history")
        history shouldContain "column 2 last_read INTEGER notnull=1 "
        schema.keys.filter { "history_temp" in it } shouldContainExactly listOf("table history_temp")
        return schema - "table history_temp" + ("table history" to history.replace("last_read INTEGER notnull=1 ", "last_read INTEGER notnull=0 "))
    }

    /** Every table's rows, for checking a database is unchanged. */
    private fun allRows(file: File, key: ByteArray): Map<String, List<String>> = OldDatabases.connect(file, key).use { c ->
        SchemaCompare.tables(c).associateWith { table -> SchemaCompare.rows(c, table).map { it.toString() }.sorted() }
    }

    /** The messages of [e] and its causes. */
    private fun messages(e: Throwable): String = generateSequence(e) { it.cause }.joinToString(" / ") { it.message.orEmpty() }

    @Test
    fun `the migrations the tests rebuild are the ones the schema runs`() {
        // N.sqm migrates version N to N + 1; the newest version is one past the last
        val numbers = OldDatabases.migrationFiles.map { it.nameWithoutExtension.toLong() }
        numbers shouldBe (1L..numbers.size).toList()
        Database.Schema.version shouldBe numbers.size + 1L
        // The rebuilt snapshot plus the migrations gives the newest schema (checked by the tests below)
        OldDatabases.snapshot.isFile shouldBe true
        OldDatabases.version1Statements().shouldNotBeEmpty()
    }

    @Test
    fun `the version-1 schema migrated to 28 is the 28 snapshot`() {
        // The proof of the version-1 schema: 1.sqm to 27.sqm, as generated, turn it into 28.db exactly
        // (columns in order with type, NOT NULL, default and primary key, foreign keys, indexes,
        // views, triggers and the system category's row), but for what 14.sqm leaves behind
        val key = OldDatabases.key(28)
        val fromVersion1 = file("from-1.db")
        val snapshot = file("snapshot.db")
        OldDatabases.create(fromVersion1, key, 28, fromVersion1 = true)
        OldDatabases.create(snapshot, key, 28)

        val (migrated, expected) = listOf(fromVersion1, snapshot).map { f -> OldDatabases.connect(f, key).use { SchemaCompare.schema(it) } }
        SchemaCompare.differences(withoutHistoryLeftovers(migrated), expected).shouldBeEmpty()
        allRows(fromVersion1, key).filterKeys { it != "history_temp" } shouldBe allRows(snapshot, key)
    }

    @Test
    fun `every table at every version has known rows`() {
        // So a table a new migration adds is seeded and checked too, and every column of every
        // version is set ([OldDatabases.seed] checks that)
        (fresh.second.keys - MigrationSeed.rows.map { it.table }.toSet()).shouldBeEmpty()
        for (version in 1..OldDatabases.NEWEST) {
            val key = OldDatabases.key(version.toInt())
            val file = file("seed-$version.db")
            OldDatabases.create(file, key, version)
            val tables = OldDatabases.connect(file, key).use { SchemaCompare.tables(it) }
            val seeded = OldDatabases.seed(file, key).map { it.table }.toSet()
            // history_temp: 14.sqm's leftover, which no code writes to
            (version to tables.filterNot { it in seeded || it == "history_temp" }) shouldBe (version to emptyList())
            file.delete()
        }
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(longs = [4, 14, 20, 21, 26, 27, 28, 38, 47])
    fun `an old database keeps every row and gets the newest schema`(version: Long) {
        val key = OldDatabases.key(version.toInt())
        val file = file()
        val seeded = oldDatabase(file, key, version)
        OldDatabases.userVersion(file, key) shouldBe version
        // The rows went in as written: no trigger changed them while seeding
        seedDifferences(file, key, seeded).shouldBeEmpty()

        openAndCheck(file, key, version, seeded, copiedAt = version)
    }

    /**
     * A row whose parent is gone, at 13 (a chapter) or 14 (a reading history entry), goes through
     * `13.sqm` and `14.sqm` (the desktop migrates with foreign keys off, as Android's driver does);
     * `47.sqm` then removes it with its foreign key's own action. Every other row is kept.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["orphan chapter at 13", "orphan history at 14"])
    fun `an old row whose parent is gone does not stop the migration`(case: String) {
        val version = case.substringAfterLast(' ').toLong()
        val key = OldDatabases.key(60 + version.toInt())
        val file = file()
        val seeded = oldDatabase(file, key, version)
        OldDatabases.connect(file, key).use { c ->
            c.createStatement().use {
                when (case) {
                    "orphan chapter at 13" -> it.execute("INSERT INTO chapters SELECT 98, 999, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload FROM chapters WHERE _id = 10")
                    "orphan history at 14" -> it.execute("INSERT INTO history VALUES (5, 9999, 1, 1)")
                }
            }
        }

        open(file, key) { }

        OldDatabases.userVersion(file, key) shouldBe OldDatabases.NEWEST
        // Every seeded row as expected, and the orphan gone from its table (an unexpected row otherwise)
        rowDifferences(file, key, version, seeded).shouldBeEmpty()
        schemaDifferences(file, key, version).shouldBeEmpty()
    }

    /**
     * Opens [file], seeded at [version], through the start-up path and checks the app reads it,
     * every row, the schema and the copy made before the migration (of the database at [copiedAt]).
     */
    private fun openAndCheck(file: File, key: ByteArray, version: Long, seeded: List<SeedRow>, copiedAt: Long) {
        open(file, key) { driver ->
            val db = DesktopDatabase.createDatabase(driver)
            // The app's own queries and column adapters read the migrated rows
            db.mangasQueries.getMangaById(1).executeAsOne().let { manga ->
                manga.title shouldBe "Harbor Lights"
                manga.genre shouldBe listOf("Drama", "Sea")
                manga.description shouldBe "A keeper of lights.\nSecond line, ünïcödé."
                manga.favorite shouldBe true
                manga.chapter_flags shouldBe 770L
                manga.notes shouldBe if (version >= 39) "Seeded note" else ""
                manga.memo["pinned"] shouldBe if (version >= 47) JsonPrimitive(true) else null
            }
            db.chaptersQueries.getChaptersByMangaId(1, 0, 0, 0).executeAsList().map { it._id }.sorted() shouldContainExactly listOf(10L, 11L)
            db.chaptersQueries.getChaptersByMangaId(1, 0, 0, 0).executeAsList().first { it._id == 11L }.scanlator shouldBe
                if (version <= 29) "Padded Group" else "  Padded Group  "
            db.historyQueries.getHistoryByMangaId(1).executeAsList().map { it._id } shouldContainExactly listOf(1L)
            db.manga_syncQueries.getTracksByMangaId(1).executeAsList().map { it.sync_id }.sorted() shouldContainExactly listOf(2L, 7L)
            db.categoriesQueries.getCategories().executeAsList().map { it.name } shouldContainExactly listOf("", "Reading", "Later")
            db.libraryViewQueries.library().executeAsList().map { it._id }.toSet() shouldBe setOf(1L, 4L)
            db.extension_storeQueries.getAll().executeAsList().map { it.index_url }.toSet() shouldBe buildSet {
                if (version >= 47) add("https://example.org/store2/index.min.json")
                if (version in 33..46) add("https://example.org/store/repo.json")
            }
        }

        OldDatabases.userVersion(file, key) shouldBe OldDatabases.NEWEST
        rowDifferences(file, key, version, seeded).shouldBeEmpty()
        schemaDifferences(file, key, version).shouldBeEmpty()
        if (version < OldDatabases.SNAPSHOT_VERSION) {
            // 14.sqm's leftover still holds the reading history as it was before 14.sqm (none, if seeded after it)
            val stale = if (version <= OldDatabases.HISTORY_PREFIX_VERSION) seeded.count { it.table == "history" }.toLong() else 0L
            OldDatabases.connect(file, key).use { c -> SchemaCompare.count(c, "history_temp") shouldBe stale }
        }
        // The copy made before the migration is the old database, whole
        val copy = DesktopDatabase.preMigrationCopy(file, OldDatabases.NEWEST)
        OldDatabases.userVersion(copy, key) shouldBe copiedAt
        OldDatabases.connect(copy, key).use { c -> SchemaCompare.count(c, "chapters") shouldBe seeded.count { it.table == "chapters" }.toLong() }
    }

    /**
     * Migrations nobody can run, inherited from upstream: `13.sqm` reads `merged.manga_source`,
     * which `3.sqm` named `COL_MANGA_SOURCE`, so nothing from before 4 migrates (none exists in the
     * field; see the class comment). The open fails, nothing is changed, and the copy made before
     * the migration is kept.
     */
    @ParameterizedTest(name = "version {0}")
    @ValueSource(longs = [1, 3])
    fun `a migration that fails leaves the old database as it was`(version: Long) {
        val key = OldDatabases.key(40 + version.toInt())
        val file = file()
        oldDatabase(file, key, version)
        val before = allRows(file, key)
        val schemaBefore = OldDatabases.connect(file, key).use { SchemaCompare.schema(it) }

        val e = shouldThrowAny { open(file, key) { } }

        messages(e) shouldContain "no such column: manga_source"
        OldDatabases.userVersion(file, key) shouldBe version
        allRows(file, key) shouldBe before
        OldDatabases.connect(file, key).use { SchemaCompare.schema(it) } shouldBe schemaBefore
        val copy = DesktopDatabase.preMigrationCopy(file, OldDatabases.NEWEST)
        OldDatabases.userVersion(copy, key) shouldBe version
        allRows(copy, key) shouldBe before
    }

    @Test
    fun `an empty old database migrates to the newest schema with only the system category`() {
        val key = OldDatabases.key(1)
        val file = file()
        OldDatabases.create(file, key, 38)

        open(file, key) { }

        OldDatabases.userVersion(file, key) shouldBe OldDatabases.NEWEST
        schemaDifferences(file, key).shouldBeEmpty()
        OldDatabases.connect(file, key).use { c ->
            SchemaCompare.tables(c).filter { it != "categories" }.forEach { table -> (table to SchemaCompare.count(c, table)) shouldBe (table to 0L) }
            SchemaCompare.rows(c, "categories").map { it["_id"] } shouldContainExactly listOf(0L)
        }
    }

    @Test
    fun `a database at the newest version is opened without a migration or a copy`() {
        val key = OldDatabases.key(2)
        val file = file()
        // Made by this build, then filled
        DesktopDatabase.createDriver(file, OldDatabases.MapStore(key)).close()
        val seeded = OldDatabases.seed(file, key)

        open(file, key) { }

        OldDatabases.userVersion(file, key) shouldBe OldDatabases.NEWEST
        dir.listFiles().orEmpty().map { it.name }.filter { ".pre-v" in it }.shouldBeEmpty()
        // Nothing removed or changed, orphans included: no migration ran
        rowDifferences(file, key, OldDatabases.NEWEST, seeded).shouldBeEmpty()
        schemaDifferences(file, key).shouldBeEmpty()
    }

    @Test
    fun `a database newer than this build is refused and left byte-identical`() {
        val key = OldDatabases.key(3)
        val file = file()
        oldDatabase(file, key, 47)
        OldDatabases.connect(file, key).use { c -> c.createStatement().use { it.execute("PRAGMA user_version = ${OldDatabases.NEWEST + 1}") } }
        val before = file.readBytes()

        val e = shouldThrow<DesktopDatabase.TooNewException> { open(file, key) { } }

        e.version shouldBe OldDatabases.NEWEST + 1
        file.readBytes().contentEquals(before) shouldBe true
        dir.listFiles().orEmpty().map { it.name }.filter { ".pre-v" in it }.shouldBeEmpty()
    }

    @Test
    fun `an old database opened with the wrong key fails and is left byte-identical`() {
        val key = OldDatabases.key(4)
        val file = file()
        oldDatabase(file, key, 38)
        val before = file.readBytes()

        shouldThrowAny { open(file, OldDatabases.key(5)) { } }

        file.readBytes().contentEquals(before) shouldBe true
        dir.listFiles().orEmpty().map { it.name }.filter { ".pre-v" in it }.shouldBeEmpty()
        // Still the old database, readable with its own key
        OldDatabases.userVersion(file, key) shouldBe 38
    }

    // Self-checks: the comparisons above do notice a lost row, a changed value and a skipped migration

    @Test
    fun `self-check - a lost row and a changed value are reported`() {
        val key = OldDatabases.key(6)
        val file = file()
        val seeded = oldDatabase(file, key, 38)
        open(file, key) { }
        rowDifferences(file, key, 38, seeded).shouldBeEmpty()

        OldDatabases.connect(file, key).use { c ->
            c.createStatement().use {
                it.execute("DELETE FROM history WHERE _id = 1")
                it.execute("UPDATE chapters SET name = 'Renamed' WHERE _id = 10")
            }
        }

        val differences = rowDifferences(file, key, 38, seeded)
        differences shouldContain "history: row [1] was lost"
        differences.any { it.startsWith("chapters [10].name: expected Ch. 1 - Arrival, was Renamed") } shouldBe true
    }

    @Test
    fun `two favourites of the same gallery stop 26 sqm`() {
        // Inherited from upstream: 26.sqm keys favourites by gid and token, and the copy fails on
        // a duplicate; nothing is changed
        val key = OldDatabases.key(26)
        val file = file()
        oldDatabase(file, key, 26)
        OldDatabases.connect(file, key).use { c ->
            c.createStatement().use { it.execute("INSERT INTO eh_favorites (_id, title, gid, token, category) VALUES (2, 'Same gallery', '100', 'abc', 0)") }
        }
        val before = allRows(file, key)

        val e = shouldThrowAny { OldDatabases.migrate(file, key, 26, 27) }

        messages(e) shouldContain "UNIQUE constraint failed: eh_favorites.gid, eh_favorites.token"
        OldDatabases.userVersion(file, key) shouldBe 26
        allRows(file, key) shouldBe before
    }

    @Test
    fun `self-check - a migration skipped before the snapshot is reported`() {
        val key = OldDatabases.key(8)
        val file = file()
        // Built from version 1 to 28 with 15.sqm (the sources table) left out
        OldDatabases.create(file, key, 28, fromVersion1 = true) { driver, from, to ->
            if (from < 15 && to > 15) {
                Database.Schema.migrate(driver, from, 15)
                Database.Schema.migrate(driver, 16, to)
            } else {
                Database.Schema.migrate(driver, from, to)
            }
        }
        val snapshot = file("snapshot.db")
        OldDatabases.create(snapshot, key, 28)

        val (migrated, expected) = listOf(file, snapshot).map { f -> OldDatabases.connect(f, key).use { SchemaCompare.schema(it) } }
        SchemaCompare.differences(withoutHistoryLeftovers(migrated), expected) shouldContain "missing table sources"
    }

    @Test
    fun `self-check - a skipped migration is reported`() {
        val key = OldDatabases.key(7)
        val file = file()
        // Migrated to the newest version with 45.sqm (the performance indexes) left out
        OldDatabases.create(file, key, OldDatabases.NEWEST) { driver, from, to ->
            Database.Schema.migrate(driver, from, 45)
            Database.Schema.migrate(driver, 46, to)
        }

        val differences = schemaDifferences(file, key)

        differences.shouldNotBeEmpty()
        differences shouldContain "missing index idx_mangas_source"
        differences.joinToString("\n") shouldContain "idx_excluded_scanlators_manga_id_scanlator"
    }
}

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
     * literals) rejects when an `ALTER TABLE … RENAME` checks the schema (`26.sqm`). The desktop's
     * start-up path rewrites it single-quoted before migrating and right after `20.sqm`, as the
     * builder here does.
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
     * Migrates [file] (with rows) from [from] to [to] as [create] does below 28, past the trigger
     * (see [TRIGGER_VERSION]), without the start-up path's checks and copy.
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

/**
 * Known rows for every table of the schema, written into old databases by [OldDatabases.seed]
 * (each with the columns its version has), and what each must be after migrating to the newest
 * version ([expected]).
 *
 * The values are invented. They include what old Android builds could leave behind: rows whose
 * parent is gone (foreign keys were not always enforced; `47.sqm` removes them), a scanlator with
 * spaces around it (`29.sqm` trims it), a negative score for tracker 7 (`30.sqm`), tracker ids in
 * URLs for trackers 3 and 9 (`42.sqm`) and an extension repository (`46.sqm` moves it to the
 * extension stores). For the migrations before 28: a whole number of chapters read in a tracker's
 * INTEGER column (`8.sqm` makes it REAL), a chapter with no upload date (`9.sqm` takes the fetch
 * date), empty read times (`14.sqm` makes them 0), the old `-1` placeholders and a category's
 * custom entry order (`18.sqm` resets them), an empty scanlator filter (`21.sqm` makes it NULL)
 * and favourite alternatives (`27.sqm` drops the table).
 *
 * A row may name columns of several versions: `history` has `history_id` and the other
 * `history_` names up to version 14 and `_id` after, and `eh_favorites` has `_id` up to 26.
 * [OldDatabases.seed] keeps the ones the version has.
 *
 * Rows of a table are inserted in list order. `mangas_categories` comes before `mangas`: its insert
 * trigger (from `31.sqm`) would otherwise bump the entry's version and modified time.
 */
internal object MigrationSeed {

    /** A column a migration set to the current time (`strftime('%s', 'now')`), in seconds. */
    object Now {
        override fun toString() = "<now>"
    }

    private const val MERGED_SOURCE = 6969L
    private val SIGNING_A = "aa".repeat(32)
    private val SIGNING_B = "bb".repeat(32)

    private fun row(table: String, vararg values: Pair<String, Any?>) = SeedRow(table, linkedMapOf(*values))

    private val orphanRows = mutableSetOf<SeedRow>()

    /** Marks [row] as one whose parent is gone. */
    private fun orphan(row: SeedRow) = row.also { orphanRows += it }

    private fun history(id: Long, chapterId: Long, lastRead: Long?, timeRead: Long?) = row(
        "history",
        "_id" to id, "chapter_id" to chapterId, "last_read" to lastRead, "time_read" to (timeRead ?: 0L),
        // Up to version 14 (14.sqm renames them; its NOT NULL time_read had no such rule before)
        "history_id" to id, "history_chapter_id" to chapterId, "history_last_read" to lastRead, "history_time_read" to timeRead,
    )

    private fun manga(id: Long, source: Long, url: String, title: String, favorite: Long, vararg extra: Pair<String, Any?>) = row(
        "mangas",
        "_id" to id, "source" to source, "url" to url, "artist" to null, "author" to null, "description" to null,
        "genre" to null, "title" to title, "status" to 0L, "thumbnail_url" to null, "favorite" to favorite,
        "last_update" to null, "next_update" to null, "initialized" to 0L, "viewer" to 0L, "chapter_flags" to 0L,
        "cover_last_modified" to 0L, "date_added" to 0L, "filtered_scanlators" to null, "update_strategy" to 0L,
        "calculate_interval" to 0L,
        // Added by later migrations
        "last_modified_at" to 1_700_000_000L + id, "favorite_modified_at" to null, "version" to 0L, "is_syncing" to 0L,
        "notes" to "", "memo" to Blob.of("{}"),
    ).let { it.copy(values = it.values + extra) }

    private fun chapter(id: Long, mangaId: Long, url: String, name: String, scanlator: String?, vararg extra: Pair<String, Any?>) = row(
        "chapters",
        "_id" to id, "manga_id" to mangaId, "url" to url, "name" to name, "scanlator" to scanlator, "read" to 0L,
        "bookmark" to 0L, "last_page_read" to 0L, "chapter_number" to 1.0, "source_order" to 0L, "date_fetch" to 0L,
        "date_upload" to 0L,
        // Added by later migrations
        "last_modified_at" to 1_700_000_000L + id, "version" to 0L, "is_syncing" to 0L, "memo" to Blob.of("{}"),
    ).let { it.copy(values = it.values + extra) }

    private fun track(id: Long, mangaId: Long, syncId: Long, remoteId: Long, libraryId: Long?, score: Double, url: String, vararg extra: Pair<String, Any?>) = row(
        "manga_sync",
        "_id" to id, "manga_id" to mangaId, "sync_id" to syncId, "remote_id" to remoteId, "library_id" to libraryId,
        "title" to "Tracked $id", "last_chapter_read" to 0.0, "total_chapters" to 0L, "status" to 1L, "score" to score,
        "remote_url" to url, "start_date" to 0L, "finish_date" to 0L, "private" to 0L,
    ).let { it.copy(values = it.values + extra) }

    private fun merged(id: Long, mergeId: Long, mangaId: Long?, mangaUrl: String, source: Long, info: Long) = row(
        "merged",
        "_id" to id, "info_manga" to info, "get_chapter_updates" to 1L, "chapter_sort_mode" to id % 3, "chapter_priority" to id,
        "download_chapters" to 1L, "merge_id" to mergeId, "merge_url" to "merged-1", "manga_id" to mangaId,
        "manga_url" to mangaUrl, "manga_source" to source,
    )

    val rows: List<SeedRow> = listOf(
        row("sources", "_id" to 1001L, "lang" to "en", "name" to "Northwind Comics"),
        row("sources", "_id" to 1002L, "lang" to "ja", "name" to "Paper Lantern"),

        // A custom entry order, which 18.sqm clears
        row("categories", "_id" to 1L, "name" to "Reading", "sort" to 1L, "flags" to 64L, "manga_order" to "3/1", "hidden" to 1L),
        row("categories", "_id" to 2L, "name" to "Later", "sort" to 2L, "flags" to 0L, "manga_order" to "", "hidden" to 0L),

        row("mangas_categories", "_id" to 1L, "manga_id" to 1L, "category_id" to 1L),
        row("mangas_categories", "_id" to 2L, "manga_id" to 1L, "category_id" to 2L),
        // Orphans: the entry, or the category, is gone
        orphan(row("mangas_categories", "_id" to 3L, "manga_id" to 999L, "category_id" to 1L)),
        orphan(row("mangas_categories", "_id" to 4L, "manga_id" to 1L, "category_id" to 999L)),

        manga(
            1, 1001, "/series/harbor-lights", "Harbor Lights", 1,
            "artist" to "Ada Artist", "author" to "Ben Writer", "description" to "A keeper of lights.\nSecond line, ünïcödé.",
            "genre" to "Drama, Sea", "status" to 1L, "thumbnail_url" to "https://example.org/c/1.jpg",
            "last_update" to 1_700_000_000_000L, "next_update" to 1_700_086_400_000L, "initialized" to 1L, "viewer" to 19L,
            "chapter_flags" to 770L, "cover_last_modified" to 1_700_000_000_001L, "date_added" to 1_690_000_000_000L,
            "calculate_interval" to 7L,
            "last_modified_at" to 1_700_000_002L, "favorite_modified_at" to 1_700_000_003L, "version" to 5L,
            "notes" to "Seeded note", "memo" to Blob.of("""{"pinned":true}"""),
        ),
        manga(
            3,
            1002,
            "/series/paper-days",
            "Paper Days",
            0,
            "update_strategy" to 1L,
            "filtered_scanlators" to "Bad Scans",
            "last_modified_at" to 1_700_000_010L,
            // The -1 placeholders old builds wrote, which 18.sqm resets to 0
            "viewer" to -1L, "chapter_flags" to -1L, "last_update" to -1L, "cover_last_modified" to -1L, "date_added" to -1L,
        ),
        // An empty scanlator filter, which 21.sqm makes NULL
        manga(4, MERGED_SOURCE, "merged-1", "Harbor Lights", 1, "date_added" to 1_695_000_000_000L, "filtered_scanlators" to ""),

        chapter(
            10, 1, "/c/10", "Ch. 1 - Arrival", "Moonlit Scans",
            "read" to 1L, "bookmark" to 1L, "last_page_read" to 17L, "source_order" to 2L,
            "date_fetch" to 1_700_000_100_000L, "date_upload" to 1_699_000_000_000L,
            "last_modified_at" to 1_700_000_004L, "version" to 2L, "memo" to Blob.of("""{"note":"x"}"""),
        ),
        chapter(11, 1, "/c/11", "Ch. 1.5", "  Padded Group  ", "chapter_number" to 1.5, "last_page_read" to 4L, "source_order" to 1L),
        // No upload date, which 9.sqm sets to the fetch date
        chapter(12, 3, "/p/12", "Chapter 1", null, "read" to 1L, "date_fetch" to 1_700_000_200_000L),
        // Orphan: its entry is gone
        orphan(chapter(99, 999, "/gone/99", "Gone", null)),

        history(1, 10, 1_700_001_000_000L, 600_000L),
        // Never read: no read time, which 14.sqm makes 0
        history(2, 12, null, null),
        // Orphans: the chapter of an orphan chapter, and a chapter that is gone
        orphan(history(3, 99, 1L, 1L)),
        orphan(history(4, 9999, 1L, 1L)),

        track(
            1, 1, 2, 4242, 77, 85.0, "https://example.org/track/4242",
            "title" to "Harbor Lights (tracker)", "last_chapter_read" to 1.0, "total_chapters" to 20L,
            "start_date" to 1_700_000_000_000L, "private" to 1L,
        ),
        // Tracker 7 with a negative score; tracker 3 with its id only in the URL
        track(2, 1, 7, 31, null, -1.0, "https://example.org/t7/31"),
        track(3, 3, 3, 5, 6, 0.0, "https://example.org/manga/12345"),
        orphan(track(4, 999, 2, 1, null, 0.0, "https://example.org/gone")),

        row("excluded_scanlators", "manga_id" to 1L, "scanlator" to "Bad Scans"),
        orphan(row("excluded_scanlators", "manga_id" to 999L, "scanlator" to "Gone")),

        row("saved_search", "_id" to 1L, "source" to 1001L, "name" to "Completed", "query" to "action", "filters_json" to "[]"),
        row("feed_saved_search", "_id" to 1L, "source" to 1001L, "saved_search" to 1L, "global" to 0L, "feed_order" to 3L),
        row("feed_saved_search", "_id" to 2L, "source" to 1002L, "saved_search" to null, "global" to 1L, "feed_order" to 4L),
        orphan(row("feed_saved_search", "_id" to 3L, "source" to 1001L, "saved_search" to 77L, "global" to 0L, "feed_order" to 5L)),

        merged(1, mergeId = 4, mangaId = 4, mangaUrl = "merged-1", source = MERGED_SOURCE, info = 1),
        merged(2, mergeId = 4, mangaId = 1, mangaUrl = "/series/harbor-lights", source = 1001, info = 0),
        // A member whose entry is gone (kept, without it) and a merge whose entry is gone (removed)
        orphan(merged(3, mergeId = 4, mangaId = 555, mangaUrl = "/gone", source = 1002, info = 0)),
        orphan(merged(4, mergeId = 888, mangaId = 1, mangaUrl = "/series/harbor-lights", source = 1001, info = 0)),

        row("search_metadata", "manga_id" to 1L, "uploader" to "uploader", "extra" to """{"k":1}""", "indexed_extra" to "idx", "extra_version" to 2L),
        orphan(row("search_metadata", "manga_id" to 999L, "uploader" to null, "extra" to "{}", "indexed_extra" to null, "extra_version" to 1L)),
        row("search_tags", "_id" to 1L, "manga_id" to 1L, "namespace" to "genre", "name" to "sea", "type" to 1L),
        orphan(row("search_tags", "_id" to 2L, "manga_id" to 999L, "namespace" to null, "name" to "gone", "type" to 0L)),
        row("search_titles", "_id" to 1L, "manga_id" to 1L, "title" to "Alt Title", "type" to 0L),
        orphan(row("search_titles", "_id" to 2L, "manga_id" to 999L, "title" to "Gone", "type" to 0L)),

        // _id: up to version 26 (26.sqm keys the table by gid and token)
        row("eh_favorites", "_id" to 1L, "gid" to "100", "token" to "abc", "title" to "Favorite Title", "category" to 2L),
        row("eh_favorites_alternatives", "id" to 1L, "gid" to "100", "token" to "abc", "otherGid" to "101", "otherToken" to "def"),

        row("libraryUpdateErrorMessage", "_id" to 1L, "message" to "Timed out"),
        row("libraryUpdateError", "_id" to 1L, "manga_id" to 1L, "message_id" to 1L, "last_update" to 1_700_000_500L),

        row(
            "extension_repos",
            "base_url" to "https://example.org/store",
            "name" to "Example store",
            "short_name" to "EX",
            "website" to "https://example.org",
            "signing_key_fingerprint" to SIGNING_A,
        ),
        row(
            "extension_store",
            "index_url" to "https://example.org/store2/index.min.json", "name" to "Second store", "badge_label" to "S2",
            "signing_key" to SIGNING_B, "contact_website" to "https://example.org", "contact_discord" to null,
            "is_legacy" to 0L, "extension_list_url" to "https://example.org/store2/index.pb",
        ),
    )

    /** The rows [OldDatabases.seed] leaves out when asked for none whose parent is gone. */
    val orphans: Set<SeedRow> get() = orphanRows

    /**
     * The columns that identify a row of each table, in the newest schema, or in the one whose
     * [columns] are given (`history` before 15 and `merged` before 4 name them differently).
     */
    fun keyOf(table: String, columns: Set<String>? = null): List<String> = when {
        table == "history" && columns != null && "history_id" in columns -> listOf("history_id")
        table == "merged" && columns != null && "_id" !in columns -> listOf("merge_id", "manga_id")
        else -> newestKeyOf(table)
    }

    private fun newestKeyOf(table: String): List<String> = when (table) {
        "search_metadata" -> listOf("manga_id")
        "excluded_scanlators" -> listOf("manga_id", "scanlator")
        "eh_favorites" -> listOf("gid", "token")
        "eh_favorites_alternatives" -> listOf("id")
        "extension_store" -> listOf("index_url")
        "extension_repos" -> listOf("base_url")
        else -> listOf("_id")
    }

    /**
     * Every table's rows after [seeded] (inserted at version [from]) was migrated to the newest
     * version, with every column of the newest schema ([newestColumns]). Each seeded row sets every
     * column its version has ([OldDatabases.seed] checks this), so a column missing from it is one a
     * later migration added: it takes the value that migration gives it. A column with no rule here
     * fails, so a new migration that adds a column must say what old rows get.
     */
    fun expected(from: Long, seeded: List<SeedRow>, newestColumns: Map<String, List<String>>): Map<String, List<Map<String, Any?>>> {
        val tables = newestColumns.keys.associateWith { mutableListOf<MutableMap<String, Any?>>() }
        // The system category: every schema from 21 has it, and 20.sqm adds it to older ones
        tables.getValue("categories") += linkedMapOf<String, Any?>(
            "_id" to 0L,
            "name" to "",
            "sort" to -1L,
            "flags" to 0L,
            "manga_order" to "",
            "hidden" to 0L,
        )
        for (seededRow in seeded) {
            val row = renamed(seededRow)
            if (row.table == "extension_repos") {
                // 46.sqm: every repository becomes a legacy store
                if (from <= 46) {
                    tables.getValue("extension_store") += mutableMapOf(
                        "index_url" to "${row.values["base_url"]}/repo.json",
                        "name" to row.values["name"],
                        "badge_label" to (row.values["short_name"] ?: row.values["name"]),
                        "signing_key" to row.values["signing_key_fingerprint"],
                        "contact_website" to row.values["website"],
                        "contact_discord" to null,
                        "is_legacy" to 1L,
                        "extension_list_url" to null,
                    )
                }
                continue
            }
            val columns = newestColumns[row.table] ?: error("The newest schema has no table ${row.table}")
            tables.getValue(row.table) += columns.associateWithTo(linkedMapOf()) { column ->
                if (column in row.values) row.values[column] else added(row.table, column, row.values)
            }
        }
        transform(from, tables)
        if (from <= 47) removeOrphans(tables)
        return tables
    }

    /** A row seeded before 14.sqm, with the `history` column names it gave. */
    private fun renamed(row: SeedRow): SeedRow =
        if (row.table == "history" && "history_id" in row.values) {
            SeedRow(row.table, row.values.mapKeys { (column, _) -> if (column == "history_id") "_id" else column.removePrefix("history_") })
        } else {
            row
        }

    private fun added(table: String, column: String, row: Map<String, Any?>): Any? = when ("$table.$column") {
        // 1.sqm, 5.sqm, 7.sqm (its DEFAULT 0, kept by 13.sqm's copy)
        "mangas.cover_last_modified" -> 0L
        "mangas.filtered_scanlators" -> null
        "mangas.next_update" -> 0L
        // 2.sqm's value depends on other rows, and a database that old fails at 13.sqm on this build
        "mangas.date_added" -> error("No expectation for mangas.date_added: a database from before 2.sqm cannot be migrated")
        // 22.sqm, 25.sqm
        "mangas.update_strategy", "mangas.calculate_interval" -> 0L
        // 28.sqm
        "mangas.last_modified_at", "chapters.last_modified_at" -> Now
        "mangas.favorite_modified_at" -> if (row["favorite"] == 1L) Now else null
        // 31.sqm
        "mangas.version", "mangas.is_syncing", "chapters.version", "chapters.is_syncing" -> 0L
        // 33.sqm: the feed order starts as the row's id
        "feed_saved_search.feed_order" -> row["_id"]
        // 34.sqm, 36.sqm, 38.sqm, 39.sqm
        "categories.hidden" -> 0L
        "manga_sync.private" -> 0L
        "mangas.notes" -> ""
        "libraryUpdateError.last_update" -> 0L
        // 46.sqm
        "mangas.memo", "chapters.memo" -> Blob.of("{}")
        else -> error("No expectation for $table.$column on rows from before the migration that added it")
    }

    /** What the data migrations do to rows they apply to. */
    private fun transform(from: Long, tables: Map<String, MutableList<MutableMap<String, Any?>>>) {
        // 8.sqm rebuilds manga_sync with last_chapter_read REAL; the INTEGER values become reals
        if (from <= 8) {
            tables.getValue("manga_sync").forEach { it["last_chapter_read"] = (it["last_chapter_read"] as Number).toDouble() }
        }
        // 9.sqm: a chapter with no upload date takes its fetch date
        if (from <= 9) {
            tables.getValue("chapters").filter { it["date_upload"] == 0L }.forEach { it["date_upload"] = it["date_fetch"] }
        }
        // 14.sqm copies history with coalesce(…, 0) into the renamed columns
        if (from <= 14) {
            tables.getValue("history").forEach { history ->
                history["last_read"] = history["last_read"] ?: 0L
                history["time_read"] = history["time_read"] ?: 0L
            }
        }
        // 18.sqm: the -1 placeholders become 0, and every category's custom entry order is cleared
        // (the system category comes later, from 20.sqm, with an empty order)
        if (from <= 18) {
            tables.getValue("mangas").forEach { manga ->
                for (column in listOf("chapter_flags", "viewer", "date_added", "cover_last_modified", "last_update")) {
                    if (manga[column] == -1L) manga[column] = 0L
                }
            }
            tables.getValue("categories").forEach { it["manga_order"] = "" }
        }
        // 21.sqm: an empty scanlator filter becomes NULL
        if (from <= 21) {
            tables.getValue("mangas").filter { it["filtered_scanlators"] == "" }.forEach { it["filtered_scanlators"] = null }
        }
        // 27.sqm drops and recreates eh_favorites_alternatives, with its rows
        if (from <= 27) tables.getValue("eh_favorites_alternatives").clear()
        // 29.sqm trims scanlators; the update fires 28.sqm's trigger, which sets last_modified_at
        if (from <= 29) {
            tables.getValue("chapters").filter { it["scanlator"] != null }.forEach {
                it["scanlator"] = (it["scanlator"] as String).trim()
                it["last_modified_at"] = Now
            }
        }
        // 30.sqm: no negative scores for tracker 7
        if (from <= 30) {
            tables.getValue("manga_sync").filter { it["sync_id"] == 7L }.forEach { it["score"] = maxOf(it["score"] as Double, 0.0) }
        }
        // 42.sqm: tracker 3 keeps its old remote id as the library id; 3 and 9 take the id from the URL
        if (from <= 42) {
            tables.getValue("manga_sync").forEach { track ->
                if (track["sync_id"] == 3L) track["library_id"] = track["remote_id"]
                if (track["sync_id"] == 3L || track["sync_id"] == 9L) {
                    track["remote_id"] = (track["remote_url"] as String).substringAfterLast('/').toLong()
                }
            }
        }
    }

    /** 47.sqm: the rows whose parent is gone, removed with each foreign key's own ON DELETE action. */
    private fun removeOrphans(tables: Map<String, MutableList<MutableMap<String, Any?>>>) {
        fun ids(table: String, column: String = "_id") = tables.getValue(table).map { it[column] }.toSet()
        val mangas = ids("mangas")
        tables.getValue("chapters").removeAll { it["manga_id"] !in mangas }
        val chapters = ids("chapters")
        tables.getValue("history").removeAll { it["chapter_id"] !in chapters }
        val categories = ids("categories") + 0L
        tables.getValue("mangas_categories").removeAll { it["manga_id"] !in mangas || it["category_id"] !in categories }
        for (table in listOf("manga_sync", "excluded_scanlators", "search_metadata", "search_tags", "search_titles")) {
            tables.getValue(table).removeAll { it["manga_id"] !in mangas }
        }
        tables.getValue("merged").removeAll { it["merge_id"] !in mangas }
        tables.getValue("merged").filter { it["manga_id"] != null && it["manga_id"] !in mangas }.forEach { it["manga_id"] = null }
        val searches = ids("saved_search")
        tables.getValue("feed_saved_search").removeAll { it["saved_search"] != null && it["saved_search"] !in searches }
    }
}
