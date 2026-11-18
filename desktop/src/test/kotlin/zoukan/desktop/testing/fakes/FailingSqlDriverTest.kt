package zoukan.desktop.testing.fakes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import zoukan.desktop.db.DesktopDatabase
import java.io.File
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
