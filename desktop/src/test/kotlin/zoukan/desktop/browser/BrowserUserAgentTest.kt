package zoukan.desktop.browser

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The built-in browser keeps Chromium's own user agent. An override (the app's default is an
 * Android one) leaves navigator.userAgent, navigator.userAgentData and the Sec-CH-UA headers
 * disagreeing, and Cloudflare's Turnstile fails such a browser once the user clicks its check.
 * A window cannot be driven in a unit test, so this guards the code: nothing in the browser
 * package may override the user agent through DevTools.
 */
class BrowserUserAgentTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `the built-in browser never overrides Chromium's user agent`() {
        val sources = File(root, "desktop/src/main/kotlin/zoukan/desktop/browser")
            .walk().filter { it.isFile && it.extension == "kt" }.toList()
        sources.shouldNotBeEmpty()
        sources.filter { "setUserAgentOverride" in it.readText() }.map { it.name }.shouldBeEmpty()
    }
}
