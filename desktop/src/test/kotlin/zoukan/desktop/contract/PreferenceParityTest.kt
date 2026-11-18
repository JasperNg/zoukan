package zoukan.desktop.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every preference key the desktop's own code declares (`desktop/src/main`) has Android's key
 * name and default, read from the Android sources, unless it is a desktop-only key (named
 * `zoukan_...`), a difference `desktop/PARITY.md` names (the key in backticks), or a
 * [KNOWN_DIFFERENCES] entry.
 *
 * Not checked here, because they cannot differ: the preference classes the desktop compiles
 * from the shared modules (`domain`, `core/common`: LibraryPreferences, DownloadPreferences,
 * BackupPreferences, SecurityPreferences, NetworkPreferences, ...) and the Android classes the
 * app-shared sync copies verbatim (`desktop/app-shared.txt`: TrackPreferences, UiPreferences).
 */
class PreferenceParityTest {

    @Test
    fun `the desktop's preference keys and defaults are Android's`() {
        val desktop = desktopDeclarations()
        val android = androidDeclarations()
        // A regex that matches nothing must not pass: these are well below today's counts
        check(desktop.declarations.size >= 120) { "Only ${desktop.declarations.size} desktop preferences found; the parser is broken" }
        check(android.size >= 300) { "Only ${android.size} Android preferences found; the parser or the source paths are broken" }
        val androidKeys = android.map { it.key }.toSet()
        val compared = desktop.declarations.count { it.key in androidKeys }
        check(compared >= 100) { "Only $compared desktop keys were found among Android's; the parser is broken" }

        val problems = desktop.unparsed.map { "$it: a preference read the test cannot parse (a key that is not a literal or a constant of the same file)" } +
            PreferenceParity.problems(
                desktop.declarations,
                android,
                KNOWN_DIFFERENCES,
                RepositorySources.text("desktop/PARITY.md"),
                desktopResolver(),
                androidResolver(),
            )
        writeReport(desktop.declarations, androidKeys, compared)
        assertNoProblems("Preferences that differ from Android", problems)
    }

    @Test
    fun `every known difference gives a reason`() {
        KNOWN_DIFFERENCES.filter { it.why.isBlank() }.map { it.key }.shouldBeEmpty()
    }

    // --- Self-tests on text in memory: the check catches a wrong key, default or type ---

    private val androidSample = """
        class ReaderPreferences(private val preferenceStore: PreferenceStore) {
            fun skipRead() = preferenceStore.getBoolean("skip_read", false)
            fun lastSource() = preferenceStore.getLong(Preference.appStateKey("last_catalogue_source"), -1)
            fun pinned() = preferenceStore.getStringSet(
                // KMK -->
                PINNED_KEY,
                // KMK <--
                emptySet(),
            )
            fun flash() = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)
            fun scale() = preferenceStore.getInt("scale", ScaleModes.FIT)
            object ScaleModes { const val FIT = 1 }
            companion object { const val PINNED_KEY = "pinned_catalogues" }
        }
    """.trimIndent()

    private fun sample(desktopText: String, known: List<KnownPreferenceDifference> = emptyList(), parity: String = ""): List<String> {
        val desktop = PreferenceSource.parse(desktopText, "Desktop.kt")
        val android = PreferenceSource.parse(androidSample, "Android.kt").declarations
        return desktop.unparsedStoreCalls + PreferenceParity.problems(
            desktop.declarations,
            android,
            known,
            parity,
            ConstantResolver(listOf(desktopText)),
            ConstantResolver(listOf(androidSample)),
        )
    }

