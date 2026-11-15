package zoukan.desktop.compat

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.ToastSink
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/**
 * The androidx.preference values, SharedPreferences and Toast as extensions use them: values read
 * and written through the stored preference, sets that do not take neighbouring keys with them,
 * listeners that cannot break a write, and toasts that reach the app.
 */
class PreferenceCompatTest {

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
        ToastSink.set(null)
    }

    private fun prefs(name: String) = JavaSharedPreferences("${name}_${System.nanoTime()}")

    @Test
    fun `ListPreference value round-trips through its SharedPreferences`() {
        val prefs = prefs("list")
        val pref = ListPreference(null).apply {
            key = "quality"
            entries = arrayOf("Low", "High")
            entryValues = arrayOf("low", "high")
            setDefaultValue("low")
        }
        // Before it has SharedPreferences: the default, and setValue is kept in memory
        pref.value shouldBe "low"
        pref.sharedPreferences = prefs
        pref.value shouldBe "low"
        pref.entry shouldBe "Low"

        var listenerCalls = 0
        pref.setOnPreferenceChangeListener { _, _ ->
            listenerCalls++
            true
        }
        pref.value = "high"
        prefs.getString("quality", null) shouldBe "high"
        pref.value shouldBe "high"
        pref.entry shouldBe "High"
        // As Android, setValue only persists
        listenerCalls shouldBe 0

        pref.setValueIndex(0)
        prefs.getString("quality", null) shouldBe "low"
        pref.findIndexOfValue(pref.value) shouldBe 0
        pref.dialogTitle = "Quality"
        pref.dialogTitle shouldBe "Quality"
    }

    @Test
    fun `TwoStatePreference checked state round-trips through its SharedPreferences`() {
        val prefs = prefs("switch")
        val switch = SwitchPreferenceCompat(null).apply {
            key = "adult"
            sharedPreferences = prefs
        }
        switch.isChecked shouldBe false
        switch.isChecked = true
        prefs.getBoolean("adult", false) shouldBe true
        switch.isChecked shouldBe true

        prefs.edit().putBoolean("adult", false).commit()
        switch.isChecked shouldBe false

        val checkbox = CheckBoxPreference(null).apply {
            key = "box"
            setDefaultValue(true)
        }
        checkbox.isChecked shouldBe true
        checkbox.isChecked = false
        checkbox.isChecked shouldBe false
        checkbox.disableDependentsState = true
        checkbox.disableDependentsState shouldBe true
    }

    @Test
    fun `EditTextPreference text round-trips through its SharedPreferences`() {
        val prefs = prefs("text")
        val pref = EditTextPreference(null).apply {
            key = "domain"
            setDefaultValue("https://example.org")
            sharedPreferences = prefs
        }
        pref.text shouldBe "https://example.org"
        pref.text = "https://mirror.example.org"
        prefs.getString("domain", null) shouldBe "https://mirror.example.org"
        pref.text shouldBe "https://mirror.example.org"
    }

    @Test
    fun `removing or rewriting a key keeps other keys that start with it`() {
        val name = "domain_${System.nanoTime()}"
        val prefs = JavaSharedPreferences(name)
        prefs.edit()
            .putString("domain", "a")
            .putString("domain.custom", "b")
            .putString("domain.size", "large")
            .commit()

        prefs.edit().remove("domain").commit()
        prefs.getString("domain.custom", null) shouldBe "b"
        prefs.getString("domain.size", null) shouldBe "large"
        // And from disk: a domain.size of its own is not taken for a Set
        JavaSharedPreferences(name).all shouldBe mapOf("domain.custom" to "b", "domain.size" to "large")

        // A Set under the same key, written and rewritten (each write clears the old Set first)
        prefs.edit().remove("domain.size").commit()
        prefs.edit().putStringSet("domain", mutableSetOf("x", "y")).commit()
        prefs.edit().putStringSet("domain", mutableSetOf("z")).commit()
        prefs.getStringSet("domain", null) shouldBe setOf("z")
        prefs.getString("domain.custom", null) shouldBe "b"
        JavaSharedPreferences(name).all shouldBe mapOf("domain" to setOf("z"), "domain.custom" to "b")

        prefs.edit().remove("domain").commit()
        prefs.getStringSet("domain", null) shouldBe null
        prefs.getString("domain.custom", null) shouldBe "b"
        JavaSharedPreferences(name).all shouldBe mapOf("domain.custom" to "b")
    }

    @Test
    fun `a value of another type replaces a Set, and removing it leaves nothing`() {
        val name = "settype_${System.nanoTime()}"
        val prefs = JavaSharedPreferences(name)
        prefs.edit().putStringSet("langs", mutableSetOf("en", "ja")).commit()
        prefs.edit().putString("langs", "en").commit()
        prefs.getString("langs", null) shouldBe "en"
        JavaSharedPreferences(name).all shouldBe mapOf("langs" to "en")

        prefs.edit().remove("langs").commit()
        prefs.contains("langs") shouldBe false
        prefs.getStringSet("langs", null) shouldBe null
        JavaSharedPreferences(name).all shouldBe emptyMap<String, Any?>()
    }

    @Test
    fun `a throwing listener does not break commit or apply, and the others still hear`() {
        val name = "listener_${System.nanoTime()}"
        val prefs = JavaSharedPreferences(name)
        val heard = mutableListOf<String?>()
        val throwing = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> error("extension bug") }
        val recording = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(throwing)
        prefs.registerOnSharedPreferenceChangeListener(recording)

        prefs.edit().putString("a", "1").commit() shouldBe true
        prefs.edit().putString("b", "2").apply()
        prefs.getString("a", null) shouldBe "1"
        prefs.getString("b", null) shouldBe "2"
        heard shouldBe listOf("a", "b")

        // The same value again notifies no one, as on Android
        prefs.edit().putString("a", "1").commit()
        heard shouldBe listOf("a", "b")

        // A listener may write to the same preferences without deadlocking
        val writing = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "c") p.edit().putString("c-seen", "yes").commit()
        }
        prefs.registerOnSharedPreferenceChangeListener(writing)
        prefs.edit().putString("c", "3").commit()
        prefs.getString("c-seen", null) shouldBe "yes"

        JavaSharedPreferences.flushPendingSaves()
        JavaSharedPreferences(name).getString("b", null) shouldBe "2"
        // Listeners are held weakly: keep these alive to the end
        java.lang.ref.Reference.reachabilityFence(throwing)
        java.lang.ref.Reference.reachabilityFence(recording)
        java.lang.ref.Reference.reachabilityFence(writing)
    }

    @Test
    fun `an apply is on disk after the pending saves are flushed`() {
        val name = "flush_${System.nanoTime()}"
        JavaSharedPreferences(name).edit().putString("last", "change").apply()
        JavaSharedPreferences.flushPendingSaves()
        JavaSharedPreferences(name).getString("last", null) shouldBe "change"
    }

    @Test
    fun `an extension toast reaches the sink, also with setText and a resource id`() {
        val received = mutableListOf<String>()
        ToastSink.set { received += it }

        Toast.makeText(null, "Logged in", Toast.LENGTH_SHORT).show()
        Toast.makeText(null, "x", Toast.LENGTH_LONG).apply {
            setText("Changed")
            duration shouldBe Toast.LENGTH_LONG
        }.show()
        // No extension resources on the desktop: no text, and no exception
        Toast.makeText(null, 0x7f010001, Toast.LENGTH_SHORT).show()

        received shouldBe listOf("Logged in", "Changed")

        // Without a sink the text is only printed
        ToastSink.set(null)
        Toast.makeText(null, "printed", Toast.LENGTH_SHORT).show()
        received shouldBe listOf("Logged in", "Changed")
    }

    @Test
    fun `a category gets the screen's SharedPreferences, also for children added before it was added`() {
        val prefs = prefs("category")
        val screen = PreferenceScreen(null).apply { sharedPreferences = prefs }
        val category = PreferenceCategory(null).apply { title = "Reading" }
        // Some extensions fill a category before adding it to the screen
        val early = SwitchPreferenceCompat(null).apply { key = "early" }
        category.addPreference(early)
        screen.addPreference(category)
        val late = EditTextPreference(null).apply { key = "late" }
        category.addPreference(late)

        early.sharedPreferences shouldBe prefs
        late.sharedPreferences shouldBe prefs
        early.parent shouldBe category
        category.parent shouldBe screen
        screen.preferenceCount shouldBe 1
        category.preferenceCount shouldBe 2
        category.getPreference(1) shouldBe late
        // findPreference searches nested groups, as on Android
        screen.findPreference<Preference>("late") shouldBe late
        screen.findPreference<Preference>("missing") shouldBe null

        early.isChecked = true
        prefs.getBoolean("early", false) shouldBe true
        category.removePreference(late) shouldBe true
        late.parent shouldBe null
    }

    @Test
    fun `a dependent preference is disabled while the switch it depends on is off`() {
        val screen = PreferenceScreen(null).apply { sharedPreferences = prefs("dependency") }
        val master = SwitchPreferenceCompat(null).apply {
            key = "master"
            setDefaultValue(false)
        }
        val category = PreferenceCategory(null)
        val dependent = EditTextPreference(null).apply { key = "dependent" }
        screen.addPreference(master)
        screen.addPreference(category)
        category.addPreference(dependent)
        dependent.dependency = "master"

        dependent.isEnabled shouldBe false
        dependent.performClick() shouldBe false
        master.isChecked = true
        dependent.isEnabled shouldBe true

        // setDisableDependentsState(true): on disables them instead
        master.disableDependentsState = true
        dependent.isEnabled shouldBe false

        // A disabled group disables its children, as on Android
        master.disableDependentsState = false
        category.isEnabled = false
        dependent.isEnabled shouldBe false
        category.isEnabled = true

        // An empty text disables what depends on a text preference
        val chained = Preference(null).apply { key = "chained" }
        screen.addPreference(chained)
        chained.dependency = "dependent"
        chained.isEnabled shouldBe false
        dependent.text = "x"
        chained.isEnabled shouldBe true

        // A dependency cycle does not recurse forever
        master.dependency = "chained"
        dependent.isEnabled shouldBe true
    }

    @Test
    fun `SeekBarPreference keeps an int within its range in its SharedPreferences`() {
        val prefs = prefs("seekbar")
        val pref = SeekBarPreference(null).apply {
            key = "page_count"
            min = 1
            max = 10
            setDefaultValue(4)
            showSeekBarValue = true
        }
        pref.value shouldBe 4
        pref.sharedPreferences = prefs
        pref.currentValue shouldBe 4
        pref.value = 7
        prefs.getInt("page_count", 0) shouldBe 7
        pref.value = 50
        pref.value shouldBe 10
        // As the settings screen saves it
        pref.saveNewValue(3)
        prefs.getInt("page_count", 0) shouldBe 3
        pref.value shouldBe 3
    }

    @Test
    fun `setPersistent(false) keeps values out of SharedPreferences, and the common setters exist`() {
        val prefs = prefs("persistent")
        val pref = SwitchPreferenceCompat(null).apply {
            key = "transient"
            sharedPreferences = prefs
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            order = 3
            isSelectable = false
        }
        pref.isChecked = true
        prefs.contains("transient") shouldBe false
        pref.isChecked shouldBe true
        pref.isIconSpaceReserved shouldBe false
        pref.isSingleLineTitle shouldBe false
        pref.order shouldBe 3
        pref.isSelectable shouldBe false
        pref.hasKey() shouldBe true
        PreferenceCategory(null).isSelectable shouldBe false
    }

    @Test
    fun `a summary provider replaces the summary, as on Android`() {
        val pref = ListPreference(null).apply {
            key = "lang"
            entries = arrayOf("English", "French")
            entryValues = arrayOf("en", "fr")
            setDefaultValue("fr")
            summary = "ignored"
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }
        pref.summary shouldBe "French"
        val text = EditTextPreference(null).apply { summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance() }
        text.summary shouldBe "Not set"
        text.text = "abc"
        text.summary shouldBe "abc"
    }
}
