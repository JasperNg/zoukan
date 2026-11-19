package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider

/** A failed load is logged by host only: its path and query can carry a sign-in code or token. */
class KcefLoadErrorLogTest {
    @Test
    fun `only the host of a URL is logged`() {
        KcefWebViewProvider.hostForLog("https://user:pass@example.org:8443/callback?code=SECRET#frag") shouldBe "example.org"
        KcefWebViewProvider.hostForLog("http://sub.example.org/path/to?token=abc") shouldBe "sub.example.org"
    }

    @Test
    fun `a URL without a host gives its scheme`() {
        KcefWebViewProvider.hostForLog("about:blank") shouldBe "about:"
        KcefWebViewProvider.hostForLog("data:text/plain,code=1") shouldBe "data:"
    }

    @Test
    fun `a missing or malformed URL does not throw`() {
        KcefWebViewProvider.hostForLog(null) shouldBe "?"
        KcefWebViewProvider.hostForLog("") shouldBe "?"
        KcefWebViewProvider.hostForLog("https://exa mple.org/?code=SECRET") shouldBe "?"
        KcefWebViewProvider.hostForLog("::::") shouldBe "?"
    }
}
