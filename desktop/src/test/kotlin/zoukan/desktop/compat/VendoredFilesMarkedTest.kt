package zoukan.desktop.compat

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `android-compat` is vendored from Suwayomi-Server. Every Zoukan edit sits inside one marker
 * convention (see `android-compat/README.md`), so that a later sync can find the edits and not
 * drop them silently:
 *
 * - an edit is wrapped in `// ZOUKAN -->` and `// ZOUKAN <--` on their own lines, or in
 *   inline block-comment forms of the same two markers on one line;
 * - a file Zoukan added or rewrote starts with `// ZOUKAN: whole file -->` and ends with
 *   `// ZOUKAN: whole file <--`;
 * - blocks never nest, and every opener has a closer.
 */
class VendoredFilesMarkedTest {

    private val moduleRoot: File = File("..", "android-compat").canonicalFile
    private val readme = File(moduleRoot, "README.md")
    private val sources = File(moduleRoot, "src/main/java")

    private val opener = Regex("""^\s*// ZOUKAN -->\s*$""")
    private val closer = Regex("""^\s*// ZOUKAN <--\s*$""")
    private val wholeOpener = Regex("""^\s*// ZOUKAN: whole file -->\s*$""")
    private val wholeCloser = Regex("""^\s*// ZOUKAN: whole file <--\s*$""")
    private val inlinePair = Regex("""/\* ZOUKAN --> \*/.*?/\* ZOUKAN <-- \*/""")

    private fun sourceFiles(): List<File> {
        check(readme.isFile) { "android-compat/README.md not found at $readme (working dir ${File(".").canonicalPath})" }
        check(sources.isDirectory) { "android-compat sources not found at $sources" }
        return sources.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .sortedBy { it.path }
            .toList()
    }

    private fun File.rel(): String = relativeTo(sources).invariantSeparatorsPath

    private fun File.markerLines(): List<String> = readLines().map { inlinePair.replace(it, "") }

    private fun File.isWholeFile(): Boolean = readLines().any { wholeOpener.matches(it) }

    /** The backticked file names of the README change table's second column. */
    private fun tableFiles(): List<String> = readme.readLines()
        .filter { it.startsWith("|") && !it.startsWith("|---") }
        .drop(1)
        .flatMap { row ->
            val cells = row.split("|")
            val files = cells.getOrNull(2).orEmpty()
            Regex("""`([\w/.-]+\.(?:java|kt))`""").findAll(files).map { it.groupValues[1] }.toList()
        }

    @Test
    fun `every file in the README change table has Zoukan markers`() {
        val all = sourceFiles()
        val problems = mutableListOf<String>()
        val names = tableFiles()
        assertTrue(names.isNotEmpty(), "no files found in the README change table")
        for (name in names) {
            val matches = all.filter { it.rel() == name || (!name.contains('/') && it.name == name) }
            when {
                matches.isEmpty() -> problems += "$name: not found under src/main/java"
                matches.size > 1 -> problems += "$name: ambiguous, use the path under src/main/java (${matches.joinToString { it.rel() }})"
                else -> {
                    val lines = matches.single().readLines().map { inlinePair.replace(it, "") }
                    val hasOpen = lines.any { opener.matches(it) || wholeOpener.matches(it) } ||
                        matches.single().readLines().any { it.contains("/* ZOUKAN --> */") }
                    val hasClose = lines.any { closer.matches(it) || wholeCloser.matches(it) } ||
                        matches.single().readLines().any { it.contains("/* ZOUKAN <-- */") }
                    if (!hasOpen || !hasClose) problems += "$name: no Zoukan opener and closer"
                }
            }
        }
        // Files Zoukan rewrote or added must be listed even if a sync would drop their markers.
        val required = listOf(
            "android/os/Build.java",
            "xyz/nulldev/androidcompat/network/ExtensionHttp.java",
            "android/graphics/BitmapFactory.java",
            "android/graphics/Canvas.java",
        )
        for (path in required) {
            if (names.none { it == path || it == path.substringAfterLast('/') }) problems += "$path: missing from the README change table"
        }
        // Every file with a marker gets a row.
        val listed = names.flatMap { n -> all.filter { it.rel() == n || (!n.contains('/') && it.name == n) } }.toSet()
        for (file in all) {
            if (file !in listed && file.readLines().any { it.contains("ZOUKAN -->") || it.contains("ZOUKAN: whole file -->") }) {
                problems += "${file.rel()}: has Zoukan markers but no README change table row"
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `marker blocks are balanced and never nested`() {
        val problems = mutableListOf<String>()
        for (file in sourceFiles()) {
            var openAt = -1
            var wholeAt = -1
            file.markerLines().forEachIndexed { index, line ->
                val n = index + 1
                when {
                    wholeOpener.matches(line) -> {
                        if (wholeAt != -1 || openAt != -1) problems += "${file.rel()}:$n: whole-file opener inside another block"
                        wholeAt = n
                    }
                    wholeCloser.matches(line) -> {
                        if (wholeAt == -1) problems += "${file.rel()}:$n: whole-file closer without opener"
                        wholeAt = -1
                    }
                    opener.matches(line) -> {
                        if (openAt != -1) {
                            problems += "${file.rel()}:$n: opener inside the block opened at line $openAt"
                        } else if (wholeAt != -1) {
                            problems += "${file.rel()}:$n: opener inside the whole-file block"
                        }
                        openAt = n
                    }
                    closer.matches(line) -> {
                        if (openAt == -1) problems += "${file.rel()}:$n: closer without opener"
                        openAt = -1
                    }
                }
            }
            if (openAt != -1) problems += "${file.rel()}:$openAt: opener without closer"
            if (wholeAt != -1) problems += "${file.rel()}:$wholeAt: whole-file opener without closer"
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `Zoukan imports sit inside a marker block`() {
        val problems = mutableListOf<String>()
        for (file in sourceFiles()) {
            if (file.isWholeFile()) continue
            var inBlock = false
            file.readLines().forEachIndexed { index, raw ->
                val line = raw
                if (opener.matches(line)) inBlock = true
                if (closer.matches(line)) inBlock = false
                val isZoukanImport = line.startsWith("import zoukan.") || line.startsWith("import uy.kohesive.injekt")
                val wrappedInline = inlinePair.containsMatchIn(line)
                if (isZoukanImport && !inBlock && !wrappedInline) problems += "${file.rel()}:${index + 1}: $line"
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `no one-line Zoukan comment forms`() {
        val problems = mutableListOf<String>()
        val oneLineColon = Regex("""//\s*ZOUKAN:""")
        val trailingBare = Regex("""\S.*//\s*ZOUKAN\s*$""")
        val bareLine = Regex("""^\s*//\s*ZOUKAN\s*$""")
        val inlineBare = Regex("""/\*\s*ZOUKAN\s*\*/""")
        val javadocForm = Regex("""^\s*\*\s*ZOUKAN:""")
        for (file in sourceFiles()) {
            file.readLines().forEachIndexed { index, raw ->
                val line = inlinePair.replace(raw, "")
                val bad = when {
                    wholeOpener.matches(line) || wholeCloser.matches(line) -> false
                    oneLineColon.containsMatchIn(line) -> true
                    bareLine.matches(line) -> true
                    trailingBare.containsMatchIn(line) -> true
                    inlineBare.containsMatchIn(line) -> true
                    javadocForm.containsMatchIn(line) -> true
                    else -> false
                }
                if (bad) problems += "${file.rel()}:${index + 1}: ${raw.trim().take(80)}"
            }
        }
        problems.shouldBeEmpty()
    }
}
