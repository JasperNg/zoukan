package zoukan.desktop.browser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CookieVisitEnd
import xyz.nulldev.androidcompat.webkit.JsDialogLimiter
import xyz.nulldev.androidcompat.webkit.KcefCancelledNavigation
import xyz.nulldev.androidcompat.webkit.KcefNavigationPolicy
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider.CallChunkAssembler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What pages may do in the built-in browser and extensions' WebViews: navigations, dialogs, cookies, and JS call assembly. */
class BrowserPageRulesTest {

    // --- Navigation & cancelled-navigation policy ---

    @Test
    fun `the top frame goes to web pages and the blank page only`() {
        listOf("https://example.org/a", "http://example.org", "HTTPS://EXAMPLE.ORG", "about:blank", "about:blank#x")
            .forEach { KcefNavigationPolicy.isAllowedMainFrameUrl(it) shouldBe true }
        listOf(
            "data:text/html,<p>hi</p>",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "blob:https://example.org/1234",
            "chrome://settings",
            "komikku://anilist-auth?code=1",
            "about:srcdoc",
            "",
            null,
        ).forEach { KcefNavigationPolicy.isAllowedMainFrameUrl(it) shouldBe false }
    }

    @Test
    fun `an extension's WebView also loads the address it asked for, and nothing else of that kind`() {
        val asked = "file:///android_asset/index.html"
        KcefNavigationPolicy.isAllowedInWebView(asked, asked) shouldBe true
        KcefNavigationPolicy.isAllowedInWebView("file:///etc/passwd", asked) shouldBe false
        KcefNavigationPolicy.isAllowedInWebView("data:text/html,x", null) shouldBe false
        KcefNavigationPolicy.isAllowedInWebView("https://example.org", null) shouldBe true
    }

    @Test
    fun `the client hears of a cancelled navigation later, not on Chromium's thread`() {
        val posted = mutableListOf<Runnable>()
        val failures = mutableListOf<Throwable>()
        var told = 0
        KcefCancelledNavigation.tellLater(post = { posted += it }, tell = { told++ }, onFailure = { failures += it })
        told shouldBe 0
        posted.size shouldBe 1
        posted.single().run()
        told shouldBe 1

        // A client that throws is logged, and the looper goes on
        KcefCancelledNavigation.tellLater(post = { it.run() }, tell = { error("boom") }, onFailure = { failures += it })
        failures.single().message shouldBe "boom"
        // as does a looper that is gone
        KcefCancelledNavigation.tellLater(post = { error("no looper") }, tell = { told++ }, onFailure = { failures += it })
        failures.last().message shouldBe "no looper"
        told shouldBe 1
    }

    // --- JavaScript dialog origin & rate limiting ---

    @Test
    fun `a dialog from a frame of another origin is suppressed`() {
        val limiter = JsDialogLimiter()
        limiter.allow("https://site.example/page", "https://ads.example/", 0) shouldBe false
        limiter.allow("https://site.example/page", "http://site.example/", 0) shouldBe false
        limiter.allow("https://site.example/page", null, 0) shouldBe false
        limiter.allow(null, "https://site.example/", 0) shouldBe false
        limiter.allow("https://site.example/page", "https://site.example/", 0) shouldBe true
        limiter.allow("https://SITE.example:443/page", "https://site.example", 1) shouldBe true
    }

    @Test
    fun `a page that opens too many dialogs is silenced until the top frame moves on`() {
        val limiter = JsDialogLimiter(maxDialogs = 3, windowMillis = 10_000)
        val page = "https://site.example/page"
        val origin = "https://site.example/"
        (0 until 3).forEach { limiter.allow(page, origin, it * 1000L) shouldBe true }
        limiter.allow(page, origin, 4000) shouldBe false
        // Silenced for the page, even after the time has passed, and after a jump to an anchor
        limiter.allow(page, origin, 60_000) shouldBe false
        limiter.allow("$page#top", origin, 60_000) shouldBe false
        // Another page starts over
        limiter.allow("https://site.example/other", origin, 61_000) shouldBe true
    }

    @Test
    fun `a page with no web origin gets its own dialogs, under the same limit`() {
        val limiter = JsDialogLimiter(maxDialogs = 3, windowMillis = 10_000)
        // The blank page, or loadDataWithBaseURL(null, ...): Chromium gives the dialog no web origin
        (0 until 3).forEach { limiter.allow("about:blank", "", it * 1000L) shouldBe true }
        limiter.allow("about:blank", null, 4000) shouldBe false
        limiter.allow("about:blank", "null", 60_000) shouldBe false
        // A web page's iframe on a blank page is another frame's
        JsDialogLimiter().allow("about:blank", "https://ads.example/", 0) shouldBe false
        JsDialogLimiter().allow("data:text/html,x", "about:blank", 0) shouldBe true
    }

