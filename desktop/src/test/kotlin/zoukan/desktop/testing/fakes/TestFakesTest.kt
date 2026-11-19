package zoukan.desktop.testing.fakes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okio.Buffer
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.fakes.FaultyFileSystem.Op
import java.io.File
import java.nio.file.Files
import java.sql.SQLException

class FailingSqlDriverTest {
    @TempDir
    lateinit var dir: File

    private val real by lazy {
        JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(dir, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
    }
    private val driver by lazy { FailingSqlDriver(real) }
    private val db by lazy { DesktopDatabase.createDatabase(driver) }

    @AfterEach
    fun close() {
        real.close()
    }

    private fun insert(name: String) {
        driver.execute(null, "INSERT INTO categories(name, sort, flags, manga_order, hidden) VALUES ('$name', 1, 0, '', 0)", 0)
    }

    private fun names(): List<String> =
        driver.executeQuery(null, "SELECT name FROM categories WHERE _id > 0 ORDER BY name", { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows += cursor.getString(0)!!
            QueryResult.Value(rows)
        }, 0).value

    @Test
    fun `a statement that fails inside a transaction rolls the whole transaction back`() {
        driver.failOn(FailingSqlDriver.insertInto("categories"), nth = 2)
        val error = shouldThrow<SQLException> {
            db.transaction {
                insert("first")
                insert("second")
            }
        }
        error.message shouldContain "SQLITE_FULL"
        names() shouldBe emptyList()
        driver.failures shouldBe 1

        // The failure was used up: the same transaction works now
        db.transaction {
            insert("first")
            insert("second")
        }
        names() shouldBe listOf("first", "second")
    }

    @Test
    fun `only matching statements count, and heal ends the failure`() {
        driver.failOn(FailingSqlDriver.insertInto("categories"), times = Int.MAX_VALUE)
        names() shouldBe emptyList() // a SELECT goes through
        repeat(3) { shouldThrow<SQLException> { insert("x") } }
        driver.heal()
        insert("x")
        names() shouldBe listOf("x")
    }

    @Test
    fun `the generated queries go through the driver and can fail`() {
        driver.failOn(FailingSqlDriver.selectFrom("categories"))
        shouldThrow<SQLException> { db.categoriesQueries.getCategories().executeAsList() }
        // Used up: the next read works
        db.categoriesQueries.getCategories().executeAsList()
        driver.failures shouldBe 1
    }
}

class FaultyFileSystemTest {
    @TempDir
    lateinit var dir: File

    private val fs = FaultyFileSystem()

    private fun path(name: String): Path = File(dir, name).toOkioPath()

    /** One `write` call per chunk, unbuffered, so a failed chunk is not written again at close. */
    private fun FaultyFileSystem.writeChunks(file: Path, vararg chunks: String) {
        sink(file).use { sink ->
            chunks.forEach { chunk -> Buffer().writeUtf8(chunk).let { sink.write(it, it.size) } }
        }
    }

    @Test
    fun `the n-th write fails once, the writes before it landed and the next ones go through`() {
        fs.failOn(Op.WRITE, nth = 2)
        val file = path("a.txt")
        shouldThrow<IOException> { fs.writeChunks(file, "one", "two", "three") }.message shouldContain "write"
        File(dir, "a.txt").readText() shouldBe "one"
        fs.writeChunks(file, "one", "two", "three")
        File(dir, "a.txt").readText() shouldBe "onetwothree"
        fs.calls(Op.WRITE) shouldBe 5
    }

    @Test
    fun `a full disk writes what fits, then fails with the OS message, and heals`() {
        fs.full(freeBytes = 4)
        val file = path("b.txt")
        val error = shouldThrow<IOException> { fs.writeChunks(file, "0123456789") }
        error.message shouldBe FaultyFileSystem.DISK_FULL
        File(dir, "b.txt").readText() shouldBe "0123"
        // Still full
        shouldThrow<IOException> { fs.writeChunks(path("c.txt"), "x") }

        fs.heal()
        fs.writeChunks(file, "0123456789")
        File(dir, "b.txt").readText() shouldBe "0123456789"
    }

    @Test
    fun `a read-only disk refuses every change and still reads`() {
        val existing = File(dir, "kept.txt").apply { writeText("kept") }
        val folder = File(dir, "folder").apply { mkdirs() }
        fs.readOnly()

        shouldThrow<IOException> { fs.writeChunks(path("new.txt"), "x") }.message shouldContain "Read-only"
        shouldThrow<IOException> { fs.appendingSink(existing.toOkioPath()) }
        shouldThrow<IOException> { fs.delete(existing.toOkioPath()) }
        shouldThrow<IOException> { fs.atomicMove(existing.toOkioPath(), path("moved.txt")) }
        shouldThrow<IOException> { fs.createDirectory(path("sub")) }
        fs.read(existing.toOkioPath()) { readUtf8() } shouldBe "kept"
        dir.list()!!.sorted() shouldBe listOf("folder", "kept.txt")
        folder.list()!!.toList() shouldBe emptyList()

        fs.heal()
        fs.atomicMove(existing.toOkioPath(), path("moved.txt"))
        File(dir, "moved.txt").readText() shouldBe "kept"
    }

    @Test
    fun `a move fails as often as planned, then works`() {
        val source = File(dir, "s").apply { writeText("s") }
        fs.failOn(Op.MOVE, times = 2) { IOException("The file is being used by another process") }
        repeat(2) { shouldThrow<IOException> { fs.atomicMove(source.toOkioPath(), path("t")) } }
        fs.atomicMove(source.toOkioPath(), path("t"))
        File(dir, "t").readText() shouldBe "s"
    }
}

class FileFaultsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a file in the way stops a folder and anything under it, and deleting it heals`() {
        val folder = File(dir, "downloads/Source")
        val blocker = FileFaults.fileInTheWay(folder)

        folder.mkdirs() shouldBe false
        shouldThrow<java.io.IOException> { File(folder, "Manga/001.png").also { it.parentFile.mkdirs() }.writeText("x") }
        shouldThrow<java.io.IOException> { Files.createDirectories(File(folder, "Manga").toPath()) }

        blocker.delete() shouldBe true
        File(folder, "Manga").mkdirs() shouldBe true
    }

    @Test
    fun `a read-only folder refuses new files where the OS enforces it, and closing heals`() {
        val folder = File(dir, "backups").apply { mkdirs() }
        File(folder, "old.tachibk").writeText("old")
        val fault = FileFaults.readOnly(folder)
        assumeTrue(fault != null, "This OS or user can write in a read-only folder")
        fault!!.use {
            shouldThrow<java.io.IOException> { File(folder, "new.tachibk").writeText("new") }
            File(folder, "old.tachibk").delete() shouldBe false
        }
        File(folder, "new.tachibk").writeText("new")
        folder.list()!!.sorted() shouldBe listOf("new.tachibk", "old.tachibk")
    }
}
