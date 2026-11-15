package zoukan.desktop.ui

import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps `coverage-matrix.yaml` honest without starting the app: every screen, home tab, Browse
 * page, Settings page and named dialog found in the compiled classes needs a row, every row must
 * name something that still exists, and every test a row names must exist. Inline dialogs (an
 * `AlertDialog(` outside a function named ...Dialog) are held to a baseline that may only shrink.
 */
class CoverageMatrixTest {
    private val loader = javaClass.classLoader

    @Test
    fun `every screen, page and dialog has a matrix row, and every row's tests exist`() {
        val rows = CoverageMatrix.load()
        val discovered = discoveredKeys()
        writeReport(discovered)
        val known = discovered.values.flatten().toSet()
        val problems = mutableListOf<String>()

        // Duplicates
        rows.groupBy { it.key }.filterValues { it.size > 1 }.forEach { (key, dupes) ->
            problems += "$key is in coverage-matrix.yaml ${dupes.size} times (lines ${dupes.joinToString { "${it.line}" }}); " +
                "merge them into one row"
        }

        // Discovered keys without a row
        val rowKeys = rows.map { it.key }.toSet()
        // (inline dialogs need a baseline line, not a row)
        discovered.filterKeys { it != INLINE }.values.flatten().filter { it !in rowKeys }.sorted().forEach { key ->
            problems += "$key has no row in coverage-matrix.yaml; add one (desktop/TESTING.md, 'Adding a row')"
        }

        // Rows naming something that no longer exists
        rows.filter { row -> DISCOVERABLE.any { row.key.startsWith(it) } && row.key !in HAND_MADE && row.key !in known }
            .forEach { row ->
                problems += "${row.key} (coverage-matrix.yaml line ${row.line}) names a screen, page or dialog that no longer " +
                    "exists; rename the row to the new key or remove it"
            }

        rows.forEach { row ->
            // The tests exist
            row.tests.forEach { test ->
                testProblem(test)?.let { problems += "${row.key} (line ${row.line}): $it" }
            }
            // A valid tier, and a reason when nothing covers the row
            if (row.tier !in CoverageMatrix.TIERS) {
                problems += "${row.key} (line ${row.line}): tier '${row.tier}' is not one of ${CoverageMatrix.TIERS.joinToString()}"
            }
            if (row.tests.isEmpty() && row.gap.isNullOrBlank()) {
                problems += "${row.key} (line ${row.line}): no tests and no gap; name a test or say why there is none in gap:"
            }
        }

        assertNoProblems(problems)
    }

    @Test
    fun `inline dialogs stay within the baseline`() {
        val baseline = baselineKeys()
        val sites = inlineDialogs(namedDialogs().toSet())
        val problems = mutableListOf<String>()
        (sites - baseline).sorted().forEach {
            problems += "new inline dialog $it: extract it into a fun ...Dialog, so the coverage matrix sees it"
        }
        (baseline - sites).sorted().forEach {
            problems += "$it is in coverage-inline-dialogs.txt but has no inline dialog any more; remove it from the baseline"
        }
        assertNoProblems(problems)
    }

    // Discovery

    /** The keys found in the app, by kind. */
    private fun discoveredKeys(): Map<String, List<String>> {
        val screens = UiInventory.screenClasses()
        // About 30 when this was written; far fewer means the scan stopped finding them
        screens.size shouldBeGreaterThan 20
        val named = namedDialogs()
        return linkedMapOf(
            "screens" to screens.map(UiInventory::screenKey).sorted(),
            "tabs, Browse pages and Settings pages" to UiInventory.enumKeys(),
            "named dialogs" to named,
            INLINE to inlineDialogs(named.toSet()).sorted(),
        )
    }

