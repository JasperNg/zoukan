package zoukan.desktop.property

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.util.SafePath
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.download.FileNames
import zoukan.desktop.property.PortableName.Problem
import zoukan.desktop.ui.reader.PageImages
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Whether a string is one file or folder name that works on Windows, macOS and Linux, and what is
 * wrong with it when it is not.
 */
object PortableName {

    enum class Problem {
        EMPTY,

        /** `.` or `..`: the folder itself or its parent. */
        DOT_NAME,

        /** One of `<>:"/\|?*`, reserved on Windows (`/` everywhere). */
        RESERVED_CHARACTER,

        /** U+0000 to U+001F, refused by Windows (and U+0000 everywhere). */
        CONTROL_CHARACTER,

        /** Half of a surrogate pair: not valid UTF-8, so macOS refuses it and Java writes `?`. */
        LONE_SURROGATE,

        /** Windows drops a trailing dot or space, so the name on disk is another one. */
        TRAILING_DOT_OR_SPACE,

        /** CON, PRN, AUX, NUL, COM0-9, LPT0-9 (also with an extension): a device on Windows, not a file. */
        WINDOWS_DEVICE_NAME,

        /** Over 255 UTF-8 bytes (Linux, macOS) or 255 UTF-16 units (Windows). */
        TOO_LONG,

        /** Over the limit the Android app keeps to (240 bytes for a whole name). */
        OVER_ANDROID_LIMIT,

        /** Resolved against a folder, it ends up somewhere else. */
        ESCAPES_FOLDER,
    }

    private const val RESERVED = "<>:\"/\\|?*"

    private val deviceNames: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        // Windows also treats the superscript digits 1-3 as device numbers
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
            add("COM$d")
            add("LPT$d")
        }
    }

    /** Everything wrong with [name] as a file name; empty when it is a valid name everywhere. */
    fun problems(name: String, androidLimitBytes: Int = 240): Set<Problem> {
        val found = linkedSetOf<Problem>()
        if (name.isEmpty()) return setOf(Problem.EMPTY)
        if (name == "." || name == "..") found += Problem.DOT_NAME
        if (name.any { it in RESERVED }) found += Problem.RESERVED_CHARACTER
        if (name.any { it.code < 0x20 }) found += Problem.CONTROL_CHARACTER
        if (hasLoneSurrogate(name)) found += Problem.LONE_SURROGATE
        if (name.last() == '.' || name.last() == ' ') found += Problem.TRAILING_DOT_OR_SPACE
        if (isWindowsDeviceName(name)) found += Problem.WINDOWS_DEVICE_NAME
        val utf8 = name.toByteArray(Charsets.UTF_8).size
        if (utf8 > 255 || name.length > 255) found += Problem.TOO_LONG
        if (utf8 > androidLimitBytes) found += Problem.OVER_ANDROID_LIMIT
        if (escapesFolder(name)) found += Problem.ESCAPES_FOLDER
        return found
    }

    fun isWindowsDeviceName(name: String): Boolean =
        name.substringBefore('.').trimEnd(' ').uppercase() in deviceNames

    fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                i += 2
            } else {
                if (c.isLowSurrogate()) return true
                i++
            }
        }
        return false
    }

    private val folder: Path = Path.of("downloads", "source").toAbsolutePath()

    private fun escapesFolder(name: String): Boolean = try {
        val resolved = folder.resolve(name).normalize()
        resolved.parent != folder
    } catch (_: InvalidPathException) {
        // Not a path on this OS at all; the character checks name the reason
        false
    }
}

/**
 * Keeps [DiskUtil] and [DownloadProvider] (the Android code the name properties compare with) the
 * same as the Android source: each copied function's body must be the Android one, comments and
 * spacing aside. The two list functions take the settings as parameters, so their Android bodies
 * are pinned as text instead: when Android changes them, update the copy and the pinned text.
 */
