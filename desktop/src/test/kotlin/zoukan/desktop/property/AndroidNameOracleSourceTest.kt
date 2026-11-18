package zoukan.desktop.property

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

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