    /**
     * `dialog:<package under ui>.<name>` for each composable function named ...Dialog, ...Dialogs or
     * ...Sheet: methods of the `*Kt` file classes under zoukan.desktop.ui that take a Composer.
     */
    private fun namedDialogs(): List<String> {
        val root = File(HomeScreen::class.java.protectionDomain.codeSource.location.toURI())
        val ui = File(root, UI_PACKAGE.replace('.', File.separatorChar))
        return ui.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Kt.class") && '$' !in it.name }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .flatMap { name ->
                val type = Class.forName(name, false, loader)
                val pkg = type.packageName.removePrefix("zoukan.desktop.ui").removePrefix(".")
                type.declaredMethods
                    .filter { method -> method.parameterTypes.any { it.name == COMPOSER } }
                    .map { it.name.substringBefore('-') }
                    .filter { '$' !in it && DIALOG_NAME.matches(it) }
                    .map { if (pkg.isEmpty()) "dialog:$it" else "dialog:$pkg.$it" }
            }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * `dialog-inline:<File>.kt:<function>` for each `AlertDialog(`/`Dialog(` call whose enclosing
     * function is not one of the [named] dialogs (a top-level fun ...Dialog, ...Dialogs or ...Sheet;
     * a member fun ...Dialog is not found by reflection, so it counts as inline). The test runs in
     * desktop/.
     */
    private fun inlineDialogs(named: Set<String>): Set<String> {
        val sources = File("src/main/kotlin/zoukan/desktop/ui")
        check(sources.isDirectory) { "${sources.absolutePath} not found; the test must run in desktop/" }
        return sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val lines = file.readLines()
            val pkg = lines.firstOrNull { it.startsWith("package ") }.orEmpty()
                .removePrefix("package ").trim().removePrefix(UI_PACKAGE).removePrefix(".")
            lines.indices.mapNotNull { i ->
                val line = lines[i]
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapNotNull null
                if (!DIALOG_CALL.containsMatchIn(line)) return@mapNotNull null
                val function = FUN.find(line)?.groupValues?.get(1)
                    ?: (i - 1 downTo 0).firstNotNullOfOrNull { j ->
                        FUN.find(lines[j])?.takeIf { indent(lines[j]) < indent(line) }?.groupValues?.get(1)
                    }
                    ?: "<top level>"
                val key = if (pkg.isEmpty()) "dialog:$function" else "dialog:$pkg.$function"
                if (key in named) null else "dialog-inline:${file.name}:$function"
            }
        }.toSet()
    }

    private fun baselineKeys(): Set<String> =
        requireNotNull(javaClass.getResourceAsStream(BASELINE)) { "$BASELINE is not on the test classpath" }
            .reader().use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    /** What is wrong with a `tests` entry ("ui.Foo" or "ui.Foo#bar", under zoukan.desktop), or null. */
    private fun testProblem(test: String): String? {
        val className = "zoukan.desktop." + test.substringBefore('#')
        val type = runCatching { Class.forName(className, false, loader) }.getOrNull()
            ?: return "test class $className does not exist; fix the name in tests: or remove it"
        val method = test.substringAfter('#', "").ifEmpty { return null }
        return if (type.declaredMethods.none { it.name == method }) {
            "$className has no test method '$method'; fix the name in tests: or remove it"
        } else {
            null
        }
    }

    /** discovered.txt, so a reviewer can see what the test found. */
    private fun writeReport(discovered: Map<String, List<String>>) {
        val file = File("build/reports/coverage/discovered.txt")
        file.parentFile.mkdirs()
        file.writeText(
            discovered.entries.joinToString("\n\n") { (kind, keys) ->
                "# ${keys.size} $kind\n" + keys.joinToString("\n")
            } + "\n",
        )
    }

    private fun indent(line: String) = line.length - line.trimStart().length

    /** Fails listing every problem, one per line (a collection matcher would show only the first). */
    private fun assertNoProblems(problems: List<String>) {
        if (problems.isNotEmpty()) {
            throw AssertionError("${problems.size} coverage problem(s):\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    private companion object {
        const val UI_PACKAGE = "zoukan.desktop.ui"
        const val COMPOSER = "androidx.compose.runtime.Composer"
        const val BASELINE = "/coverage-inline-dialogs.txt"

        /** Row prefixes the test can check against the app; other rows (state:, flow:) are hand-made. */
        val DISCOVERABLE = listOf("screen:", "tab:", "browse:", "settings:", "dialog:", "dialog-inline:")
        val HAND_MADE = setOf("settings:search")

        val DIALOG_NAME = Regex(""".*(Dialog|Dialogs|Sheet)""")
        val DIALOG_CALL = Regex("""\b(AlertDialog|Dialog)\(""")
        val FUN = Regex(
            """^\s*(?:(?:@\w+|private|internal|public|protected|inline|override|suspend)\s+)*""" +
                """fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""",
        )
        const val INLINE = "inline dialogs"
    }
}