    @Test
    fun `a dialog with no web origin on a web page is suppressed`() {
        val limiter = JsDialogLimiter()
        limiter.allow("https://site.example/page", "", 0) shouldBe false
        limiter.allow("https://site.example/page", "about:blank", 0) shouldBe false
        limiter.allow("https://site.example/page", "data:text/html,x", 0) shouldBe false
    }

    @Test
    fun `dialogs spread out in time are all shown`() {
        val limiter = JsDialogLimiter(maxDialogs = 3, windowMillis = 10_000)
        (0 until 10).forEach { limiter.allow("https://site.example/", "https://site.example/", it * 5000L) shouldBe true }
    }

    // --- Browser cookie sync scope ---

    @Test
    fun `only the start site's cookies go to Chromium`() {
        val scope = BrowserCookieScope("https://www.source.co.uk/manga/1")
        scope.copiesToChromium("source.co.uk") shouldBe true
        scope.copiesToChromium(".source.co.uk") shouldBe true
        scope.copiesToChromium("cdn.source.co.uk") shouldBe true
        // A public suffix is no site of its own, and another site under it is another site
        scope.copiesToChromium("co.uk") shouldBe false
        scope.copiesToChromium("other.co.uk") shouldBe false
        scope.copiesToChromium("other.example") shouldBe false
        scope.copiesToChromium("") shouldBe false
    }

    @Test
    fun `cookies come back from the start site and from sites the user chose`() {
        val scope = BrowserCookieScope("https://source.example/")
        scope.copiesFromChromium("https://www.source.example/chapter") shouldBe true
        // A script sends the window to an ad: its cookies stay in Chromium
        scope.navigating("https://ads.example/landing", userGesture = false, isRedirect = false)
        scope.copiesFromChromium("https://ads.example/landing") shouldBe false
        // and so does where the ad redirects to
        scope.navigating("https://tracker.example/", userGesture = false, isRedirect = true)
        scope.copiesFromChromium("https://tracker.example/") shouldBe false
        // A link the user clicked
        scope.navigating("https://wiki.example/page", userGesture = true, isRedirect = false)
        scope.copiesFromChromium("https://wiki.example/page") shouldBe true
        // An address the user typed, or a pop-up opened from the bar
        scope.userOpened("https://typed.example/")
        scope.copiesFromChromium("https://typed.example/x") shouldBe true
        scope.copiesFromChromium(null) shouldBe false
        scope.copiesFromChromium("about:blank") shouldBe false
    }

    @Test
    fun `a trusted page's redirect to a new domain keeps its clearance`() {
        val scope = BrowserCookieScope("https://old-source.example/")
        scope.navigating("https://old-source.example/", userGesture = false, isRedirect = false)
        scope.navigating("https://new-source.example/", userGesture = false, isRedirect = true)
        scope.copiesFromChromium("https://new-source.example/") shouldBe true
        // Going back to a trusted site later is still trusted
        scope.navigating("https://old-source.example/again", userGesture = false, isRedirect = false)
        scope.copiesFromChromium("https://old-source.example/again") shouldBe true
    }

    // --- JavaScript-interface call chunk assembly ---

    // At most 3 chunks of 4 characters, 2 calls unfinished at once
    private val chunks = CallChunkAssembler(chunkChars = 4, maxCallChars = 12, maxPending = 2, maxPendingChars = 100)

