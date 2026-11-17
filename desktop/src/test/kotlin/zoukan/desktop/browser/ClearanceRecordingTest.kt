package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClearanceRecordingTest {
    @Test
    fun `pages without cookies are skipped`() {
        for (url in listOf("about:blank", "data:text/html,hi", "chrome://version", "file:///tmp/a.html", "", "not a url")) {
            clearanceUrlOrNull(url) shouldBe null
        }
    }

    @Test
    fun `http and https pages are kept`() {
        clearanceUrlOrNull("https://example.com/a")?.host shouldBe "example.com"
        clearanceUrlOrNull("http://example.com/")?.host shouldBe "example.com"
    }

    @Test
    fun `about blank returns before touching the app's services`() {
        // Injekt has nothing registered here: reaching it would log a WARN with a stack trace (it
        // never throws), so this checks only that the call returns quietly
        recordClearanceUserAgent("about:blank", null)
    }
}
