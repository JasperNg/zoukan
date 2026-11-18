package zoukan.desktop.scan

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed
import zoukan.desktop.scan.SourceScan.Finding

class SourceScanTest {
    private val site = Finding("desktop/src/main/kotlin/zoukan/desktop/A.kt", 3, "dir.deleteRecursively()")

    @Test
    fun `an allowed site with a reason passes`() {
        SourceScan.problems(listOf(site), listOf(Allowed("zoukan/desktop/A.kt", "deleteRecursively", "own temp folder")))
            .shouldBeEmpty()
    }

    @Test
    fun `a site no entry allows fails`() {
        SourceScan.problems(listOf(site), emptyList()) shouldBe listOf(site.toString())
    }

    @Test
    fun `an entry without a reason fails`() {
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively", " ")
        SourceScan.problems(listOf(site), listOf(entry)) shouldBe listOf("Allow-list entry without a reason: $entry")
    }

    @Test
    fun `a stale entry that matches no site fails`() {
        val stale = Allowed("zoukan/desktop/B.kt", "deleteRecursively", "fixed since")
        SourceScan.problems(listOf(site), listOf(Allowed("A.kt", "", "the helper"), stale)) shouldBe
            listOf("Allow-list entry that matches nothing (remove it): $stale")
    }

    @Test
    fun `a new site in an allowed file is flagged, though the entry's part matches it`() {
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively()", "own temp folder")
        val extra = site.copy(line = 9)
        val problems = SourceScan.problems(listOf(site, extra), listOf(entry))
        problems.size shouldBe 1
        problems.single() shouldStartWith "Allow-list entry allows 1 site(s) but 2 match (a new site needs its own entry and reason)"
        problems.single() shouldContain extra.toString()
        // A count that names both sites allows them; a fixed site is flagged the other way
        SourceScan.problems(listOf(site, extra), listOf(entry.copy(count = 2))).shouldBeEmpty()
        SourceScan.problems(listOf(site), listOf(entry.copy(count = 2))).single() shouldContain "a site was fixed: lower the count"
        // A whole-file entry with no count is for a checked helper's own file
        SourceScan.problems(listOf(site, extra), listOf(Allowed("A.kt", "", "the helper", count = null))).shouldBeEmpty()
    }

    @Test
    fun `an entry that names a function allows only the site in that function`() {
        val inClear = site.copy(function = "clear")
        val inOther = site.copy(line = 20, function = "prune")
        val entry = Allowed("zoukan/desktop/A.kt", "deleteRecursively()", "own temp folder", function = "clear")
        SourceScan.problems(listOf(inClear, inOther), listOf(entry)) shouldBe listOf(inOther.toString())
    }

    @Test
    fun `a finding names the function around it, Kotlin and Java`() {
        val source = SourceScan.Source(
            "A.kt",
            """
            class A {
                fun `the library's list`() {
                    one()
                }
                private suspend fun <T> Foo.load(block: () -> T) = runBlocking(Dispatchers.IO) {
                    items.forEach { two() }
                }
                val top = three()
            }
            class J {
                public void run() throws IOException {
                    for (;;) { four(); }
                }
            }
            """.trimIndent(),
        )
        listOf(3, 6, 8, 12).map { source.functionAt(it) } shouldBe listOf("`the library's list`", "load", null, "run")
        SourceScan.finding(source, 6).function shouldBe "load"
    }

    @Test
    fun `only the lines inside Zoukan's markers are its own in a vendored file`() {
        val lines = """
            upstream()
            // ZOUKAN -->
            ours()
            // ZOUKAN <--
            upstream()
            call(/* ZOUKAN --> */ours/* ZOUKAN <-- */)
        """.trimIndent().lines()
        SourceScan.zoukanLines(lines).toList() shouldBe listOf(false, true, true, true, false, true)
        SourceScan.zoukanLines(listOf("// ZOUKAN: whole file -->", "x()", "// ZOUKAN: whole file <--")).toList() shouldBe
            listOf(true, true, true)
    }

    @Test
    fun `the header of a Java for loop keeps its semicolons`() {
        val code = "void run() { for (int i = 0; i < n; i++) { step(); } }"
        SourceScan.header(code, code.indexOf("{ step")).trim() shouldBe "for (int i = 0; i < n; i++)"
    }
}