class AndroidNameOracleSourceTest {

    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "desktop").isDirectory }
    }

    private fun read(path: String) = File(repoRoot, path).readText()

    private val diskUtil by lazy { read("core/common/src/androidMain/kotlin/eu/kanade/tachiyomi/util/storage/DiskUtil.kt") }
    private val downloadProvider by lazy { read("app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt") }
    private val oracle by lazy { read("desktop/src/test/kotlin/zoukan/desktop/property/AndroidNameOracle.kt") }

    @Test
    fun `the copied DiskUtil functions are Android's`() {
        for (name in listOf("buildValidFilename", "truncateToLength", "isValidFatFilenameChar")) {
            withClue(name) { body(oracle, name) shouldBe body(diskUtil, name) }
        }
        Regex("""const val MAX_FILE_NAME_BYTES = (\d+)""").find(diskUtil)!!.groupValues[1] shouldBe DiskUtil.MAX_FILE_NAME_BYTES.toString()
    }

    @Test
    fun `the copied DownloadProvider functions are Android's`() {
        for (name in listOf("getChapterDirName", "sanitizeChapterName")) {
            withClue(name) { body(oracle, name) shouldBe body(downloadProvider, name) }
        }
        // Rewritten with the settings as parameters; Android's bodies pinned as they were copied
        body(downloadProvider, "getLegacyChapterDirNames") shouldBe LEGACY_NAMES_BODY
        body(downloadProvider, "getValidChapterDirNames") shouldBe VALID_NAMES_BODY
    }

    @Test
    fun `self-test - the body of a function is found, without comments and spaces`() {
        val source = """
            fun a(x: Int = f(1)): Int {
                // a comment { with a brace
                return /* SY --> */ x /* SY <-- */ + 1
            }
            fun b() { }
        """.trimIndent()
        body(source, "a") shouldBe "{returnx+1}"
        body(source, "b") shouldBe "{}"
    }

    private fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue(clue, block)

    companion object {
        const val LEGACY_NAMES_BODY =
            "{valsanitizedChapterName=sanitizeChapterName(chapterName)valchapterNameV1=DiskUtil.buildValidFilename(when{" +
                "!chapterScanlator.isNullOrBlank()->\"\${chapterScanlator}_\$sanitizedChapterName\"else->sanitizedChapterName},)" +
                "valotherChapterDirName=getChapterDirName(chapterName,chapterScanlator,chapterUrl," +
                "!libraryPreferences.disallowNonAsciiFilenames().get(),!downloadPreferences.includeChapterUrlHash().get(),)" +
                "returnbuildList(2){add(chapterNameV1)add(otherChapterDirName)}}"
        const val VALID_NAMES_BODY =
            "{valchapterDirName=getChapterDirName(chapterName,chapterScanlator,chapterUrl)" +
                "vallegacyChapterDirNames=getLegacyChapterDirNames(chapterName,chapterScanlator,chapterUrl)" +
                "returnbuildList{add(chapterDirName)add(\"\$chapterDirName.cbz\")legacyChapterDirNames.forEach{add(it)add(\"\$it.cbz\")}}}"

        /** The body of the first function named [name] in [source], with comments and whitespace taken out. */
        fun body(source: String, name: String): String {
            val text = stripComments(source)
            val start = text.indexOf("fun $name(")
            check(start >= 0) { "fun $name not found" }
            var i = text.indexOf('(', start)
            var depth = 0
            while (true) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                i++
            }
            val open = text.indexOf('{', i)
            depth = 0
            var end = open
            while (true) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                end++
            }
            return text.substring(open, end + 1).filterNot { it.isWhitespace() }
        }

        private fun stripComments(source: String): String =
            source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")
    }
}

/**
 * Download folder and file names, for any title, chapter name, scanlator and chapter URL:
 * - the desktop builds the name the Android app builds ([DiskUtil], [DownloadProvider] are
 *   Android's code) whenever Android's name is valid on every OS; otherwise it builds a valid one
 *   and still looks downloads up by Android's name, so a downloads folder works in both apps;
 * - the name is one valid file name on Windows, macOS and Linux, inside its folder;
 * - the same input always gives the same name, and a name that is already valid is kept as it is,
 *   so titles that differ only by case or Unicode normalisation stay apart, as on Android.
 *
 * The desktop has its own copy of DiskUtil.buildValidFilename ([FileNames]), because DiskUtil is
 * Android-only; DownloadPaths builds the chapter name with it as DownloadProvider does.
 */
class DownloadNamePropertyTest {

    private val strings = arbFrom { NastyStrings.next(it) }