    @Test
    fun `self-test - the parser reads literal, prefixed, constant and multi-line keys`() {
        val parsed = PreferenceSource.parse(androidSample, "Android.kt").declarations
        parsed.map { it.key } shouldBe listOf("skip_read", "appState:last_catalogue_source", "pinned_catalogues", "pref_reader_flash_mode", "scale")
        parsed.map { it.default } shouldBe listOf("false", "-1", "emptySet()", "FlashColor.BLACK", "ScaleModes.FIT")
        PreferenceSource.parse("""store.getString(key = "a", defaultValue = "x, y")""", "F.kt").declarations.single().default shouldBe "\"x, y\""
    }

    @Test
    fun `self-test - matching keys and equivalent defaults pass`() {
        sample(
            """
            store.getBoolean("skip_read", false)
            store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)
            store.getStringSet("pinned_catalogues", setOf())
            store.getString("pref_reader_flash_mode", "BLACK")
            store.getInt("scale", 1)
            store.getBoolean("zoukan_close_to_tray", true)
            """.trimIndent(),
        ).shouldBeEmpty()
    }

    @Test
    fun `self-test - a wrong default is caught`() {
        sample("""store.getBoolean("skip_read", true)""").single() shouldContain "\"skip_read\" defaults to true"
        sample("""store.getString("pref_reader_flash_mode", "WHITE")""").single() shouldContain "defaults to \"WHITE\""
        sample("""store.getInt("scale", 2)""").single() shouldContain "Android: 1"
    }

    @Test
    fun `self-test - a misspelled key or a lost prefix is caught`() {
        sample("""store.getBoolean("skip_reads", false)""").single() shouldContain "\"skip_reads\" is not an Android preference"
        sample("""store.getLong("last_catalogue_source", -1L)""").single() shouldContain "with another prefix"
    }

    @Test
    fun `self-test - a wrong type and an unparsable key are caught`() {
        sample("""store.getInt("skip_read", 0)""").single() shouldContain "is read as Int"
        sample("""store.getBoolean(someKey, false)""").single() shouldContain "Desktop.kt:1"
    }

    @Test
    fun `self-test - a listed difference passes, and a stale one fails`() {
        val known = listOf(KnownPreferenceDifference("skip_read", reviewed = true, why = "test"))
        sample("""store.getBoolean("skip_read", true)""", known).shouldBeEmpty()
        sample("""store.getBoolean("skip_read", false)""", known).single() shouldContain "matches Android now"
        sample("""store.getBoolean("skip_read", true)""", parity = "| Skip | No | `skip_read` |").shouldBeEmpty()
    }

    @Test
    fun `self-test - the real Android resolvers still read their sources`() {
        val android = androidResolver()
        android("ReadingMode.RIGHT_TO_LEFT.flagValue") shouldBe "2"
        android("ReaderBottomButton.BUTTONS_DEFAULTS") shouldBe "set(cbc, cbp, pl, vc, wb)"
        android("PagerConfig.PageLayout.AUTOMATIC") shouldBe "2"
        desktopResolver()("ReaderBottomButtons.DEFAULTS")!!.removePrefix("set(").removeSuffix(")").split(", ") shouldHaveSize 7
    }

    private class DesktopParse(val declarations: List<PreferenceDeclaration>, val unparsed: List<String>)

    private fun desktopDeclarations(): DesktopParse {
        val parsed = RepositorySources.kotlinFiles("desktop/src/main").map { PreferenceSource.parse(RepositorySources.text(it), it) }
        return DesktopParse(parsed.flatMap { it.declarations }, parsed.flatMap { it.unparsedStoreCalls })
    }

    private fun androidDeclarations(): List<PreferenceDeclaration> = androidFiles()
        .filter { "PreferenceStore" in RepositorySources.text(it) }
        .flatMap { PreferenceSource.parse(RepositorySources.text(it), it).declarations }

    private fun writeReport(desktop: List<PreferenceDeclaration>, androidKeys: Set<String>, compared: Int) {
        val file = File("build/reports/contract/preferences.txt")
        file.parentFile.mkdirs()
        file.writeText(
            "# ${desktop.size} desktop preference declarations, $compared compared with Android's\n" +
                desktop.joinToString("\n") { "${if (it.key in androidKeys) "android" else "desktop-only"}\t${it.key}\t${it.default}\t${it.location}" } + "\n",
        )
    }

