package zoukan.desktop.prefs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/**
 * getAll() must return values with their types, as Android does: backups store an Int as an Int,
 * and a restore only writes a value over one of the same type.
 */
class TypedSharedPreferencesTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `getAll keeps types and sets, also after reloading the file`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        val prefs = JavaSharedPreferences("typed_test")
        prefs.edit()
            .putInt("int", 4)
            .putLong("long", 5_000_000_000L)
            .putFloat("float", 0.5f)
            .putBoolean("bool", true)
            .putString("string", "12")
            .putStringSet("set", mutableSetOf("a", "b"))
            .commit()

        val expected = mapOf(
            "int" to 4,
            "long" to 5_000_000_000L,
            "float" to 0.5f,
            "bool" to true,
            "string" to "12",
            "set" to setOf("a", "b"),
        )
        prefs.all shouldBe expected
        prefs.contains("set") shouldBe true

        // Read back from disk
        JavaSharedPreferences("typed_test").all shouldBe expected

        prefs.edit().remove("set").remove("int").commit()
        JavaSharedPreferences("typed_test").all shouldBe expected - "set" - "int"
    }

    @Test
    fun `a save replaces the file and leaves no temporary file, and a half-done save is recovered`() {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        val file = File(root, "settings/atomic_test.xml")
        JavaSharedPreferences("atomic_test").edit().putString("a", "1").commit()
        JavaSharedPreferences("atomic_test").edit().putString("b", "2").commit()
        file.isFile shouldBe true
        File(root, "settings/atomic_test.xml.tmp").exists() shouldBe false

        // An older version deleted the file before copying the new one over it
        file.renameTo(File(root, "settings/atomic_test.xml.tmp"))
        JavaSharedPreferences("atomic_test").all shouldBe mapOf("a" to "1", "b" to "2")
        file.isFile shouldBe true
    }
}
