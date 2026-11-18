package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Edge cases of the page rules in KcefPageRules.kt and KcefContentRules.kt (Zoukan's own files).
 * The desktop's BrowserPageRulesTest and KcefContentRulesTest cover the main cases.
 */
class PageRulesEdgeCasesTest {
    @AfterEach
    fun resetFilter() {
        KcefRequestFilter.filter = null
    }

    @Test
    fun `the top-frame check trims the address and accepts only the blank page itself`() {
        KcefNavigationPolicy.isAllowedMainFrameUrl("  https://example.org/  ") shouldBe true
        KcefNavigationPolicy.isAllowedMainFrameUrl("About:Blank") shouldBe true
        KcefNavigationPolicy.isAllowedMainFrameUrl("about:blank?x=1") shouldBe true
        KcefNavigationPolicy.isAllowedMainFrameUrl("about:blankpage") shouldBe false
        KcefNavigationPolicy.isAllowedMainFrameUrl("httpx://example.org") shouldBe false
        KcefNavigationPolicy.isAllowedMainFrameUrl("//example.org/no-scheme") shouldBe false
    }

    @Test
    fun `an extension's own address is let through only when it is exactly the one it asked for`() {
        KcefNavigationPolicy.isAllowedInWebView(null, null) shouldBe false
        KcefNavigationPolicy.isAllowedInWebView(null, "file:///a.html") shouldBe false
        KcefNavigationPolicy.isAllowedInWebView("file:///a.html ", "file:///a.html") shouldBe false
        KcefNavigationPolicy.isAllowedInWebView("data:text/html,x", "data:text/html,x") shouldBe true
    }

    @Test
    fun `an explicit port makes another origin unless it is the scheme's default`() {
        val limiter = JsDialogLimiter()
        limiter.allow("https://site.example:8443/page", "https://site.example/", 0) shouldBe false
        limiter.allow("http://site.example:80/page", "http://site.example/", 0) shouldBe true
        limiter.allow("https://site.example/page", "https://site.example:443/", 1) shouldBe true
    }

    @Test
    fun `a dialog that fits in the window again after the oldest one aged out is shown`() {
        val limiter = JsDialogLimiter(maxDialogs = 2, windowMillis = 1_000)
        val page = "https://site.example/"
        limiter.allow(page, page, 0) shouldBe true
        limiter.allow(page, page, 500) shouldBe true
        // Exactly one window after the first: the first no longer counts
        limiter.allow(page, page, 1_000) shouldBe true
        // A third inside the window silences the page
        limiter.allow(page, page, 1_200) shouldBe false
        limiter.allow(page, page, 10_000) shouldBe false
    }

    @Test
    fun `a dialog suppressed for another origin does not count against the page`() {
        val limiter = JsDialogLimiter(maxDialogs = 1, windowMillis = 10_000)
        val page = "https://site.example/"
        repeat(5) { limiter.allow(page, "https://ads.example/", it.toLong()) shouldBe false }
        limiter.allow(page, page, 10) shouldBe true
    }

    @Test
    fun `a failing failure handler is swallowed too`() {
        KcefCancelledNavigation.tellLater(
            post = { it.run() },
            tell = { error("client") },
            onFailure = { error("handler") },
        )
        KcefCancelledNavigation.tellLater(
            post = { error("looper") },
            tell = {},
            onFailure = { error("handler") },
        )
    }

    @Test
    fun `the request filter is not asked about an empty address, and a JVM error is not swallowed`() {
        var asked = 0
        KcefRequestFilter.filter =
            KcefRequestFilter.Filter { _, _ ->
                asked++
                true
            }
        KcefRequestFilter.shouldBlock("", "https://site.example/") shouldBe false
        asked shouldBe 0
        KcefRequestFilter.shouldBlock("https://ads.example/", null) shouldBe true
        asked shouldBe 1

        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw StackOverflowError() }
        assertThrows<StackOverflowError> { KcefRequestFilter.shouldBlock("https://ads.example/", null) }
        // The filter is the app's own code: a missing class or method in it is fatal too
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw NoClassDefFoundError("a class of the app") }
        assertThrows<NoClassDefFoundError> { KcefRequestFilter.shouldBlock("https://ads.example/", null) }
        // Any other failure fails open
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw IllegalStateException("block list not loaded") }
        KcefRequestFilter.shouldBlock("https://ads.example/", null) shouldBe false
    }
}
