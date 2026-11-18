package zoukan.desktop.testing

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests must never read or write the developer's real home folder. The build points `user.home` at
 * a folder under build/, but a test run from an IDE has the real one, so test code reaches the home
 * folder only through [TestHome] (and makes files there with [FileSystemSandbox]). Fails when a
 * test source reads the home folder another way.
 */
class HomeFolderScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Ways to reach the home folder: the system property, the environment, library helpers, `~`. */
    private val homeReads = listOf(
        Regex(""""user\.home""""),
        Regex("""\bgetenv\(\s*"(HOME|USERPROFILE|HOMEDRIVE|HOMEPATH)"\s*\)"""),
        Regex("""\bFileUtils\.getUserDirectory(Path)?\b"""),
        Regex("""\bSystemUtils\.(USER_HOME|getUserHome)\b"""),
        Regex("""\b(expandHome|expandTilde|untildify)\b"""),
        // A path that starts with ~, which some helper must expand to the home folder
        Regex(""""~[/\\]"""),
    )

    /** Files allowed to read the home folder directly, each with why. */
    private val allowlist = mapOf(
        "desktop/src/test/kotlin/zoukan/desktop/testing/TestHome.kt" to
            "the one place tests read user.home: it checks the build set it, else uses the temporary folder",
        "desktop/src/test/kotlin/zoukan/desktop/testing/HomeFolderScanTest.kt" to
            "this scan's own patterns",
    )

    @Test
    fun `test code reads the home folder only through TestHome`() {
        val tests = File(root, "desktop/src/test")
        check(tests.isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = tests.walk()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in allowlist }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                    .filter { (_, line) -> homeReads.any { it.containsMatchIn(line) } }
                    .map { (i, line) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        check(violations.isEmpty()) {
            "Read the home folder with TestHome.root() (or make files with FileSystemSandbox), not directly:\n" +
                violations.joinToString("\n")
        }
    }

    @Test
    fun `every allowlisted file still exists`() {
        val missing = allowlist.keys.filterNot { File(root, it).isFile }
        check(missing.isEmpty()) { "Allowlist entries for files that are gone: $missing" }
    }

    @Test
    fun `the patterns catch each way of reading the home folder`() {
        val samples = listOf(
            """val home = System.getProperty("user.home")""",
            """File(System.getenv("HOME"), "x")""",
            """System.getenv("USERPROFILE")""",
            """FileUtils.getUserDirectory()""",
            """SystemUtils.USER_HOME""",
            """val p = expandHome(path)""",
            """File("~/Downloads")""",
        )
        samples.filterNot { sample -> homeReads.any { it.containsMatchIn(sample) } }.let { missed ->
            check(missed.isEmpty()) { "Not caught: $missed" }
        }
        // A home folder in a fixed test value is not a read
        listOf("""ZoukanDirs.default(platform, null, { null }, "/home/someone")""", """TestHome.root()""")
            .filter { sample -> homeReads.any { it.containsMatchIn(sample) } }
            .let { flagged -> check(flagged.isEmpty()) { "Wrongly caught: $flagged" } }
    }
}