    private data class ChapterInput(val name: String, val scanlator: String?, val url: String, val ascii: Boolean, val hash: Boolean)

    private val chapters = arbFrom { r ->
        ChapterInput(
            name = NastyStrings.next(r),
            scanlator = if (r.nextInt(3) == 0) null else NastyStrings.next(r, maxPieces = 4),
            url = NastyStrings.next(r, maxPieces = 4, padding = false),
            ascii = r.nextBoolean(),
            hash = r.nextBoolean(),
        )
    }

    @Test
    fun `entry and source folder names are Android's, or valid ones that still find Android's`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                // The entry and source name, and the chapter name's shorter limit
                for (limit in listOf(FileNames.MAX_FILE_NAME_BYTES, 229)) {
                    val android = DiskUtil.buildValidFilename(title, limit, ascii)
                    val desktop = FileNames.buildValidFilename(title, limit, ascii)
                    if (isPortable(android)) {
                        desktop shouldBe android
                    } else {
                        PortableName.problems(desktop) shouldBe emptySet()
                    }
                }
                // Android's entry folder is looked up
                val names = paths(ascii, hash = false).mangaDirNames(title)
                names.first() shouldBe FileNames.buildValidFilename(title, disallowNonAscii = ascii)
                names shouldContainAll listOf(DiskUtil.buildValidFilename(title, disallowNonAscii = ascii), DiskUtil.buildValidFilename(title, disallowNonAscii = !ascii))
                    .filter { isPathHere(it) }
                names.none { !isPathHere(it) } shouldBe true
            }
        }
    }

    @Test
    fun `truncation is Android's for any limit`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { r -> NastyStrings.next(r) to r.nextInt(0, 300) }) { (s, max) ->
            FileNames.truncateToLength(s, max) shouldBe DiskUtil.truncateToLength(s, max)
        }
    }

    @Test
    fun `chapter folder names are Android's, also the older names looked up`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), chapters) { c ->
            val name = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            val androidName = DownloadProvider.getChapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            if (isPortable(androidName)) name shouldBe androidName else PortableName.problems(name) shouldBe emptySet()

            // Every name Android looks a chapter up by is looked up on desktop too, the current one first
            val desktop = paths(c.ascii, c.hash).validChapterNames(chapter(c))
            val android = DownloadProvider.getValidChapterDirNames(c.name, c.scanlator, c.url, c.ascii, c.hash)
            desktop.take(2) shouldBe listOf(name, "$name.cbz")
            desktop shouldContainAll android.filter { isPathHere(it) }
            desktop.none { !isPathHere(it) } shouldBe true
        }
    }

    @Test
    fun `entry folder names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (ascii in listOf(false, true)) {
                val name = FileNames.buildValidFilename(title, disallowNonAscii = ascii)
                PortableName.problems(name).forEach { found.add(it, title, "\"${escaped(name)}\" (ascii=$ascii)") }
                // Stable: the same title always gives the same name
                FileNames.buildValidFilename(title, disallowNonAscii = ascii) shouldBe name
            }
        }
        assertAllValid(found)
    }

    @Test
    fun `chapter folder and CBZ names are valid on every OS`() = runBlocking<Unit> {
        val found = Found()
        checkAll(PropertyRuns.config(ITERATIONS), chapters) { c ->
            val name = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash)
            for (file in listOf(name, "$name.cbz", name + DownloadPaths.TMP_SUFFIX)) {
                val input = (if (c.scanlator.isNullOrBlank()) "" else c.scanlator + "_") + c.name.ifBlank { "Chapter" }
                PortableName.problems(file).forEach { found.add(it, input, "\"${escaped(file)}\" (ascii=${c.ascii}, hash=${c.hash})") }
            }
            DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, c.ascii, c.hash) shouldBe name
        }
        assertAllValid(found)
    }

    @Test
    fun `a name that is already valid is kept as it is`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            val untouched = title.isNotEmpty() &&
                title.trim('.', ' ') == title &&
                title.none { it.code < 0x20 || it.code == 0x7f || it in "<>:\"/\\|?*" } &&
                title.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_FILE_NAME_BYTES &&
                !PortableName.hasLoneSurrogate(title) &&
                !PortableName.isWindowsDeviceName(title)
            if (untouched) FileNames.buildValidFilename(title) shouldBe title
        }
    }

    private class Found {
        val unknown = Counterexamples<Problem>()

        fun add(problem: Problem, input: String, detail: String) = unknown.add(problem, input, detail)
    }

    private fun isPortable(name: String) = PortableName.problems(name).isEmpty()

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /**
     * Whether [name] can be a folder on this OS at all: on Windows `?` is rejected by `Path.of`,
     * and a device name (`NUL`, `CON.1`) names the device rather than a folder so it is not looked up.
     */
    private fun isPathHere(name: String) =
        runCatching { Path.of(name) }.isSuccess && !(isWindows && PortableName.isWindowsDeviceName(name))

    private fun assertAllValid(found: Found) {
        check(found.unknown.kinds().isEmpty()) { "Invalid names (seed ${PropertyRuns.seed}):\n" + found.unknown.report() }
    }

    private fun chapter(c: ChapterInput) = Chapter.create().copy(name = c.name, scanlator = c.scanlator, url = c.url)

    /** Made once for each setting: a mock made on every iteration fails now and then under the JIT. */
    private val pathsBySetting = listOf(false, true).flatMap { a -> listOf(false, true).map { h -> (a to h) to newPaths(a, h) } }.toMap()

    private fun paths(ascii: Boolean, hash: Boolean): DownloadPaths = pathsBySetting.getValue(ascii to hash)

    private fun newPaths(ascii: Boolean, hash: Boolean): DownloadPaths {
        val library = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames().get() } returns ascii }
        val download = mockk<DownloadPreferences> { every { includeChapterUrlHash().get() } returns hash }
        return DownloadPaths(mockk(relaxed = true), mockk(relaxed = true), library, download)
    }

    companion object {
        private const val ITERATIONS = 500
    }
}

/**
 * Names [DownloadNamePropertyTest] found that the Android app builds but that are not valid on
 * every OS, with the smallest inputs found. The desktop writes a portable name for each, and still
 * looks downloads up by Android's name, so a folder Android (or an older desktop) wrote is found.
 */
class DownloadNameKnownDefectsTest {

    private val paths = paths(ascii = false, hash = false)
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The desktop writes a valid name, and looks the entry up by Android's name too. */
    private fun assertValid(input: String, name: String) {
        PortableName.problems(name) shouldBe emptySet()
        val android = DiskUtil.buildValidFilename(input)
        name shouldNotBe android
        paths.mangaDirNames(input).first() shouldBe name
        // A name this OS cannot have as a folder (`?`, or a device name on Windows) cannot be on disk, so it is not looked up
        if (isPathHere(android)) {
            paths.mangaDirNames(input) shouldContain android
        } else {
            paths.mangaDirNames(input) shouldNotContain android
        }
    }

    /**
     * Whether [name] can be a folder on this OS: [Path.of] accepts it and, on
     * Windows, it is not a device name (`NUL`, `CON.1`: `Path.of` accepts those too, but they name
     * the device, never a folder, so [DownloadPaths] does not look them up there).
     */
    private fun isPathHere(name: String) =
        runCatching { Path.of(name) }.isSuccess && !(isWindows && PortableName.isWindowsDeviceName(name))

    @Test
    fun `a Windows device name is not a folder name`() {
        assertValid("nul", FileNames.buildValidFilename("nul"))
        FileNames.buildValidFilename("nul") shouldBe "nul_"
        assertValid("CON.txt", FileNames.buildValidFilename("CON.txt"))
        FileNames.buildValidFilename("CON.txt") shouldBe "CON_.txt"
        // A chapter name with the hash turned off, and CON.<anything> with it on
        PortableName.problems(DownloadPaths.chapterDirName("CON", null, "/c/1", disallowNonAscii = false, includeHash = false)) shouldBe emptySet()
        PortableName.problems(DownloadPaths.chapterDirName("CON.1", null, "/c/1", disallowNonAscii = false, includeHash = true)) shouldBe emptySet()
        // CON with the hash is valid as Android names it (CON_xxxxxx), so it stays Android's
        DownloadPaths.chapterDirName("CON", null, "/c/1", disallowNonAscii = false, includeHash = true) shouldBe
            DownloadProvider.getChapterDirName("CON", null, "/c/1", false, true)
        for (hash in listOf(false, true)) {
            val names = paths(ascii = false, hash = hash).validChapterNames(chapter("CON.1"))
            val android = DownloadProvider.getChapterDirName("CON.1", null, "/c/1", false, hash)
            if (isPathHere(android)) {
                names shouldContain android
                names shouldContain "$android.cbz"
            } else {
                names shouldNotContain android
                names shouldNotContain "$android.cbz"
            }
        }
    }

    @Test
    fun `a name cut at the limit does not end in a space or a dot`() {
        val input = "a".repeat(239) + " b"
        assertValid(input, FileNames.buildValidFilename(input))
        val dot = "a".repeat(239) + ".b"
        assertValid(dot, FileNames.buildValidFilename(dot))
        FileNames.buildValidFilename(dot) shouldBe "a".repeat(239)
    }

    @Test
    fun `a chapter name cut at a trailing space still finds Android's CBZ`() {
        val cut229 = "a".repeat(228) + " b"
        val android229 = DownloadProvider.getChapterDirName(cut229, null, "/c/1", false, false)
        android229 shouldBe "a".repeat(228) + " "
        val names229 = paths(ascii = false, hash = false).validChapterNames(chapter(cut229))
        names229.first() shouldBe "a".repeat(228)
        // On Windows a folder ending in a space is not a valid path, but the CBZ file ("... .cbz") is
        names229 shouldContain "$android229.cbz"
        if (isPathHere(android229)) names229 shouldContain android229 else names229 shouldNotContain android229

        val cut240 = "a".repeat(239) + " b"
        val androidV1 = DiskUtil.buildValidFilename(cut240)
        androidV1 shouldBe "a".repeat(239) + " "
        val names240 = paths(ascii = false, hash = false).validChapterNames(chapter(cut240))
        names240 shouldContain "$androidV1.cbz"
        if (isPathHere(androidV1)) names240 shouldContain androidV1 else names240 shouldNotContain androidV1
    }

    @Test
    fun `half a surrogate pair does not reach the name`() {
        assertValid("\udbff", FileNames.buildValidFilename("\udbff"))
        FileNames.buildValidFilename("a\udbffb") shouldBe "a_b"
        // As an older desktop wrote it on macOS and Linux, where Java writes the half pair as '?'
        if (isPathHere("a?b")) paths.mangaDirNames("a\udbffb") shouldContain "a?b"
    }

    @Test
    fun `half a surrogate pair in a long name does not become a question mark`() {
        val input = "\ud800" + "a".repeat(240)
        val name = FileNames.buildValidFilename(input)
        (Problem.RESERVED_CHARACTER in PortableName.problems(name)) shouldBe false
        assertValid(input, name)
    }

    private fun chapter(name: String) = Chapter.create().copy(name = name, url = "/c/1")

    private fun paths(ascii: Boolean, hash: Boolean): DownloadPaths {
        val library = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames().get() } returns ascii }
        val download = mockk<DownloadPreferences> { every { includeChapterUrlHash().get() } returns hash }
        return DownloadPaths(mockk(relaxed = true), mockk(relaxed = true), library, download)
    }
}