    private companion object {

        /** Where the Android app's sources are: the app and the shared modules, main source sets only. */
        val ANDROID_ROOTS = listOf(
            "app/src/main", "domain/src", "core", "data/src", "source-api/src", "source-local/src",
            "presentation-core/src", "backup/src", "core-metadata/src",
        )

        fun androidFiles() = ANDROID_ROOTS.flatMap { RepositorySources.kotlinFiles(it) }.filterNot(RepositorySources::isTestSource)

        const val READER = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting"

        fun androidResolver() = ConstantResolver(
            androidFiles().map(RepositorySources::text),
            mapOf(
                "ReadingMode.RIGHT_TO_LEFT.flagValue" to {
                    val path = "$READER/ReadingMode.kt"
                    val hex = ConstantResolver.only(RepositorySources.text(path), Regex("""RIGHT_TO_LEFT\(\s*[^,()]+,\s*[^,()]+,\s*0x([0-9A-Fa-f]+)\s*,"""), path)
                    hex.toInt(16).toString()
                },
                "LocaleHelper.getDefaultEnabledLanguages()" to {
                    val path = "app/src/main/java/eu/kanade/tachiyomi/util/system/LocaleHelper.kt"
                    ConstantResolver.only(
                        RepositorySources.text(path),
                        Regex("""fun getDefaultEnabledLanguages\(\): Set<String> \{\s*return (setOf\([^\n]*\))\s*}"""),
                        path,
                    ).replace("java.util.", "")
                },
                "ReaderBottomButton.BUTTONS_DEFAULTS" to {
                    val path = "$READER/ReaderBottomButton.kt"
                    val text = RepositorySources.text(path)
                    val names = ConstantResolver.only(text, Regex("""BUTTONS_DEFAULTS = setOf\(([^)]*)\)"""), path)
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    ConstantResolver.setValue(names.map { ConstantResolver.only(text, Regex("""\b$it\("(\w+)""""), path) })
                },
            ),
        )

        fun desktopResolver() = ConstantResolver(
            RepositorySources.kotlinFiles("desktop/src/main").map(RepositorySources::text),
            mapOf(
                "ReaderBottomButtons.DEFAULTS" to {
                    val path = "desktop/src/main/kotlin/zoukan/desktop/app/DesktopPreferences.kt"
                    val body = ConstantResolver.bodiesOf(RepositorySources.text(path), "ReaderBottomButtons").single()
                    val names = ConstantResolver.only(body, Regex("""val DEFAULTS = setOf\(([^)]*)\)"""), path)
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    ConstantResolver.setValue(names.map { ConstantResolver.only(body, Regex("""const val $it = "(\w+)""""), path) })
                },
            ),
        )

        /**
         * Keys whose name or default differs from Android although PARITY.md does not list them.
         * `reviewed = false`: an unreviewed mismatch found when the test was written, waiting for
         * the maintainer to call it a bug or a documented difference.
         */
        val KNOWN_DIFFERENCES = listOf(
            KnownPreferenceDifference(
                "appState:storage_dir",
                reviewed = false,
                why = "Unreviewed mismatch found when the test was written. The desktop's default is \"\" " +
                    "(ZoukanStorage then uses its default folder); Android's is folderProvider.path(). " +
                    "An app-state key, so backups never carry it",
            ),
            KnownPreferenceDifference(
                "reader_bottom_buttons",
                reviewed = false,
                why = "Unreviewed mismatch found when the test was written. The desktop's default adds the " +
                    "reading mode button (\"rm\", an Android button) and the desktop-only scale type button " +
                    "(\"zst\") to Android's vc, wb, cbp, cbc, pl. Only an unset preference differs",
            ),
        )
    }
}
