package zoukan.desktop.ui.whatsnew

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.Version
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.whatsnew.ReleaseNotes.Block
import zoukan.desktop.ui.whatsnew.ReleaseNotes.Span

class WhatsNewTest {
    private fun preferences() = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("whats_new_test_${System.nanoTime()}")))

    private val v100 = Version(listOf(1, 0, 0))
    private val v110 = Version(listOf(1, 1, 0))

    @Test
    fun `a first run says nothing and records the version`() {
        val preferences = preferences()
        WhatsNew.updatedFrom(preferences, v100) shouldBe null
        preferences.lastVersion().get() shouldBe "1.0.0"
    }

    @Test
    fun `the first launch after an update says so once`() {
        val preferences = preferences()
        WhatsNew.updatedFrom(preferences, v100)
        WhatsNew.updatedFrom(preferences, v110) shouldBe v100
        WhatsNew.updatedFrom(preferences, v110) shouldBe null
    }

    @Test
    fun `going back to an older version says nothing`() {
        val preferences = preferences()
        WhatsNew.updatedFrom(preferences, v110)
        WhatsNew.updatedFrom(preferences, v100) shouldBe null
        preferences.lastVersion().get() shouldBe "1.0.0"
    }

    @Test
    fun `reads release notes into blocks`() {
        val notes = """
            ## v1.1.0

            #### What's Changed
            - Fix the reader ([@someone](https://github.com/someone))
              continued on the next line
            * Second
                - Nested
            1. First step

            Some text
            on two lines
            > A quote
            -----
            ```
            code here
            ```
        """.trimIndent()
        ReleaseNotes.parse(notes) shouldBe listOf(
            Block.Heading(2, "v1.1.0"),
            Block.Heading(4, "What's Changed"),
            Block.ListItem(0, "•", "Fix the reader ([@someone](https://github.com/someone)) continued on the next line"),
            Block.ListItem(0, "•", "Second"),
            Block.ListItem(2, "•", "Nested"),
            Block.ListItem(0, "1.", "First step"),
            Block.Paragraph("Some text on two lines"),
            Block.Quote("A quote"),
            Block.Rule,
            Block.Code("code here"),
        )
    }

    @Test
    fun `reads bold, italic, code and links`() {
        ReleaseNotes.spans("**Full Changelog**: [a@v1...v2](https://github.com/o/r/compare/v1...v2), `x` and *y* see https://example.org.") shouldBe listOf(
            Span.Bold("Full Changelog"),
            Span.Plain(": "),
            Span.Link("a@v1...v2", "https://github.com/o/r/compare/v1...v2"),
            Span.Plain(", "),
            Span.Code("x"),
            Span.Plain(" and "),
            Span.Italic("y"),
            Span.Plain(" see "),
            Span.Link("https://example.org", "https://example.org"),
            Span.Plain("."),
        )
    }

    @Test
    fun `leaves snake_case words alone`() {
        ReleaseNotes.spans("set my_pref_key now") shouldBe listOf(Span.Plain("set my_pref_key now"))
    }
}
