package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Button keys are the same on every run and machine, and never hold the recording account's name. */
class ButtonCrawlKeyTest {

    @Test
    fun `a path in a user's home folder becomes a placeholder, whatever the name's length or spaces`() {
        ButtonCrawl.stable("""Data folder C:\Users\SomeOne\AppData\Local\Temp\zoukan-c...""") shouldBe "Data folder <home>…"
        ButtonCrawl.stable("""Data folder C:\Users\A Much Longer Name\AppData\Local\Te...""") shouldBe "Data folder <home>…"
        ButtonCrawl.stable("""Kept in files in C:\Users\Some...""") shouldBe "Kept in files in <home>…"
        ButtonCrawl.stable("""Escaped C:\\Users\\someone\\x""") shouldBe "Escaped <home>…"
        ButtonCrawl.stable("Storage location /Users/some one/Library/Zoukan") shouldBe "Storage location <home>…"
        ButtonCrawl.stable("Data folder /home/someone/.local/share/zoukan") shouldBe "Data folder <home>…"
    }

    @Test
    fun `digits and dates still become a hash, and other paths stay`() {
        ButtonCrawl.stable("Data folder /tmp/zoukan-crawl123456789012345678") shouldBe "Data folder /tmp/zoukan-crawl#"
        ButtonCrawl.stable("Last update: Nov 17, 2026") shouldBe "Last update: ##, #"
    }
}
