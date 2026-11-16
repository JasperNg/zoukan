package zoukan.desktop.compat

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/**
 * What a PreferenceStore on the Android compatibility layer's SharedPreferences does: typed
 * defaults, set and delete, the change flow, and sharing the file with plain SharedPreferences.
 */
class PreferenceStoreContractTest {

    private enum class Mode { A, B, C }

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    private fun prefs() = JavaSharedPreferences("store_contract_${System.nanoTime()}")

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
    fun `every typed preference reads its default, then the value set, and forgets it on delete`() {
        val store = DesktopPreferenceStore(prefs())

        store.getString("s", "d").roundTrip("d", "x")
        store.getLong("l", 1L).roundTrip(1L, 99L)
        store.getInt("i", 2).roundTrip(2, 7)
        store.getFloat("f", 0.5f).roundTrip(0.5f, 1.5f)
        store.getBoolean("b", false).roundTrip(false, true)
        store.getStringSet("set", setOf("a")).roundTrip(setOf("a"), setOf("x", "y"))
        store.getObjectFromString("os", Mode.A, { it.name }, { Mode.valueOf(it) }).roundTrip(Mode.A, Mode.C)
        store.getObjectFromInt("oi", Mode.A, { it.ordinal }, { Mode.entries[it] }).roundTrip(Mode.A, Mode.B)
    }

    @Test
    fun `changes reach the preference flow and getAll lists stored keys`() = runTest {
        val store = DesktopPreferenceStore(prefs())
        val number = store.getInt("n", 0)

        val seen = mutableListOf<Int>()
        // Unconfined: collection starts (current value, listener registered) before set() runs
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            seen += number.changes().take(2).toList()
        }
        number.set(5)
        job.join()

        seen shouldBe listOf(0, 5)
        store.getAll()["n"] shouldBe 5
    }

    @Test
    fun `a value written through SharedPreferences is seen by the store`() {
        val prefs = prefs()
        val store = DesktopPreferenceStore(prefs)

        prefs.edit().putString("k", "v").commit()

        store.getString("k", "").get() shouldBe "v"
    }
}
