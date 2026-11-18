package xyz.nulldev.androidcompat.io.sharedprefs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Zoukan's changes to JavaSharedPreferences that the desktop tests do not reach: files written
 * before types were kept, a key that only looks like a Set's entry, typed reads, and files that
 * are not quite the XML a save writes. Each test has its own settings folder.
 */
class JavaSharedPreferencesTest {
    @TempDir
    lateinit var root: File

    private var previousRoot: String? = null
    private var previousCodec: SettingsFileCodec? = null

    @BeforeEach
    fun pointAtTempRoot() {
        previousRoot = System.getProperty(ROOT_PROPERTY)
        previousCodec = SettingsFileCodec.current
        System.setProperty(ROOT_PROPERTY, root.absolutePath)
        SettingsFileCodec.current = null
    }

    /** Puts back the root and the codec, so a test that sets either does not change the tests after it. */
    @AfterEach
    fun restoreRoot() {
        SettingsFileCodec.current = previousCodec
        previousRoot?.let { System.setProperty(ROOT_PROPERTY, it) } ?: System.clearProperty(ROOT_PROPERTY)
    }

    private fun settingsFile(name: String) = File(root, "settings/$name.xml")

    /** A file as Properties.storeToXML writes it, with only the given entries. */
    private fun propertiesXml(vararg entries: Pair<String, String>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
            append("<!DOCTYPE properties SYSTEM \"http://java.sun.com/dtd/properties.dtd\">\n")
            append("<properties>\n")
            entries.forEach { (key, value) -> append("<entry key=\"$key\">$value</entry>\n") }
            append("</properties>\n")
        }

    private fun writeFile(
        name: String,
        bytes: ByteArray,
    ) {
        settingsFile(name).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    @Test
    fun `a file from before types were kept is read leniently, its sets as sets`() {
        writeFile(
            "legacy",
            propertiesXml(
                "count" to "5",
                "name" to "Zoukan",
                "tags.size" to "2",
                "tags.0" to "a",
                "tags.1" to "b",
            ).toByteArray(),
        )
        val prefs = JavaSharedPreferences("legacy")
        prefs.getInt("count", 0) shouldBe 5
        prefs.getString("count", null) shouldBe "5"
        prefs.getStringSet("tags", null) shouldBe setOf("a", "b")
        prefs.contains("tags") shouldBe true
        // getAll() cannot know the old types: numbers stay text, but a Set is one value
        prefs.all shouldBe mapOf("count" to "5", "name" to "Zoukan", "tags" to setOf("a", "b"))
    }

    @Test
    fun `a key of its own that ends in size is not taken for a Set`() {
        val prefs = JavaSharedPreferences("own_size")
        prefs.edit().putInt("pages.size", 3).putString("pages.0", "first").commit() shouldBe true
        prefs.contains("pages") shouldBe false
        prefs.all shouldBe mapOf("pages.size" to 3, "pages.0" to "first")
        // The same after reading the file back
        JavaSharedPreferences("own_size").all shouldBe mapOf("pages.size" to 3, "pages.0" to "first")
    }

    @Test
    fun `values keep their types through the file, and reading one as another type fails as on Android`() {
        JavaSharedPreferences("typed").edit()
            .putInt("i", 1)
            .putLong("l", 2L)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putString("s", "text")
            .putStringSet("set", setOf("x"))
            .commit() shouldBe true
        val reread = JavaSharedPreferences("typed")
        reread.all shouldBe mapOf("i" to 1, "l" to 2L, "f" to 1.5f, "b" to true, "s" to "text", "set" to setOf("x"))
        assertThrows<ClassCastException> { reread.getString("i", null) }
        assertThrows<ClassCastException> { reread.getInt("l", 0) }
        assertThrows<ClassCastException> { reread.getBoolean("s", false) }
        assertThrows<ClassCastException> { reread.getLong("f", 0L) }
        // A key that is not there is the default, whatever the type asked for
        reread.getInt("missing", 7) shouldBe 7
    }

    @Test
    fun `a file that starts with a byte order mark is still read as XML`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        writeFile("bom", bom + propertiesXml("theme" to "dark").toByteArray())
        val prefs = JavaSharedPreferences("bom")
        prefs.getString("theme", null) shouldBe "dark"
        File(root, "settings/bom.xml.unreadable").exists() shouldBe false
    }

    @Test
    fun `a blank file is kept aside before the first save replaces it`() {
        writeFile("blank", "  \n".toByteArray())
        val prefs = JavaSharedPreferences("blank")
        prefs.all shouldBe emptyMap<String, Any>()
        File(root, "settings/blank.xml.unreadable").readText() shouldBe "  \n"
        // The copy is safe, so saving is allowed again
        prefs.edit().putString("k", "v").commit() shouldBe true
        JavaSharedPreferences("blank").getString("k", null) shouldBe "v"
    }

    private companion object {
        /** xyz.nulldev.ts.config.ApplicationRootDir reads it on every use. */
        const val ROOT_PROPERTY = "suwayomi.tachidesk.config.server.rootDir"
    }
}
