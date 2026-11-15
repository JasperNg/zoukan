package zoukan.desktop.compat

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
}
