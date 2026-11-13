package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A browser window that fails to open must not stay in the registry the quit paths dispose. */
class BrowserWindowOpenFailureTest {
    @Test
    fun `a window refused before Chromium starts leaves the registry`() {
        val before = BrowserWindow.registeredCount()
        // Not a web address (and in a headless test run there is no display either): refused early
        val window = BrowserWindow("file:///etc/hosts", userAgent = null)
        BrowserWindow.registeredCount() shouldBe before + 1

        window.open() shouldBe false
        BrowserWindow.registeredCount() shouldBe before
        // Its await returns at once instead of waiting for a window that never came
        window.await(timeoutMs = 5_000) shouldBe false
    }
}
