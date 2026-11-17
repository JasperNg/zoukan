package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The built-in browser runs Chromium's own user agent, and a clearance it gets is recorded with
 * [DesktopBrowser.userAgent]. That value is first assumed from Chromium's version, then replaced by
 * what Chromium reports in a window's blank page, when the report is a plausible user agent.
 */
class ChromiumUserAgentTest {
    private val assumed = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    private val reported = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"

    @Test
    fun `a valid reported user agent wins over the assumed one`() {
        JcefRuntime.chromiumUserAgent(reported, assumed) shouldBe reported
    }

    @Test
    fun `a failed or implausible report keeps the assumed user agent`() {
        listOf(
            null,
            "",
            "   ",
            "null",
            "Chrome/153.0.0.0",
            "$reported\nX-Injected: 1",
            "$reported\rX-Injected: 1",
        ).forEach { JcefRuntime.chromiumUserAgent(it, assumed) shouldBe assumed }
    }

    @Test
    fun `learning the reported user agent sets the one the app records clearances with`() {
        val previous = DesktopBrowser.userAgent
        try {
            DesktopBrowser.userAgent = assumed
            JcefRuntime.learnUserAgent("null")
            DesktopBrowser.userAgent shouldBe assumed

            JcefRuntime.learnUserAgent(reported)
            DesktopBrowser.userAgent shouldBe reported
            JcefRuntime.userAgentKnown shouldBe true
        } finally {
            DesktopBrowser.userAgent = previous
        }
    }
}