/**
 * The two other name builders besides the downloads': saved pages and covers and the per-entry
 * Pictures folder ([PageImages.validFilename]), and the settings files ([SafePath.buildValidFilename]).
 * Every name they give is one name on Windows, macOS and Linux: no Windows device name, no half
 * surrogate pair (also not one made by the cut at the length limit). A settings file written under
 * the name an older version gave is still found, never renamed or lost.
 */
class SavedFileNamePropertyTest {

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

    private val strings = arbFrom { NastyStrings.next(it) }

    /** Problems a builder that keeps to 240 bytes may not have. */
    private fun problems(name: String) = PortableName.problems(name)

    @Test
    fun `saved picture names are valid on every OS`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { title ->
            for (limit in listOf(240, 235, 21)) {
                val name = PageImages.validFilename(title, limit)
                withClue(title, name) { problems(name) shouldBe emptySet() }
                (name.toByteArray(Charsets.UTF_8).size <= limit) shouldBe true
                PageImages.validFilename(title, limit) shouldBe name
            }
        }
    }

    @Test
    fun `settings file names are valid on every OS`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { key ->
            val name = SafePath.buildValidFilename(key)
            withClue(key, name) { problems(name) shouldBe emptySet() }
            SafePath.buildValidFilename(key) shouldBe name
        }
    }

    @Test
    fun `a name that is already valid is kept as it is`() = runBlocking<Unit> {
        checkAll(PropertyRuns.config(ITERATIONS), strings) { key ->
            val untouched = key.isNotEmpty() &&
                key.trim('.', ' ') == key &&
                key.none { it.code < 0x20 || it.code == 0x7f || it in "<>:\"/\\|?*" } &&
                key.toByteArray(Charsets.UTF_8).size <= 240 &&
                !PortableName.hasLoneSurrogate(key) &&
                !PortableName.isWindowsDeviceName(key)
            if (untouched) SafePath.buildValidFilename(key) shouldBe key
            // Saved pictures also lose other white space at the ends, as before
            if (untouched && key.trim() == key) PageImages.validFilename(key) shouldBe key
        }
    }

    @Test
    fun `the smallest failing inputs found`() {
        // A Windows device name, also with an extension
        PageImages.validFilename("NUL") shouldBe "NUL_"
        PageImages.validFilename("con.page 1") shouldBe "con_.page 1"
        SafePath.buildValidFilename("nul") shouldBe "nul_"
        SafePath.buildValidFilename("COM1.prefs") shouldBe "COM1_.prefs"
        // Half a surrogate pair, in the name and made by the cut at the limit
        PageImages.validFilename("a\udbffb") shouldBe "a_b"
        SafePath.buildValidFilename("a\udbffb") shouldBe "a_b"
        // Counted a char at a time, the cut kept "a", two pairs and half of a third
        val cut = PageImages.validFilename("a" + "😀".repeat(10), 6)
        PortableName.hasLoneSurrogate(cut) shouldBe false
        cut shouldBe "a😀"
    }

    @Test
    fun `a settings file written under its old name is still found`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // As an older version named it: a Windows device name, which only another OS could write
        val old = File(settings, "nul.xml")
        assumeTrue(runCatching { old.writeText("old") }.isSuccess && old.isFile, "this OS cannot write a device name")

        JavaSharedPreferences.fileFor("nul").readText() shouldBe "old"
        // Nothing is renamed
        old.readText() shouldBe "old"
    }

    @Test
    fun `a save an older version left half done under the old name is recovered`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // The file deleted and the new one still in .tmp, under the name an older version gave
        val leftover = File(settings, "nul.xml.tmp")
        assumeTrue(
            runCatching { leftover.outputStream().use { java.util.Properties().apply { setProperty("kept", "yes") }.storeToXML(it, null) } }.isSuccess &&
                leftover.isFile,
            "this OS cannot write a device name",
        )

        JavaSharedPreferences.fileFor("nul").name shouldBe "nul.xml"
        JavaSharedPreferences("nul").getString("kept", null) shouldBe "yes"
    }

    @Test
    fun `a settings key with half a surrogate pair has a file, and one written under its old name is still found`() {
        val settings = File(root, "settings").apply { mkdirs() }
        // Where the old name can be spelled (Windows), an older version wrote the file under it;
        // elsewhere the old name could not be made into a path at all
        val old = runCatching { settings.toPath().resolve("a\udbffb.xml") }.getOrNull()
        if (old != null) java.nio.file.Files.writeString(old, "old")

        val file = JavaSharedPreferences.fileFor("a\udbffb")

        if (old != null) file.readText() shouldBe "old" else file.name shouldBe "a_b.xml"
        file.toPath()
    }

    @Test
    fun `a new settings file gets the portable name`() {
        JavaSharedPreferences.fileFor("nul").name shouldBe "nul_.xml"
        JavaSharedPreferences.fileFor("a\udbffb").name shouldBe "a_b.xml"
        JavaSharedPreferences.fileFor("source_12").name shouldBe "source_12.xml"
    }

    private inline fun withClue(input: String, name: String, block: () -> Unit) =
        io.kotest.assertions.withClue("\"${escaped(input)}\" gave \"${escaped(name)}\"") { block() }

    private companion object {
        const val ITERATIONS = 3000
    }
}
