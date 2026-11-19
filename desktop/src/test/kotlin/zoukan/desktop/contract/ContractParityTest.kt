package zoukan.desktop.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        val KNOWN_DIFFERENCES = emptyList<KnownPreferenceDifference>()
    }
}

/**
 * The file and folder names the desktop uses in the storage folder (downloads, the local
 * source, automatic backups) are the Android app's, each read from where the Android source
 * and the desktop source define it. A name that differs on purpose, or is waiting for a
 * decision, is a [KnownNameDifference]; it must still differ exactly as recorded.
 *
 * The chapter and entry folder names themselves (DiskUtil.buildValidFilename, the scanlator
 * prefix and the URL hash) are behaviour, not constants, and are checked by the download tests.
 * Covers and custom covers live in the app's cache, not the storage folder, so they are not here.
 */
class StorageNamesParityTest {

    @Test
    fun `the storage folder names are Android's`() {
        check(PAIRS.size >= 14) { "Only ${PAIRS.size} names are compared" }
        assertNoProblems("Storage folder names that differ from Android", StorageNames.problems(PAIRS))
    }

    // --- Self-tests on text in memory ---

    private val androidText = """private const val DOWNLOADS_PATH = "downloads""""

    @Test
    fun `self-test - equal names pass and a wrong name is caught`() {
        fun pair(desktop: String, known: KnownNameDifference? = null) = NamePair(
            "Downloads folder",
            StorageNames.from("Android.kt", androidText, Regex("""DOWNLOADS_PATH = "([^"]+)"""")),
            StorageNames.from("Desktop.kt", desktop, Regex("""const val DOWNLOADS = "([^"]+)"""")),
            known,
        )
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "downloads""""))).shouldBeEmpty()
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "download""""))).single() shouldContain
            "desktop \"download\", Android \"downloads\""
        // A recorded difference passes while it holds, and is stale once the names agree
        val known = KnownNameDifference("download", "downloads", reviewed = true, why = "test")
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "download"""", known))).shouldBeEmpty()
        StorageNames.problems(listOf(pair("""const val DOWNLOADS = "downloads"""", known))).single() shouldContain "stale"
    }

    @Test
    fun `self-test - a definition that moved or appears twice fails instead of passing`() {
        val missing = NamePair("x", StorageNames.from("A.kt", androidText, Regex("""NOPE = "([^"]+)"""")), { "downloads" }, null)
        StorageNames.problems(listOf(missing)).single() shouldContain "expected one match"
        assertThrows<IllegalStateException> { StorageNames.from("A.kt", "$androidText\n$androidText", Regex("""DOWNLOADS_PATH = "([^"]+)""""))() }
    }

    @Test
    fun `self-test - every source path still exists`() {
        PAIRS.forEach { pair ->
            pair.android()
            pair.desktop()
        }
        PAIRS.map { it.what }.toSet().size shouldBe PAIRS.size
    }

    private companion object {
        const val STORAGE_MANAGER = "domain/src/androidMain/kotlin/tachiyomi/domain/storage/service/StorageManager.kt"
        const val ZOUKAN_STORAGE = "desktop/src/main/kotlin/zoukan/desktop/storage/ZoukanStorage.kt"
        const val DOWNLOADER = "app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt"
        const val DOWNLOAD_PATHS = "desktop/src/main/kotlin/zoukan/desktop/download/DownloadPaths.kt"
        const val CHAPTER_DOWNLOADER = "desktop/src/main/kotlin/zoukan/desktop/download/ChapterDownloader.kt"
        const val COMIC_INFO = "core-metadata/src/main/java/tachiyomi/core/metadata/comicinfo/ComicInfo.kt"
        const val COMIC_INFO_XML = "desktop/src/main/kotlin/zoukan/desktop/download/ComicInfoXml.kt"
        const val ANDROID_LOCAL_SOURCE = "source-local/src/androidMain/kotlin/tachiyomi/source/local/LocalSource.kt"
        const val ANDROID_LOCAL_COVERS = "source-local/src/androidMain/kotlin/tachiyomi/source/local/image/LocalCoverManager.kt"
        const val DESKTOP_LOCAL_SOURCE = "desktop/src/main/kotlin/zoukan/desktop/local/LocalSource.kt"
        const val BACKUP_CREATOR = "app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt"
        const val DESKTOP_BACKUPS = "desktop/src/main/kotlin/zoukan/desktop/backup/DesktopBackups.kt"
        const val DISK_UTIL = "core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/util/storage/DiskUtil.kt"

        fun file(path: String, regex: String) = StorageNames.from(path, null, Regex(regex))

        val PAIRS = listOf(
            NamePair("Downloads folder", file(STORAGE_MANAGER, """DOWNLOADS_PATH = "([^"]+)""""), file(ZOUKAN_STORAGE, """const val DOWNLOADS = "([^"]+)"""")),
            NamePair("Local source folder", file(STORAGE_MANAGER, """LOCAL_SOURCE_PATH = "([^"]+)""""), file(ZOUKAN_STORAGE, """const val LOCAL = "([^"]+)"""")),
            NamePair(
                "Automatic backups folder",
                file(STORAGE_MANAGER, """AUTOMATIC_BACKUPS_PATH = "([^"]+)""""),
                file(ZOUKAN_STORAGE, """const val AUTOMATIC_BACKUPS = "([^"]+)""""),
            ),
            NamePair("Unfinished download suffix", file(DOWNLOADER, """TMP_DIR_SUFFIX = "([^"]+)""""), file(DOWNLOAD_PATHS, """TMP_SUFFIX = "([^"]+)"""")),
            NamePair(
                "Downloaded chapter archive extension",
                file(DOWNLOADER, """zip\.renameTo\("\${'$'}dirname\.(\w+)"\)"""),
                file(CHAPTER_DOWNLOADER, """File\(mangaDir, "\${'$'}chapterName\.(\w+)"\)"""),
            ),
            NamePair("ComicInfo file", file(COMIC_INFO, """const val COMIC_INFO_FILE = "([^"]+)""""), file(COMIC_INFO_XML, """const val FILE = "([^"]+)"""")),
            NamePair(
                "Local source ComicInfo archive",
                file(ANDROID_LOCAL_SOURCE, """const val COMIC_INFO_ARCHIVE = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """const val COMIC_INFO_ARCHIVE = "([^"]+)""""),
            ),
            NamePair(
                "Local source encrypted cover",
                file(ANDROID_LOCAL_COVERS, """const val COVER_ARCHIVE_NAME = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """const val COVER_ARCHIVE = "([^"]+)""""),
            ),
            NamePair(
                "Local source cover base name (any cover.* is the cover)",
                file(ANDROID_LOCAL_COVERS, """nameWithoutExtension\.equals\("(\w+)", ignoreCase = true\)"""),
                file(DESKTOP_LOCAL_SOURCE, """nameWithoutExtension\.equals\("(\w+)", ignoreCase = true\)"""),
            ),
            NamePair(
                "New local source cover file",
                file(ANDROID_LOCAL_COVERS, """const val DEFAULT_COVER_NAME = "([^"]+)""""),
                file(DESKTOP_LOCAL_SOURCE, """File\(dir, "(cover\.[^"]+)"\)"""),
                KnownNameDifference(
                    desktop = "cover.\$ext",
                    android = "cover.jpg",
                    reviewed = true,
                    why = "On purpose (PARITY.md, Local source): the desktop names the cover after the image's real type; " +
                        "Android always writes cover.jpg. Each app takes any cover.* image, so each reads the other's",
                ),
            ),
            NamePair(
                "Automatic backup date format",
                file(BACKUP_CREATOR, """SimpleDateFormat\("([^"]+)""""),
                file(DESKTOP_BACKUPS, """ofPattern\("([^"]+)"\)"""),
            ),
            NamePair(
                "Automatic backup extension",
                file(BACKUP_CREATOR, """"\$\{BuildConfig\.APPLICATION_ID\}_\${'$'}date\.(\w+)""""),
                file(DESKTOP_BACKUPS, """ofPattern\("[^"]+"\)\)\}\.(\w+)""""),
            ),
            NamePair(
                "Automatic backup prefix",
                file(BACKUP_CREATOR, """return "(\$\{[^}]+\})_\${'$'}date"""),
                file(DESKTOP_BACKUPS, """"([^_"]+)_\$\{LocalDateTime"""),
                KnownNameDifference(
                    desktop = "zoukan",
                    android = "\${BuildConfig.APPLICATION_ID}",
                    reviewed = true,
                    why = "On purpose (PARITY.md, Backups; THREAT-MODEL.md residual risk 4): each app prunes only its own " +
                        "prefix, so neither deletes the other's automatic backups; both restore any .tachibk",
                ),
            ),
            NamePair(
                "No-media marker in the downloads folder",
                {
                    val manager = RepositorySources.text(STORAGE_MANAGER)
                    check(Regex("""createDirectory\(DOWNLOADS_PATH\)\.also \{\s*DiskUtil\.createNoMediaFile""").containsMatchIn(manager)) {
                        "$STORAGE_MANAGER no longer creates the no-media file in the downloads folder; update the test"
                    }
                    ConstantResolver.only(RepositorySources.text(DISK_UTIL), Regex("""const val NOMEDIA_FILE = "([^"]+)""""), DISK_UTIL)
                },
                {
                    val writers = RepositorySources.kotlinFiles("desktop/src/main").filter { "nomedia" in RepositorySources.text(it).lowercase() }
                    if (writers.isEmpty()) "(none)" else "named in ${writers.joinToString()}"
                },
                KnownNameDifference(
                    desktop = "(none)",
                    android = ".nomedia",
                    reviewed = true,
                    why = "On purpose (PARITY.md, Storage folder: .nomedia files): the file only steers Android's media " +
                        "scanner; the desktop keeps Android's and writes none",
                ),
            ),
        )
    }
}

/**
 * Every file and symbol a row of `desktop/PARITY.md` names in backticks still exists, so a
 * difference cannot outlive the code it describes. Which backticked text counts as a reference
 * ([ParityReferences.classify]):
 *
 * - a path: text with a `/` whose first part is a top-level folder of the repository
 *   (`desktop/THREAT-MODEL.md`), or that ends in `.kt`/`.kts`. `desktop/.../download/X.kt`
 *   means `X.kt` somewhere under `desktop/`; a path that does not start at a top-level folder
 *   (`storage/StorageFolderPrivacy.kt`, `SafeDelete.kt`) must end some file's path.
 * - a symbol: a camelCase or PascalCase identifier (`planCleanup`, `MergedSource`) or a
 *   snake_case one (`crop_borders_continues_vertical`, `track_dupes`), which must appear as a
 *   whole word in some main source file (Kotlin, Java or SQLDelight; tests do not count).
 *
 * Anything else in backticks (`downloads/`, `0700`, `about:blank`, `src:local`, `http.agent`)
 * is a name the app shows or uses, not a reference, and is left alone.
 */
class ParityReferencesTest {

    @Test
    fun `every file and symbol PARITY md names still exists`() {
        val references = ParityReferences.references(RepositorySources.text("desktop/PARITY.md"))
        // Today's document has about 50; a parser that finds almost none is broken
        check(references.size >= 30) { "Only ${references.size} references found in PARITY.md; the parser is broken" }
        check(references.count { it.kind == ParityReferences.Kind.PATH } >= 10) { "Too few path references found in PARITY.md" }
        assertNoProblems("PARITY.md names things that no longer exist", ParityReferences.problems(references, ParityReferences.repositoryIndex()))
    }

    // --- Self-tests on text in memory ---

    private val index = ParityReferences.Index(
        paths = listOf("desktop/src/main/kotlin/zoukan/desktop/download/SafeDelete.kt", "desktop/PARITY.md"),
        dirs = setOf("desktop", "desktop/src/main/kotlin/zoukan/desktop/download"),
        words = setOf("planCleanup", "track_dupes", "MergedSource"),
    )

    private fun problemsOf(row: String) = ParityReferences.problems(ParityReferences.references(row), index)

    @Test
    fun `self-test - existing files and symbols pass`() {
        problemsOf("| A | Yes | Code: `desktop/.../download/SafeDelete.kt` (`planCleanup`), `download/SafeDelete.kt`, `SafeDelete.kt`, `MergedSource`, `track_dupes`, `desktop/PARITY.md` |")
            .shouldBeEmpty()
        problemsOf("| A | Yes | `downloads/`, `0700`, `src:local`, `http.agent`, `about:blank`, `--private`, `tar` |").shouldBeEmpty()
        ParityReferences.references("| A | Yes | `downloads/`, `0700`, `tar`, `java.net.useSystemProxies` |").shouldBeEmpty()
    }

    @Test
    fun `self-test - a removed file or symbol is caught`() {
        problemsOf("| Clean up | Yes | Code: `desktop/.../download/Gone.kt` |").single() shouldContain "desktop/.../download/Gone.kt"
        problemsOf("| Clean up | Yes | `planCleanupRenamed` |").single() shouldContain "planCleanupRenamed"
        problemsOf("| Clean up | Yes | `removed_pref_key` |").single() shouldContain "\"Clean up\""
        problemsOf("| Clean up | Yes | `desktop/.../browser/` |").single() shouldContain "desktop/.../browser/"
    }

    @Test
    fun `self-test - the header and separator rows are not entries`() {
        ParityReferences.references("| Android feature | Desktop | Notes |\n|---|---|---|").shouldBeEmpty()
        ParityReferences.classify("SafeDelete.kt") shouldBe ParityReferences.Kind.PATH
    }
}

/**
 * Every trust boundary `desktop/THREAT-MODEL.md` lists has a row in its "Checked by" table, and
 * every test class a row names exists and has tests.
 * The classes may be in any module (`extension-jvm`'s tests are not on this class path), so they
 * are found as source files: `zoukan.extension.Foo` is `<module>/src/<test source set>/kotlin/zoukan/extension/Foo.kt`
 * declaring `class Foo` with an `@Test`. A row with no test must say why in its Gap cell.
 */
class ThreatModelCoverageTest {

    @Test
    fun `every trust boundary names tests that exist`() {
        val text = RepositorySources.text("desktop/THREAT-MODEL.md")
        val boundaries = ThreatModel.boundaries(text)
        check(boundaries.size >= 10) { "Only ${boundaries.size} trust boundaries found in THREAT-MODEL.md; the parser is broken" }
        val rows = ThreatModel.rows(text)
        assertNoProblems(
            "THREAT-MODEL.md's Checked by table",
            ThreatModel.problems(boundaries, rows, ThreatModel::testSourceProblem),
        )
    }

    // --- Self-tests on text in memory ---

    private val document = """
        ## Trust boundaries

        Remote:

        - Manga sites and CDNs: HTML, JSON, images.
        - The DNS over HTTPS provider (Quad9 unless the user chose another): the answer
          to every public name.

        Local:

        - Files the user opens: CBZ chapters.

        ### Checked by

        | Boundary | Checked by | Gap |
        |---|---|---|
        | Manga sites and CDNs | | Nothing contains extension code |
        | The DNS over HTTPS provider | `zoukan.desktop.network.AppDnsResolverTest` | |
        | Files the user opens | `zoukan.desktop.local.ArchivesTest`, `zoukan.desktop.local.GoneTest` | |

        ## Extensions
    """.trimIndent()

    private val existing = setOf("zoukan.desktop.network.AppDnsResolverTest", "zoukan.desktop.local.ArchivesTest")

    private fun problems(text: String) = ThreatModel.problems(ThreatModel.boundaries(text), ThreatModel.rows(text)) { name ->
        if (name in existing) null else "$name does not exist"
    }

    @Test
    fun `self-test - the boundaries and rows are read`() {
        ThreatModel.boundaries(document) shouldBe listOf("Manga sites and CDNs", "The DNS over HTTPS provider", "Files the user opens")
        ThreatModel.rows(document).map { it.tests.size } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `self-test - a missing test class is caught`() {
        problems(document).single() shouldContain "zoukan.desktop.local.GoneTest does not exist"
    }

    @Test
    fun `self-test - a boundary without a row, a row without a boundary and a row with neither tests nor gap are caught`() {
        val fixed = document.replace(", `zoukan.desktop.local.GoneTest`", "")
        problems(fixed).shouldBeEmpty()
        problems(fixed.replace("| Files the user opens |", "| Files the user reads |")).let {
            it.any { p -> "\"Files the user opens\" has no row" in p } shouldBe true
            it.any { p -> "\"Files the user reads\" names no boundary" in p } shouldBe true
        }
        problems(fixed.replace("| Nothing contains extension code |", "| |")).single() shouldContain "neither tests nor a gap"
    }

    @Test
    fun `self-test - the real test source lookup finds a desktop and an extension-jvm test, and not a made-up one`() {
        ThreatModel.testSourceProblem("zoukan.desktop.contract.ThreatModelCoverageTest") shouldBe null
        ThreatModel.testSourceProblem("zoukan.extension.ExtensionVerificationTest") shouldBe null
        ThreatModel.testSourceProblem("zoukan.desktop.NoSuchTest")!! shouldContain "no test source"
        // A contract's subclass declared in a shared file, its tests inherited from the contract
        ThreatModel.testSourceProblem("zoukan.desktop.secrets.MacKeychainStoreContractTest") shouldBe null
        ThreatModel.testSourceProblem("zoukan.desktop.secrets.FileSecretStoreContractTest") shouldBe null
        ThreatModel.testSourceProblem("zoukan.desktop.secrets.NoSuchStoreContractTest")!! shouldContain "no test source"
        // A class that exists but has no tests
        ThreatModel.testSourceProblem("zoukan.desktop.testing.FreshInjekt")!! shouldContain "has no @Test"
    }
}
