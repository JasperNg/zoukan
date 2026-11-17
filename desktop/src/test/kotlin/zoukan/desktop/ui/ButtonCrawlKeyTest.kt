package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Button keys are the same on every run and machine, and never hold the recording account's name. */
class ButtonCrawlKeyTest {

    @Test
    fun `a user's home folder becomes a placeholder, also when the label cut it short`() {
        ButtonCrawl.stable("""Data folder C:\Users\SomeOne\AppData\Local\Temp\zoukan-c...""") shouldBe
            """Data folder <home>\AppData\Local\Temp\zoukan-c..."""
        ButtonCrawl.stable("""Kept in files in C:\Users\Some...""") shouldBe "Kept in files in <home>"
        ButtonCrawl.stable("Storage location /Users/someone/Library/Zoukan") shouldBe "Storage location <home>/Library/Zoukan"
        ButtonCrawl.stable("Data folder /home/someone/.local/share/zoukan") shouldBe "Data folder <home>/.local/share/zoukan"
    }

    @Test
    fun `digits and dates still become a hash, and other paths stay`() {
        ButtonCrawl.stable("Data folder /tmp/zoukan-crawl123456789012345678") shouldBe "Data folder /tmp/zoukan-crawl#"
        ButtonCrawl.stable("Last update: Nov 17, 2026") shouldBe "Last update: ##, #"
    }
}
