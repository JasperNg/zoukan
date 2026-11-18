package zoukan.desktop.property

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.secrets.FileCipher
import java.io.File
import java.util.Properties

/**
 * Preferences [PreferencePropertyTest] found that did not survive a save and a reload, with the
 * smallest inputs found. The settings file is Java's Properties XML, which writes keys as XML
 * attributes and values as text without escaping these characters, so the XML parser normalised
 * them on reading; the save now writes them as character references (JavaSharedPreferences
 * `keepLineBreaks`).
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

    @Test
    fun `a key with a tab or a line break keeps its value`() {
        for (key in listOf("a\tb", "a\nb", "a\rb")) {
            reloaded(key, "v")[key] shouldBe "v"
        }
    }

    @Test
    fun `a carriage return in a value survives`() {
        reloaded("k", "a\rb")["k"] shouldBe "a\rb"
        reloaded("k", "a\r\nb")["k"] shouldBe "a\r\nb"
    }

    @Test
    fun `a key with a line break keeps its Set, its type and its neighbours`() {
        JavaSharedPreferences("neighbours").edit()
            .putStringSet("s\tet\r", setOf("a\rb", "c\r\n", "\r"))
            .putInt("i\nnt", 5)
            .putString("i nt", "plain")
            .putString("q\"<&>'\t", "<&>\"'\r]]>")
            .putString("&#13;\r", "&#13;&#10;&amp;\r")
            .putString("long", "x\r\ny\t".repeat(100_000))
            .commit()
        val all = JavaSharedPreferences("neighbours").all
        all shouldBe mapOf(
            "s\tet\r" to setOf("a\rb", "c\r\n", "\r"),
            "i\nnt" to 5,
            "i nt" to "plain",
            "q\"<&>'\t" to "<&>\"'\r]]>",
            "&#13;\r" to "&#13;&#10;&amp;\r",
            "long" to "x\r\ny\t".repeat(100_000),
        )
    }

    @OptIn(ForTestsOnly::class)
    @Test
    fun `an encrypted file keeps a carriage return in a key and a value`() {
        val previous = SettingsFileCodec.current
        try {
            SettingsFileCodec.current = FileCipher(ByteArray(32) { 3 })
            reloaded("a\rb\tc", "x\ry")["a\rb\tc"] shouldBe "x\ry"
        } finally {
            SettingsFileCodec.current = previous
        }
    }

    @Test
    fun `a file written now reads the same with plain Properties, as an older version reads it`() {
        JavaSharedPreferences("downgrade").edit().putString("a\tb\nc\rd", "x\ry\r\nz").commit()
        val properties = Properties()
        JavaSharedPreferences.fileFor("downgrade").inputStream().use { properties.loadFromXML(it) }
        properties.getProperty("a\tb\nc\rd") shouldBe "x\ry\r\nz"
    }

    @Test
    fun `a file written before the fix still reads as it did`() {
        // As older versions wrote it: raw line breaks, which the parser normalises as before
        val file = JavaSharedPreferences.fileFor("older")
        file.parentFile.mkdirs()
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                "<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">\n" +
                "<properties>\n<entry key=\"k\">a\r\nb&amp;#13;</entry>\n<entry key=\"e\"/>\n</properties>\n",
        )
        JavaSharedPreferences("older").all shouldBe mapOf("k" to "a\nb&#13;", "e" to "")
    }
}
