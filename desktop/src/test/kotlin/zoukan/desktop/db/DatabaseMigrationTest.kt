package zoukan.desktop.db

import app.cash.sqldelight.db.SqlDriver
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
import java.io.File

/**
 * Old databases opened through the desktop's own start-up path ([DesktopDatabase.createDriver]:
 * the encrypted driver, the version check, the copy made before a migration, SQLDelight's
 * migrations and the trigger fix) end up with every row and the newest schema.
 *
 * The databases are built by [OldDatabases] from the version-1 schema in the test resources (proven
 * against the `28.db` snapshot by the cross-check below) or from that snapshot, and seeded with
 * [MigrationSeed]'s rows. The start-up path migrates the oldest version it can (27), the
 * snapshot's (28), one in the middle (38) and the one before the newest (47). It cannot migrate
 * anything older (pinned below: 1 to 3 fail at `13.sqm`, 4 to 26 at `26.sqm` on the system
 * category's trigger), so 4 (the oldest the migrations themselves can take) and 14 (the one
 * before `14.sqm` rebuilds history) are migrated to 27 the way [OldDatabases] builds databases,
 * then opened. Every version from 1 is built and seeded.
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
     * Up to version 14 without the orphans: with foreign keys on, as the desktop migrates, `13.sqm`
     * and `14.sqm` cannot copy them (pinned by the failure tests).
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
    @ValueSource(longs = [27, 28, 38, 47])
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
     * A database from before 27 cannot be opened by the start-up path (pinned below: the trigger),
     * so it is migrated to 27 as [OldDatabases.migrate] does, with the generated migrations and
     * foreign keys on, then opened. The rows `1.sqm` to `26.sqm` change are checked from [version].
     */
    @ParameterizedTest(name = "version {0}")
    @ValueSource(longs = [4, 14])
    fun `a database from before the trigger fix keeps every row and gets the newest schema`(version: Long) {
        val key = OldDatabases.key(version.toInt())
        val file = file()
        val seeded = oldDatabase(file, key, version)
        OldDatabases.userVersion(file, key) shouldBe version
        seedDifferences(file, key, seeded).shouldBeEmpty()

        OldDatabases.migrate(file, key, version, 27)

        openAndCheck(file, key, version, seeded, copiedAt = 27)
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
     * Migrations the desktop's start-up path cannot run, so a database from before 27 is never
     * migrated (none exists in the field; see the class comment). Inherited from upstream: `13.sqm`
     * reads `merged.manga_source`, which `3.sqm` named `COL_MANGA_SOURCE`, so nothing from before 4
     * migrates; with foreign keys on (the desktop migrates with them on), `13.sqm` and `14.sqm`
     * cannot copy a row whose parent is gone. On the desktop only: `26.sqm` renames a table, which
     * makes SQLite check every trigger, and `20.sqm`'s trigger has a double-quoted string this
     * build's SQLite rejects ([OldDatabases.TRIGGER_VERSION]; the start-up path rewrites the
     * trigger only after migrating). Each time the open fails, nothing is changed, and the copy made
     * before the migration is kept.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["version 1", "version 3", "orphan chapter at 13", "orphan history at 14", "version 4", "version 14", "version 21", "version 26"])
    fun `a migration that fails leaves the old database as it was`(case: String) {
        val version = case.substringAfterLast(' ').toLong()
        val key = OldDatabases.key(40 + version.toInt())
        val file = file()
        oldDatabase(file, key, version)
        OldDatabases.connect(file, key).use { c ->
            c.createStatement().use {
                when (case) {
                    "orphan chapter at 13" -> it.execute("INSERT INTO chapters SELECT 98, 999, url, name, scanlator, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload FROM chapters WHERE _id = 10")
                    "orphan history at 14" -> it.execute("INSERT INTO history VALUES (5, 9999, 1, 1)")
                }
            }
        }
        val before = allRows(file, key)
        val schemaBefore = OldDatabases.connect(file, key).use { SchemaCompare.schema(it) }

        val e = shouldThrowAny { open(file, key) { } }

        messages(e) shouldContain when {
            version <= 3 -> "no such column: manga_source"
            case.startsWith("orphan") -> "FOREIGN KEY constraint failed"
            else -> "error in trigger system_category_delete_trigger"
        }
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
        // Inherited from upstream (reached on the desktop only past the trigger, as above): 26.sqm keys
        // favourites by gid and token, and the copy fails on a duplicate; nothing is changed
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
