package zoukan.desktop.property

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.secrets.FileCipher
import java.io.File

/**
 * Any preference value of every type the store has survives being written, saved to its file and
 * read back by a new store on that file, in a plain file and in a file encrypted with the file key
 * (as the app keeps them): keys and values with odd characters, empty strings and empty sets,
 * numbers at their edges.
 */
class PreferencePropertyTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null
    private var previousCodec: SettingsFileCodec? = null
    private var file = 0

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        previousCodec = SettingsFileCodec.current
        System.setProperty(property, root.absolutePath)
    }

    @OptIn(ForTestsOnly::class)
    @AfterEach
    fun restoreRoot() {
        JavaSharedPreferences.awaitPendingSaves()
        SettingsFileCodec.current = previousCodec
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    /** A key, a value of one type, and how to make that type's preference in a store. */
    private class Case(val key: String, val value: Any, val type: String, val preference: (PreferenceStore, String) -> Preference<*>) {
        override fun toString() = "$type \"${escaped(key)}\" = ${if (value is String) "\"${escaped(value)}\"" else value}"
    }

    private data class Item(val id: Int, val label: String)

    private fun itemToString(item: Item) = "${item.id}|${item.label}"

    private fun itemFromString(s: String) = Item(s.substringBefore('|').toInt(), s.substringAfter('|'))

    /** Odd keys with plain values, or plain keys with odd values, so a failure says which of the two it is. */
    private fun cases(oddKeys: Boolean) = arbFrom { r ->
        val key = when {
            !oddKeys -> "pref_${r.nextInt(1_000_000)}"
            r.nextInt(6) == 0 -> listOf("pref_display_mode", "a.size", "a.0", "__zoukan_type__.x", "source_123_password", "a=b", "a:b", "#x", "!x", " spaced key ").random(r)
            else -> NastyStrings.next(r, maxPieces = 5, padding = false).ifEmpty { "k" }
        }
        val text = if (oddKeys) "value ${r.nextInt()}" else NastyStrings.next(r)
        fun texts() = List(r.nextInt(0, 5)) { if (oddKeys) "v${r.nextInt()}" else NastyStrings.next(r, maxPieces = 3) }.toSet()
        when (r.nextInt(8)) {
            0 -> Case(key, text, "String") { s, k -> s.getString(k, DEFAULT_STRING) }
            1 -> Case(key, listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, r.nextInt()).random(r), "Int") { s, k -> s.getInt(k, 7) }
            2 -> Case(key, listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, r.nextLong()).random(r), "Long") { s, k -> s.getLong(k, 7L) }
            3 -> Case(
                key,
                listOf(0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE, r.nextFloat() * 1e6f - 5e5f).random(r),
                "Float",
            ) { s, k -> s.getFloat(k, 7f) }
            4 -> Case(key, r.nextBoolean(), "Boolean") { s, k -> s.getBoolean(k, false) }
            5 -> Case(key, texts(), "Set<String>") { s, k -> s.getStringSet(k, setOf(DEFAULT_STRING)) }
            6 -> Case(key, Item(r.nextInt(), text.replace("|", "")), "Object") { s, k ->
                s.getObjectFromString(k, Item(0, DEFAULT_STRING), ::itemToString, ::itemFromString)
            }
            else -> Case(key, "", "String") { s, k -> s.getString(k, DEFAULT_STRING) }
        }
    }

    @Test
    fun `any value survives write then read in a plain file`() = roundTrips(codec = null, oddKeys = false)

    @Test
    fun `any value survives write then read in an encrypted file`() = roundTrips(codec = FileCipher(ByteArray(32) { 3 }), oddKeys = false)

    @Test
    fun `any key keeps its value in a plain file`() = roundTrips(codec = null, oddKeys = true)

    @Test
    fun `any key keeps its value in an encrypted file`() = roundTrips(codec = FileCipher(ByteArray(32) { 3 }), oddKeys = true)

    @OptIn(ForTestsOnly::class)
    private fun roundTrips(codec: SettingsFileCodec?, oddKeys: Boolean) = runBlocking<Unit> {
        SettingsFileCodec.current = codec
        val found = Counterexamples<String>()
        checkAll(PropertyRuns.config(ITERATIONS), cases(oddKeys)) { case ->
            val part = if (oddKeys) "key" else "value"
            val name = "property_${file++}"
            @Suppress("UNCHECKED_CAST")
            (case.preference(DesktopPreferenceStore(JavaSharedPreferences(name)), case.key) as Preference<Any>).set(case.value)
            JavaSharedPreferences.awaitPendingSaves()

            val stored = File(root, "settings/$name.xml").readBytes()
            if (codec != null && !codec.isEncoded(stored)) found.add("$part ${case.type}: file not encrypted", case.key, case.toString())

            val reopened = JavaSharedPreferences(name)
            val read = case.preference(DesktopPreferenceStore(reopened), case.key).get()
            // Boxed equality: NaN equals NaN, -0.0 does not equal 0.0
            if ((read as Any?) != case.value) found.add(kindOfChange(part, case), case.key, "$case, read back ${show(read)}")
            if (File(root, "settings/$name.xml.unreadable").exists()) found.add("$part ${case.type}: file unreadable", case.key, case.toString())
        }
        check(found.kinds().isEmpty()) { "Preferences that did not survive (seed ${PropertyRuns.seed}):\n" + found.report(found.kinds()) }
    }

    /** A changed value, by what is odd in the key or the value: the characters XML cannot keep as they are. */
    private fun kindOfChange(part: String, case: Case): String {
        val odd = if (part == "key") case.key else (case.value as? String) ?: (case.value as? Item)?.label ?: (case.value as? Set<*>)?.joinToString("") ?: ""
        val why = when {
            '\r' in odd -> "carriage return"
            '\t' in odd || '\n' in odd -> "tab or line break"
            PortableName.hasLoneSurrogate(odd) -> "lone surrogate"
            odd.any { it.code < 0x20 } -> "control character"
            else -> "other"
        }
        return "$part with a $why changed"
    }

    private fun show(value: Any?) = if (value is String) "\"${escaped(value)}\"" else value.toString()

    companion object {
        private const val ITERATIONS = 300
        private const val DEFAULT_STRING = "\u0000default"
    }
}
