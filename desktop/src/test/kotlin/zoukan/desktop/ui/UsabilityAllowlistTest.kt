package zoukan.desktop.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class UsabilityAllowlistTest {
    private val keys = listOf("tab:Library", "dialog:a.One", "dialog:a.Two", "screen:b.Reader")

    private fun parse(text: String) = UsabilityAllowlist.parse(text.trimIndent(), keys)

    @Test
    fun `reads entries, skipping comments and blank lines, and keeps their line numbers`() {
        val entries = parse(
            """
            # a comment

            dialog:* | UNLABELLED_CLICKABLE | scrim* | The scrim closes the dialog | on click
            tab:Library | TEXT_OVERFLOW | "Filter" | Fits at the default size
            """,
        )

        entries shouldContainExactly listOf(
            AllowEntry("dialog:*", Rule.UNLABELLED_CLICKABLE, "scrim*", "The scrim closes the dialog | on click", 3),
            AllowEntry("tab:Library", Rule.TEXT_OVERFLOW, "\"Filter\"", "Fits at the default size", 4),
        )
    }

    @Test
    fun `an entry without a reason fails with its line number`() {
        shouldThrow<IllegalArgumentException> { parse("# c\ntab:Library | UNLABELLED_CLICKABLE | x |") }
            .message!! shouldContain "usability-allowlist.txt line 2"
        shouldThrow<IllegalArgumentException> { parse("tab:Library | UNLABELLED_CLICKABLE | x") }
            .message!! shouldContain "line 1"
    }

    @Test
    fun `an unknown rule fails`() {
        shouldThrow<IllegalArgumentException> { parse("tab:Library | NOT_A_RULE | x | because") }
            .message!! shouldContain "unknown rule"
    }

    @Test
    fun `a key with no matrix row fails, exact or as a prefix`() {
        shouldThrow<IllegalArgumentException> { parse("tab:Nope | UNLABELLED_CLICKABLE | x | because") }
            .message!! shouldContain "no matrix row"
        shouldThrow<IllegalArgumentException> { parse("nothing:* | UNLABELLED_CLICKABLE | x | because") }
            .message!! shouldContain "no matrix row"
    }

    @Test
    fun `an entry matches by key, rule and node, with a trailing star as a prefix`() {
        val entry = parse("dialog:* | UNLABELLED_CLICKABLE | scrim* | because").single()

        entry.matches(UsabilityFinding("v", "dialog:a.One", Rule.UNLABELLED_CLICKABLE, "scrim-1", "d")) shouldBe true
        entry.matches(UsabilityFinding("v", "tab:Library", Rule.UNLABELLED_CLICKABLE, "scrim-1", "d")) shouldBe false
        entry.matches(UsabilityFinding("v", "dialog:a.One", Rule.TEXT_OVERFLOW, "scrim-1", "d")) shouldBe false
        entry.matches(UsabilityFinding("v", "dialog:a.One", Rule.UNLABELLED_CLICKABLE, "other", "d")) shouldBe false
    }

    @Test
    fun `classify pairs each finding with the first entry that matches it`() {
        val entries = parse("dialog:* | UNLABELLED_CLICKABLE | * | because")
        val allowed = UsabilityFinding("v", "dialog:a.One", Rule.UNLABELLED_CLICKABLE, "x", "d")
        val fresh = UsabilityFinding("v", "tab:Library", Rule.UNLABELLED_CLICKABLE, "x", "d")

        val classified = classify(listOf(allowed, fresh), entries)

        classified.map { it.allowedBy?.line } shouldContainExactly listOf(1, null)
    }

    @Test
    fun `a rule error is never allowlisted, not even by a star`() {
        val entries = parse("dialog:a.One | ESCAPE_NOT_DISMISSING | * | because\ndialog:a.One | ESCAPE_NOT_DISMISSING | - | because")
        val error = UsabilityFinding("v", "dialog:a.One", Rule.ESCAPE_NOT_DISMISSING, RULE_ERROR_NODE, "rule error: boom")

        classify(listOf(error), entries).single().allowedBy shouldBe null
    }

    @Test
    fun `enforce fails only new findings of the first three rules`() {
        val entries = parse("dialog:a.One | UNLABELLED_CLICKABLE | * | because")
        val findings = listOf(
            UsabilityFinding("v", "dialog:a.One", Rule.UNLABELLED_CLICKABLE, "x", "allowed"),
            UsabilityFinding("v", "dialog:a.Two", Rule.UNLABELLED_CLICKABLE, "y", "new"),
            UsabilityFinding("v", "dialog:a.Two", Rule.TEXT_OVERFLOW, "z", "new"),
            UsabilityFinding("v", "dialog:a.Two", Rule.ESCAPE_NOT_DISMISSING, "-", "new"),
            UsabilityFinding("v", "dialog:a.Two", Rule.TAB_TRAVERSAL, "w", "never fails"),
        )

        val failures = enforceFailures(classify(findings, entries))

        failures.size shouldBe 3
        failures.none { "allowed" in it } shouldBe true
        enforceFailures(classify(findings.take(1), entries)).shouldBeEmpty()
    }
}
