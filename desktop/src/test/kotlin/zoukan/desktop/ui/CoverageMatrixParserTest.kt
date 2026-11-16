package zoukan.desktop.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class CoverageMatrixParserTest {
    @Test
    fun `reads entries, quoted values with colons, commas and quotes, and flow lists`() {
        val rows = CoverageMatrix.parse(
            """
            # a comment
            - key: tab:Library
              feature: "Grid: covers, titles and \"badges\""
              tier: smoke
              smoke: true
              tests: [ui.ScreenSmokeTest, ui.library.LibraryFiltersTest#filters]

              # a comment inside the file
            - key: flow:library.selection
              feature: Selection bar
              tier: journey
              smoke: false
              tests: []
              gap: "Only the gated test; planned: LibrarySelectionJourneyTest"
            """.trimIndent(),
        )

        rows shouldHaveSize 2
        rows[0] shouldBe CoverageRow(
            key = "tab:Library",
            feature = "Grid: covers, titles and \"badges\"",
            tier = "smoke",
            smoke = true,
            tests = listOf("ui.ScreenSmokeTest", "ui.library.LibraryFiltersTest#filters"),
            gap = null,
            line = 2,
        )
        rows[1].tests.shouldBeEmpty()
        rows[1].smoke shouldBe false
        rows[1].gap shouldBe "Only the gated test; planned: LibrarySelectionJourneyTest"
        rows[1].line shouldBe 9
    }

    @Test
    fun `a line outside the subset fails with its line number`() {
        val error = shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: tab:Library\n  feature: x\n    tier: smoke\n")
        }
        error.message!! shouldStartWith "line 3:"
    }

    @Test
    fun `an unknown field fails`() {
        val error = shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: tab:Library\n  feature: x\n  owner: me\n")
        }
        error.message!! shouldStartWith "line 3: unknown field \"owner\""
    }

    @Test
    fun `a missing field, a bad smoke value, a plain value with a colon and a block list fail`() {
        shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: tab:Library\n  feature: x\n  tier: smoke\n  smoke: true\n")
        }.message!! shouldStartWith "line 1: the entry has no tests"
        shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: a\n  feature: x\n  tier: smoke\n  smoke: yes\n  tests: []\n")
        }.message!! shouldStartWith "line 4:"
        shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: a\n  feature: Grid: covers\n")
        }.message!! shouldStartWith "line 2:"
        shouldThrow<IllegalArgumentException> {
            CoverageMatrix.parse("- key: a\n  feature: x\n  tests:\n    - ui.ScreenSmokeTest\n")
        }.message!! shouldStartWith "line 3:"
    }

    @Test
    fun `a gap that YAML reads as no value, or too short to say why, fails`() {
        listOf("null", "~", "\"\"", "NULL", "todo").forEach { gap ->
            shouldThrow<IllegalArgumentException> {
                CoverageMatrix.parse("- key: a\n  feature: x\n  tier: journey\n  smoke: false\n  tests: []\n  gap: $gap\n")
            }.message!! shouldStartWith "line 6: gap"
        }
    }
}
