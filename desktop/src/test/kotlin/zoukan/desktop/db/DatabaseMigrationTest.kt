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
 * The databases are built by [OldDatabases] from the repository's schema snapshot and migrations
 * and seeded with [MigrationSeed]'s rows: the oldest version the repository can rebuild (28), one
 * in the middle (38) and the one before the newest (47).
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

    /** An old database at [version] seeded with [MigrationSeed.rows]; returns the rows as inserted. */
    private fun oldDatabase(file: File, key: ByteArray, version: Long): List<SeedRow> {
        OldDatabases.create(file, key, version)
        return OldDatabases.seed(file, key)
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
                val keyColumns = MigrationSeed.keyOf(table)
                val actual = SchemaCompare.rows(c, table).associateBy { row -> keyColumns.map { row[it] } }
                rows.flatMap { row ->
                    val id = keyColumns.map { row.values[it] }
                    val got = actual[id] ?: return@flatMap listOf("$table $id: not inserted")
                    row.values.filter { (column, value) -> got[column] != value }.map { (column, value) -> "$table $id.$column: wrote $value, read ${got[column]}" }
                }
            }
        }

    private fun schemaDifferences(file: File, key: ByteArray): List<String> =
        OldDatabases.connect(file, key).use { SchemaCompare.differences(SchemaCompare.schema(it), fresh.first) }

    @Test
    fun `the migrations the tests rebuild are the ones the schema runs`() {
        // N.sqm migrates version N to N + 1; the newest version is one past the last
        val numbers = OldDatabases.migrationFiles.map { it.nameWithoutExtension.toLong() }
        numbers shouldBe (1L..numbers.size).toList()
        Database.Schema.version shouldBe numbers.size + 1L
        // The rebuilt snapshot plus the migrations gives the newest schema (checked by the tests below)
        OldDatabases.snapshot.isFile shouldBe true
    }

    @Test
    fun `every table of the newest schema has known rows`() {
        // So a table a new migration adds is seeded and checked too
        (fresh.second.keys - MigrationSeed.rows.map { it.table }.toSet()).shouldBeEmpty()
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(longs = [28, 38, 47])
    fun `an old database keeps every row and gets the newest schema`(version: Long) {
        val key = OldDatabases.key(version.toInt())
        val file = file()
        val seeded = oldDatabase(file, key, version)
        OldDatabases.userVersion(file, key) shouldBe version
        // The rows went in as written: no trigger changed them while seeding
        seedDifferences(file, key, seeded).shouldBeEmpty()

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
        schemaDifferences(file, key).shouldBeEmpty()
        // The copy made before the migration is the old database, whole
        val copy = DesktopDatabase.preMigrationCopy(file, OldDatabases.NEWEST)
        OldDatabases.userVersion(copy, key) shouldBe version
        OldDatabases.connect(copy, key).use { c -> SchemaCompare.count(c, "chapters") shouldBe 4L }
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
