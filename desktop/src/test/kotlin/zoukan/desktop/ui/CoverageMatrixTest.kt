package zoukan.desktop.ui

import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps `coverage-matrix.yaml` honest without starting the app: every screen, home tab, Browse
 * page, Settings page and named dialog found in the compiled classes needs a row, every row must
 * name something that still exists, and every test a row names must exist. Inline dialogs (an
 * `AlertDialog(` outside a function named ...Dialog) are counted per function and held to a
 * baseline that may only shrink.
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
            val at = "${row.key} (line ${row.line})"
            // A known kind of key, so a misspelt prefix is not taken for a hand-made row
            if (!KEY.matches(row.key)) {
                problems += "$at: the key must start with one of ${PREFIXES.joinToString(" ") { "$it:" }} and hold no space"
            }
            // The tests exist
            row.tests.forEach { test ->
                testProblem(test)?.let { problems += "$at: $it" }
            }
            // A valid tier, and a reason when nothing covers the row
            if (row.tier !in CoverageMatrix.TIERS) {
                problems += "$at: tier '${row.tier}' is not one of ${CoverageMatrix.TIERS.joinToString()}"
            }
            if (row.tests.isEmpty() && row.gap.isNullOrBlank()) {
                problems += "$at: no tests and no gap; name a test or say why there is none in gap:"
            }
            // A smoke-tier row is one a smoke check opens, or says why not
            if (row.tier == "smoke" && !row.smoke && row.gap.isNullOrBlank()) {
                problems += "$at: tier smoke but smoke: false; give a smoke check this key, or change the tier, or say why in gap:"
            }
            // Gaps name planned test classes, not planning documents
            listOfNotNull(row.feature, row.gap).firstOrNull { text -> PLANNING_NAMES.any { it.containsMatchIn(text) } }?.let {
                problems += "$at: \"$it\" names a planning document or ID; name the planned test class instead " +
                    "(\"planned: FooJourneyTest\")"
            }
        }

        assertNoProblems(problems)
    }

    @Test
    fun `inline dialogs stay within the baseline`() {
        val baseline = baseline()
        val sites = inlineDialogs(namedDialogs().toSet())
        val problems = mutableListOf<String>()
        (sites.keys + baseline.keys).sorted().forEach { key ->
            val now = sites[key] ?: 0
            val allowed = baseline[key] ?: 0
            when {
                now > allowed ->
                    problems += "new inline dialog in $key ($now sites, the baseline allows $allowed): " +
                        "extract it into a fun ...Dialog, so the coverage matrix sees it"
                now == 0 ->
                    problems += "$key is in coverage-inline-dialogs.txt but has no inline dialog any more; " +
                        "remove its line from the baseline"
                now < allowed -> problems += "$key: update the baseline: $now sites now (coverage-inline-dialogs.txt says $allowed)"
            }
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
            INLINE to inlineDialogs(named.toSet()).keys.sorted(),
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
     * How many `AlertDialog(`/`Dialog(` calls each function holds, by `dialog-inline:<File>.kt:<function>`,
     * for the functions that are not one of the [named] dialogs (a top-level fun ...Dialog, ...Dialogs
     * or ...Sheet; a member fun ...Dialog is not found by reflection, so it counts as inline). The
     * enclosing function comes from [InlineDialogScanner]. The test runs in desktop/.
     */
    private fun inlineDialogs(named: Set<String>): Map<String, Int> {
        val sources = File("src/main/kotlin/zoukan/desktop/ui")
        check(sources.isDirectory) { "${sources.absolutePath} not found; the test must run in desktop/" }
        return sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            val pkg = text.lineSequence().firstOrNull { it.startsWith("package ") }.orEmpty()
                .removePrefix("package ").trim().removePrefix(UI_PACKAGE).removePrefix(".")
            InlineDialogScanner.sites(text).mapNotNull { site ->
                val function = site.function ?: "<top level>"
                val key = if (pkg.isEmpty()) "dialog:$function" else "dialog:$pkg.$function"
                if (key in named) null else "dialog-inline:${file.name}:$function"
            }
        }.groupingBy { it }.eachCount()
    }

    /** coverage-inline-dialogs.txt: "<key>" for one site, "<key> <count>" for more. */
    private fun baseline(): Map<String, Int> {
        val lines = requireNotNull(javaClass.getResourceAsStream(BASELINE)) { "$BASELINE is not on the test classpath" }
            .reader().use { it.readLines() }
        return lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("""\s+"""))
                val count = parts.getOrNull(1)?.toIntOrNull()
                require(parts.size <= 2 && (parts.size == 1 || (count != null && count > 1))) {
                    "$BASELINE: \"$line\" is not \"<key>\" or \"<key> <count of 2 or more>\""
                }
                parts[0] to (count ?: 1)
            }
    }

    /** What is wrong with a `tests` entry ("ui.Foo" or "ui.Foo#bar", under zoukan.desktop), or null. */
    private fun testProblem(test: String): String? {
        val className = "zoukan.desktop." + test.substringBefore('#')
        val type = runCatching { Class.forName(className, false, loader) }.getOrNull()
            ?: return "test class $className does not exist; fix the name in tests: or remove it"
        val tests = type.declaredMethods.filter { method -> method.annotations.any { it.annotationClass.java.name in TEST_ANNOTATIONS } }
        if (tests.isEmpty()) return "$className has no @Test method; name a test class in tests:"
        val method = test.substringAfter('#', "").ifEmpty { return null }
        return if (tests.none { it.name == method }) {
            "$className has no @Test method '$method'; fix the name in tests: or remove it"
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

        /** Every kind of key; see desktop/TESTING.md, 'Keys'. */
        val PREFIXES = listOf("screen", "tab", "browse", "settings", "dialog", "dialog-inline", "state", "flow")
        val KEY = Regex("""^(${PREFIXES.joinToString("|")}):\S+$""")

        /** Row prefixes the test can check against the app; other rows (state:, flow:) are hand-made. */
        val DISCOVERABLE = listOf("screen:", "tab:", "browse:", "settings:", "dialog:", "dialog-inline:")
        val HAND_MADE = setOf("settings:search")

        /** A planning folder ("123_NAME/") or a planning ID ("(P13)"), which do not belong in the repo. */
        val PLANNING_NAMES = listOf(Regex("""\b\d{3}_[A-Z]+/"""), Regex("""\([A-Z]\d+\)"""))

        val TEST_ANNOTATIONS = setOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.params.ParameterizedTest",
        )

        val DIALOG_NAME = Regex(""".*(Dialog|Dialogs|Sheet)""")
        const val INLINE = "inline dialogs"
    }
}
