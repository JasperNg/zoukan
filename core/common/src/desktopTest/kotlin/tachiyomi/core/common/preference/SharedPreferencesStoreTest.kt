package tachiyomi.core.common.preference

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The rules of the shared preference store both apps read their settings through, beyond the
 * plain round trip the desktop's contract test covers: a stored value of the wrong type or an
 * unreadable one must give the default instead of crashing a screen, and observers hear only
 * their own key.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SharedPreferencesStoreTest {

    private enum class Mode { A, B }

    private val prefs = FakeSharedPreferences()
    private val store = SharedPreferencesStore(prefs)

    @Test
    fun `a stored value of another type reads as the default and is removed`() {
        prefs.edit().putString("count", "12").apply()
        store.getInt("count", 3).get() shouldBe 3
        prefs.contains("count") shouldBe false

        prefs.edit().putInt("name", 1).apply()
        store.getString("name", "default").get() shouldBe "default"
        prefs.contains("name") shouldBe false

        prefs.edit().putLong("flag", 1L).apply()
        store.getBoolean("flag", true).get() shouldBe true
        prefs.contains("flag") shouldBe false
    }

    @Test
    fun `an object that cannot be read gives the default and the stored text is kept`() {
        prefs.edit().putString("mode", "not a mode").apply()
        store.getObjectFromString("mode", Mode.A, { it.name }, { Mode.valueOf(it) }).get() shouldBe Mode.A
        prefs.getString("mode", null) shouldBe "not a mode"

        prefs.edit().putInt("ordinal", 9).apply()
        store.getObjectFromInt("ordinal", Mode.B, { it.ordinal }, { Mode.entries[it] }).get() shouldBe Mode.B
        // Not stored at all: the default, without asking the deserializer
        store.getObjectFromInt("missing", Mode.B, { it.ordinal }, { error("not called") }).get() shouldBe Mode.B
    }

    @Test
    fun `an enum preference reads an unknown name as the default`() {
        val pref = store.getEnum("enum", Mode.B)
        pref.get() shouldBe Mode.B
        pref.set(Mode.A)
        prefs.getString("enum", null) shouldBe "A"
        pref.get() shouldBe Mode.A
        prefs.edit().putString("enum", "REMOVED_IN_A_LATER_VERSION").apply()
        pref.get() shouldBe Mode.B
    }

    @Test
    fun `a long array preference skips values that are not numbers`() {
        val pref = store.getLongArray("ids", listOf(1L))
        pref.get() shouldBe listOf(1L)
        pref.set(listOf(3L, -4L, Long.MAX_VALUE))
        pref.get() shouldBe listOf(3L, -4L, Long.MAX_VALUE)
        prefs.edit().putString("ids", "5,x,,6").apply()
        pref.get() shouldBe listOf(5L, 6L)
        pref.set(emptyList())
        pref.get() shouldBe emptyList()
    }

    @Test
    fun `changes hear their own key and a clear, not other keys`() = runTest {
        val pref = store.getInt("watched", 0)
        val seen = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined) { seen += pref.changes().take(3).toList() }
        store.getInt("other", 0).set(7)
        yield()
        pref.set(1)
        yield()
        prefs.edit().clear().apply()
        job.join()
        seen shouldBe listOf(0, 1, 0)
    }

    @Test
    fun `the change listener is removed when the last observer stops`() = runTest {
        val job = launch(Dispatchers.Unconfined) { store.getInt("watched", 0).changes().collect { } }
        prefs.listenerCount shouldBe 1
        job.cancelAndJoin()
        prefs.listenerCount shouldBe 0
    }

    @Test
    fun `set helpers add and remove items and toggle flips a boolean`() {
        val set = store.getStringSet("set", emptySet())
        set += "a"
        set += listOf("b", "c")
        set -= "b"
        set.get() shouldBe setOf("a", "c")

        val flag = store.getBoolean("flag", false)
        flag.toggle() shouldBe true
        flag.toggle() shouldBe false
        flag.getAndSet { !it }
        flag.get() shouldBe true
    }

    @Test
    fun `private and app-state keys are recognised by their prefixes only`() {
        val private = Preference.privateKey("token")
        val appState = Preference.appStateKey("last_run")
        Preference.isPrivate(private) shouldBe true
        Preference.isAppState(private) shouldBe false
        Preference.isAppState(appState) shouldBe true
        Preference.isPrivate(appState) shouldBe false
        Preference.isPrivate("token") shouldBe false
        Preference.isPrivate("x$private") shouldBe false
    }

    @Test
    fun `filter states cycle in the order the filter sheets show them`() {
        TriState.DISABLED.next() shouldBe TriState.ENABLED_IS
        TriState.ENABLED_IS.next() shouldBe TriState.ENABLED_NOT
        TriState.ENABLED_NOT.next() shouldBe TriState.DISABLED

        val none = CheckboxState.TriState.None("x")
        none.next().next().next() shouldBe none
        none.next() shouldBe CheckboxState.TriState.Include("x")
        CheckboxState.State.None(1).next() shouldBe CheckboxState.State.Checked(1)
        listOf(1, 2).mapAsCheckboxState { it == 2 }.map { it.isChecked } shouldBe listOf(false, true)
    }
}
