package zoukan.desktop.contract

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Every trust boundary `desktop/THREAT-MODEL.md` lists has a row in its "Checked by" table, and
 * every test class a row names exists and has tests, as `CoverageMatrixTest` does for features.
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

internal object ThreatModel {

    class Row(val boundary: String, val tests: List<String>, val gap: String)

    private fun section(text: String, heading: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == heading }
        check(start >= 0) { "THREAT-MODEL.md has no \"$heading\" heading" }
        val level = heading.takeWhile { it == '#' }.length
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val l = lines[i].trimStart()
            l.startsWith("#") && l.takeWhile { it == '#' }.length <= level
        } ?: lines.size
        return lines.subList(start + 1, end)
    }

    /** The boundary names: each list item of "## Trust boundaries" up to its colon, without a part in brackets. */
    fun boundaries(text: String): List<String> = section(text, "## Trust boundaries")
        .takeWhile { it.trim() != "### Checked by" }
        .filter { it.startsWith("- ") }
        .map { item -> item.removePrefix("- ").substringBefore(':').replace(Regex("""\s*\([^)]*\)"""), "").trim() }

    fun rows(text: String): List<Row> = section(text, "### Checked by")
        .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Boundary |") }
        .map { line ->
            val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            check(cells.size == 3) { "A Checked by row must have 3 cells: $line" }
            Row(cells[0], Regex("`([^`]+)`").findAll(cells[1]).map { it.groupValues[1] }.toList(), cells[2])
        }

    fun problems(boundaries: List<String>, rows: List<Row>, testProblem: (String) -> String?): List<String> {
        val problems = mutableListOf<String>()
        val byName = rows.groupBy { it.boundary }
        boundaries.filter { it !in byName }.forEach { problems += "boundary \"$it\" has no row in the Checked by table" }
        byName.filter { it.value.size > 1 }.keys.forEach { problems += "boundary \"$it\" has more than one row" }
        rows.filter { it.boundary !in boundaries }.forEach { problems += "row \"${it.boundary}\" names no boundary of the list" }
        for (row in rows) {
            if (row.tests.isEmpty() && row.gap.isBlank()) problems += "row \"${row.boundary}\" has neither tests nor a gap"
            row.tests.forEach { test -> testProblem(test)?.let { problems += "row \"${row.boundary}\": $it" } }
        }
        return problems
    }

    /**
     * What is wrong with a fully qualified test class name, or null when a test source declares the
     * class with an @Test: in a file of its own name, or else in one file of its package (several
     * small subclasses of one contract share a file). A class whose file has no @Test has tests when
     * it extends a class of the same package whose file has them (a contract's subclass).
     */
    fun testSourceProblem(name: String): String? {
        if (!Regex("""^[a-z][\w.]*\.[A-Z]\w*$""").matches(name)) return "\"$name\" is not a fully qualified class name"
        val simple = name.substringAfterLast('.')
        val file = declaringFile(name.substringBeforeLast('.'), simple)
            ?: return "$name has no test source (*/src/*Test*/kotlin/${name.replace('.', '/')}.kt, or a file of its package that declares it)"
        val text = RepositorySources.text(file)
        if ("@Test" in text) return null
        val parent = Regex("""\bclass\s+$simple\b[^{]*?:\s*(\w+)\s*\(""").find(text)?.groupValues?.get(1)
        val parentFile = parent?.let { declaringFile(name.substringBeforeLast('.'), it) }
        if (parentFile != null && "@Test" in RepositorySources.text(parentFile)) return null
        return "$name has no @Test"
    }

    /** The test source that declares class [simple] of package [pkg]: the file of that name, or the one file of the package that declares it. */
    private fun declaringFile(pkg: String, simple: String): String? {
        val dir = "/kotlin/" + pkg.replace('.', '/') + "/"
        val declares = Regex("""\bclass\s+$simple\b""")
        val inPackage = RepositorySources.allPaths.filter {
            RepositorySources.isTestSource(it) && it.endsWith(".kt") && it.substringBeforeLast('/').endsWith(dir.trimEnd('/'))
        }
        inPackage.firstOrNull { it.endsWith("$dir$simple.kt") }?.let { return it.takeIf { f -> declares.containsMatchIn(RepositorySources.text(f)) } }
        return inPackage.filter { declares.containsMatchIn(RepositorySources.text(it)) }.singleOrNull()
    }
}
