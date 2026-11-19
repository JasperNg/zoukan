package zoukan.desktop.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import zoukan.desktop.testing.fakes.FailingSqlDriver
import java.io.File
import java.sql.SQLException

class TestKitTest {
    private enum class Mode { FIRST, SECOND }

    private fun <T> Preference<T>.roundTrip(default: T, value: T) {
        get() shouldBe default
        isSet() shouldBe false
        set(value)
        get() shouldBe value
        isSet() shouldBe true
        delete()
        get() shouldBe default
        isSet() shouldBe false
    }

    @Test
    fun `TestPreferenceStore supports typed primitives, string sets, objects, and flows`() = runTest {
        val store = TestPreferenceStore(mapOf("seed" to "yes"))
        store.getString("seed", "").get() shouldBe "yes"

        store.getString("s", "d").roundTrip("d", "x")
        store.getLong("l", 1L).roundTrip(1L, 99L)
        store.getInt("i", 2).roundTrip(2, 7)
        store.getFloat("f", 0.5f).roundTrip(0.5f, 1.5f)
        store.getBoolean("b", false).roundTrip(false, true)
        store.getStringSet("set", setOf("a")).roundTrip(setOf("a"), setOf("x", "y"))
        store.getObjectFromString("os", Mode.FIRST, { it.name }, { Mode.valueOf(it) }).roundTrip(Mode.FIRST, Mode.SECOND)
        store.getObjectFromInt("oi", Mode.FIRST, { it.ordinal }, { Mode.entries[it] }).roundTrip(Mode.FIRST, Mode.SECOND)

        val watched = store.getInt("n", 0)
        val seen = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) {
            seen += watched.changes().take(2).toList()
        }
        store.getString("unrelated", "").set("ignored")
        watched.set(42)
        job.join()

        seen shouldBe listOf(0, 42)
        store.getAll()["n"] shouldBe 42
    }

    @Test
    fun `TestDatabaseKit opens an encrypted database handler and supports FailingSqlDriver`(@TempDir temp: File) {
        TestDatabaseKit.withHandler(temp) { handler ->
            handler.await(inTransaction = true) {
                categoriesQueries.insert("Reading", 1L, 0L, 0L)
            }
            handler.awaitList { categoriesQueries.getCategories() }.any { it.name == "Reading" } shouldBe true
        }

        val (kit, failing) = TestDatabaseKit.withFailingDriver(temp, fileName = "faults.db")
        kit.use {
            failing.failOn(FailingSqlDriver.insertInto("categories"))
            runBlocking {
                shouldThrow<SQLException> {
                    kit.handler.await(inTransaction = true) {
                        categoriesQueries.insert("Blocked", 2L, 0L, 0L)
                    }
                }
                kit.handler.await(inTransaction = true) {
                    categoriesQueries.insert("Allowed", 3L, 0L, 0L)
                }
                kit.handler.awaitList { categoriesQueries.getCategories() }.map { it.name } shouldBe listOf("Allowed")
            }
        }
    }
}
