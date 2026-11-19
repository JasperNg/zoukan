package zoukan.desktop.browser.blocking

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefPopupPolicy
import xyz.nulldev.androidcompat.webkit.KcefRequestFilter

/** What extensions' hidden WebViews do with pop-ups and with the app's request filter. */
class KcefContentRulesTest {
    @AfterEach
    fun clearFilter() {
        KcefRequestFilter.filter = null
        KcefRequestFilter.reportError = null
    }

    @Test
    fun `a hidden WebView loads a pop-up in place only when the extension let scripts open windows`() {
        KcefPopupPolicy.loadInPlace("https://example.com/next", javaScriptCanOpenWindows = true) shouldBe "https://example.com/next"
        KcefPopupPolicy.loadInPlace("HTTP://example.com/", javaScriptCanOpenWindows = true) shouldBe "HTTP://example.com/"
        KcefPopupPolicy.loadInPlace("https://example.com/next", javaScriptCanOpenWindows = false) shouldBe null
    }

    @Test
    fun `a hidden WebView never loads a pop-up that is not a web address`() {
        listOf("file:///etc/hosts", "javascript:alert(1)", "about:blank", "", null).forEach {
            KcefPopupPolicy.loadInPlace(it, javaScriptCanOpenWindows = true) shouldBe null
        }
    }

    @Test
    fun `the request filter blocks what the app's filter says and fails open`() {
        KcefRequestFilter.shouldBlock("https://ads.example/x", "https://site.example/") shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { url, _ -> url.contains("ads") }
        KcefRequestFilter.shouldBlock("https://ads.example/x", "https://site.example/") shouldBe true
        KcefRequestFilter.shouldBlock("https://cdn.example/x", "https://site.example/") shouldBe false
        KcefRequestFilter.shouldBlock(null, "https://site.example/") shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> error("boom") }
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
    }

    @Test
    fun `the built-in browser's blocker throws nothing back into native code and reports the first error`() {
        val reported = mutableListOf<Throwable>()
        KcefRequestFilter.reportError = { reported += it }
        // Runs in Chromium's onBeforeResourceLoad and onBeforePopup: every failure lets the request through
        ContentBlocker.failOpen { true } shouldBe true
        ContentBlocker.failOpen { error("block list not loaded") } shouldBe false
        reported shouldBe emptyList()
        ContentBlocker.failOpen { throw OutOfMemoryError("Java heap space") } shouldBe false
        ContentBlocker.failOpen { throw NoClassDefFoundError("a class of the app") } shouldBe false
        // Once, shared with the extension WebViews' filter: the checks run for every request
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw StackOverflowError() }
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
        reported.map { it::class } shouldBe listOf(OutOfMemoryError::class)

        // A report that fails is not thrown back either
        KcefRequestFilter.reportError = { throw IllegalStateException("no report folder") }
        ContentBlocker.failOpen { throw StackOverflowError() } shouldBe false
    }
}
