package zoukan.desktop.property

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/**
 * Preferences [PreferencePropertyTest] found that do not survive a save and a reload, with the
 * smallest inputs found. The settings file is Java's Properties XML, which writes keys as XML
 * attributes and values as text without escaping these characters, so the XML parser normalises
 * them on reading (JavaSharedPreferences.kt:613, `storeToXML`). Disabled until fixed.
 */
class PreferenceKnownDefectsTest {

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

    private fun reloaded(key: String, value: String): Map<String, *> {
        JavaSharedPreferences("known_defects").edit().putString(key, value).commit()
        return JavaSharedPreferences("known_defects").all
    }

    @Disabled("Known defect: a key with a tab, line feed or carriage return comes back with a space instead, so its value is lost (XML attribute normalisation)")
    @Test
    fun `a key with a tab or a line break keeps its value`() {
        for (key in listOf("a\tb", "a\nb", "a\rb")) {
            reloaded(key, "v")[key] shouldBe "v"
        }
    }

    @Disabled("Known defect: a carriage return in a string value comes back as a line feed (XML end-of-line normalisation)")
    @Test
    fun `a carriage return in a value survives`() {
        reloaded("k", "a\rb")["k"] shouldBe "a\rb"
        reloaded("k", "a\r\nb")["k"] shouldBe "a\r\nb"
    }
}