    @Test
    fun `a call comes back whole once all its chunks are in`() {
        chunks.add("a", 0, 3, "abcd") shouldBe null
        chunks.add("a", 1, 3, "efgh") shouldBe null
        chunks.add("a", 2, 3, "ij") shouldBe "abcdefghij"
        // And is then forgotten
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }
    }

    @Test
    fun `a chunk count or index out of range is refused`() {
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, -1, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 0, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 200_000_000, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 4, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", -1, 3, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 3, 3, "") }
        shouldThrow<IllegalArgumentException> { chunks.add("a", 0, 3, "abcde") }
        // A call that does not start with its first chunk
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }

        chunks.add("a", 0, 2, "abcd") shouldBe null
        // The count cannot change part way
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 3, "efgh") }
        chunks.add("a", 1, 2, "ef") shouldBe "abcdef"
    }

    @Test
    fun `only the newest unfinished calls are kept`() {
        chunks.add("a", 0, 2, "abcd") shouldBe null
        chunks.add("b", 0, 2, "bbbb") shouldBe null
        chunks.add("c", 0, 2, "cccc") shouldBe null

        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 2, "ef") }
        chunks.add("b", 1, 2, "b") shouldBe "bbbbb"
        chunks.add("c", 1, 2, "c") shouldBe "ccccc"
    }

    @Test
    fun `clear drops unfinished calls`() {
        chunks.add("a", 0, 2, "abcd") shouldBe null
        chunks.clear()
        shouldThrow<IllegalArgumentException> { chunks.add("a", 1, 2, "ef") }
    }

    @Test
    fun `the unfinished calls together stay within the character budget`() {
        // 10 characters in all, up to 5 calls
        val budget = CallChunkAssembler(chunkChars = 4, maxCallChars = 12, maxPending = 5, maxPendingChars = 10)
        budget.add("a", 0, 3, "aaaa") shouldBe null
        budget.add("b", 0, 3, "bbbb") shouldBe null
        budget.pendingChars shouldBe 8
        // Over the budget: the oldest call goes
        budget.add("c", 0, 3, "cccc") shouldBe null
        budget.pendingChars shouldBe 8
        shouldThrow<IllegalArgumentException> { budget.add("a", 1, 3, "aaaa") }
        // A finished call gives its characters back
        budget.add("b", 1, 2 + 1, "bb") shouldBe null
        budget.pendingChars shouldBe 10
        budget.add("b", 2, 3, "") shouldBe "bbbbbb"
        budget.pendingChars shouldBe 4
        // A call that alone outgrows the budget is refused, and dropped
        budget.add("c", 1, 3, "cccc") shouldBe null
        shouldThrow<IllegalArgumentException> { budget.add("c", 2, 3, "cccc") }
        budget.pendingChars shouldBe 0
        shouldThrow<IllegalArgumentException> { budget.add("c", 2, 3, "c") }
        budget.clear()
        budget.pendingChars shouldBe 0
    }

    // --- Cookie visit completion timing ---

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val calls = AtomicInteger()
    private val called = CountDownLatch(1)
    private val completes = CopyOnWriteArrayList<Boolean>()
    private val onDone = { complete: Boolean ->
        completes += complete
        calls.incrementAndGet()
        called.countDown()
    }

    /**
     * Returns once every task the single-thread [scheduler] has due within [millis] has run: a
     * task scheduled after them on the same thread runs only after them.
     */
    private fun afterTasksDueWithin(millis: Long) {
        scheduler.schedule({}, millis, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun tearDown() {
        scheduler.shutdownNow()
    }

    @Test
    fun `the visit ends after its last cookie, once`() {
        val end = CookieVisitEnd(onDone, scheduler, quietMillis = 10_000, maxMillis = 10_000)
        end.visited(0, 2)
        calls.get() shouldBe 0
        end.visited(1, 2)
        calls.get() shouldBe 1
        end.finish()
        calls.get() shouldBe 1
        completes shouldBe listOf(true)
    }

    @Test
    fun `a visit that ends by the time limit is not complete`() {
        val end = CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 300)
        end.visited(0, 3)
        called.await(5, TimeUnit.SECONDS) shouldBe true
        completes shouldBe listOf(false)
        end.visited(2, 3)
        completes shouldBe listOf(false)
    }

    @Test
    fun `a visit that ends by the quiet time is not complete`() {
        CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 10_000)
        called.await(5, TimeUnit.SECONDS) shouldBe true
        completes shouldBe listOf(false)
    }

    @Test
    fun `a visit with no cookies ends after the quiet time`() {
        CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 10_000)
        called.await(5, TimeUnit.SECONDS) shouldBe true
        afterTasksDueWithin(50)
        calls.get() shouldBe 1
    }

    @Test
    fun `a visit under way is not ended by the quiet time, only by the limit`() {
        val end = CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 300)
        end.visited(0, 3)
        // Past the quiet time: its timer has run and found the visit under way
        afterTasksDueWithin(100)
        calls.get() shouldBe 0
        called.await(5, TimeUnit.SECONDS) shouldBe true
        end.visited(1, 3)
        end.visited(2, 3)
        calls.get() shouldBe 1
    }
}
