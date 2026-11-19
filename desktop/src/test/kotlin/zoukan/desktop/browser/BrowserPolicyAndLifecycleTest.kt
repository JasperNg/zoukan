package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.ChallengeFailures
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import eu.kanade.tachiyomi.network.interceptor.HostLocks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CookieVisitEnd
import xyz.nulldev.androidcompat.webkit.JsDialogLimiter
import xyz.nulldev.androidcompat.webkit.KcefCancelledNavigation
import xyz.nulldev.androidcompat.webkit.KcefNavigationPolicy
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider.CallChunkAssembler
import zoukan.desktop.testing.Await
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle, pop-up policy, lock visibility, and quit disposal for built-in browser windows
 * (see [BrowserWindow.disposeAllForQuit]), without Chromium on the classpath.
 */
class BrowserWindowLifecycleTest {

    // --- Pop-up decisions ---

    @Test
    fun `a pop-up to the same site loads in the window's own browser`() {
        BrowserPopups.decide("https://accounts.example.com/signin", "https://www.example.com/page", offerCrossSite = true) shouldBe
            BrowserPopups.Action.LoadHere("https://accounts.example.com/signin")
        // Also in a check's window, which drops only other sites' pop-ups
        BrowserPopups.decide("https://example.com/next", "https://example.com/", offerCrossSite = false) shouldBe
            BrowserPopups.Action.LoadHere("https://example.com/next")
    }

    @Test
    fun `a pop-up to another site is stopped and offered to the user`() {
        BrowserPopups.decide("https://other.example.org/x", "https://www.example.com/", offerCrossSite = true) shouldBe
            BrowserPopups.Action.Offer("https://other.example.org/x", "other.example.org")
        // Unknown opener (the blank page before the first load): not the same site
        BrowserPopups.decide("https://other.example.org/x", null, offerCrossSite = true) shouldBe
            BrowserPopups.Action.Offer("https://other.example.org/x", "other.example.org")
    }

    @Test
    fun `a pop-up to another site is dropped silently in a check's window or when it goes to a blocked host`() {
        BrowserPopups.decide("https://other.example.org/", "https://example.com/", offerCrossSite = false) shouldBe BrowserPopups.Action.Drop
        BrowserPopups.decide("https://ads.example.net/", "https://example.com/", offerCrossSite = true, isBlockedHost = { true }) shouldBe
            BrowserPopups.Action.Drop
    }

    @Test
    fun `a failing block check offers the pop-up instead of failing`() {
        BrowserPopups.decide("https://ads.example.net/", "https://example.com/", offerCrossSite = true, isBlockedHost = { error("boom") }) shouldBe
            BrowserPopups.Action.Offer("https://ads.example.net/", "ads.example.net")
    }

    @Test
    fun `a pop-up to a non-web address is dropped`() {
        listOf("file:///C:/Windows/win.ini", "javascript:alert(1)", "about:blank", "", null).forEach { url ->
            BrowserPopups.decide(url, "https://example.com/", offerCrossSite = true) shouldBe BrowserPopups.Action.Drop
        }
    }

    // --- Registry disposal and open failure ---

    @Test
    fun `a window refused before Chromium starts leaves the registry`() {
        val before = BrowserWindow.registeredCount()
        // Not a web address (and in a headless test run there is no display either): refused early
        val window = BrowserWindow("file:///etc/hosts")
        BrowserWindow.registeredCount() shouldBe before + 1

        window.open() shouldBe false
        BrowserWindow.registeredCount() shouldBe before
        // Its await returns at once instead of waiting for a window that never came
        window.await(timeoutMs = 5_000) shouldBe false
    }

    @Test
    fun `disposeAll disposes every window once and a second call does nothing`() {
        val registry = BrowserWindowRegistry()
        val disposed = mutableListOf<String>()
        registry.register { disposed += "a" }
        registry.register { disposed += "b" }

        registry.disposeAll()
        disposed shouldBe listOf("a", "b")

        // The quit paths run disposeAll more than once (at the quit, then before exitProcess)
        registry.disposeAll()
        disposed shouldBe listOf("a", "b")
    }

    @Test
    fun `a window that opens while the others are disposed is kept for the next call`() {
        val registry = BrowserWindowRegistry()
        val disposed = mutableListOf<String>()
        registry.register {
            disposed += "a"
            registry.register { disposed += "late" }
        }

        registry.disposeAll()
        disposed shouldBe listOf("a")

        registry.disposeAll()
        disposed shouldBe listOf("a", "late")
    }

    @Test
    fun `a window that closed by itself is not disposed again`() {
        val registry = BrowserWindowRegistry()
        var disposed = 0
        val registration = registry.register { disposed++ }
        registry.unregister(registration)

        registry.disposeAll()
        disposed shouldBe 0
    }

    @Test
    fun `one window that cannot close does not stop the others`() {
        val registry = BrowserWindowRegistry()
        var disposed = false
        registry.register { error("wedged") }
        registry.register { disposed = true }

        registry.disposeAll()
        disposed shouldBe true
    }

    // --- Lock visibility and waiting ---

    /** A browser window as the registry sees it: hidden while the app is locked, as BrowserWindow. */
    private class FakeWindow(private val registry: BrowserWindowRegistry) {
        var visible = !registry.locked
        var changes = 0
        val registration = registry.register(onLockChange = {
            changes++
            visible = !registry.locked
        }) {}
    }

    @Test
    fun `the windows hide while the app is locked and come back at the unlock`() {
        val registry = BrowserWindowRegistry()
        val a = FakeWindow(registry)
        registry.register(onLockChange = { error("wedged") }) {}
        val b = FakeWindow(registry)
        a.visible shouldBe true

        registry.setLocked(true)
        a.visible shouldBe false
        b.visible shouldBe false
        // A window opening while locked opens hidden
        FakeWindow(registry).visible shouldBe false

        registry.setLocked(false)
        a.visible shouldBe true
        b.visible shouldBe true
    }

    @Test
    fun `the same lock state twice tells nobody, and a closed window is not told`() {
        val registry = BrowserWindowRegistry()
        val open = FakeWindow(registry)
        val closed = FakeWindow(registry)
        registry.unregister(closed.registration)

        registry.setLocked(false)
        open.changes shouldBe 0
        registry.setLocked(true)
        registry.setLocked(true)
        open.changes shouldBe 1
        closed.changes shouldBe 0
    }

    @Test
    fun `a challenge waits for the unlock`() {
        val registry = BrowserWindowRegistry()
        registry.awaitUnlocked(10) shouldBe true

        registry.setLocked(true)
        registry.awaitUnlocked(50) shouldBe false

        val challenge = Thread.currentThread()
        val unlocker = Thread {
            // Unlocks only once the challenge waits, so the wait is what the unlock ends
            Await.until("the challenge to wait for the unlock", state = { challenge.state }) {
                challenge.state == Thread.State.TIMED_WAITING || challenge.state == Thread.State.WAITING
            }
            registry.setLocked(false)
        }.apply { start() }
        val started = System.nanoTime()
        registry.awaitUnlocked(10_000) shouldBe true
        // Woken by the unlock, not the timeout
        (System.nanoTime() - started < 5_000_000_000L) shouldBe true
        unlocker.join()
    }

    @Test
    fun `a challenge solved some other way while locked stops waiting`() {
        val registry = BrowserWindowRegistry()
        registry.setLocked(true)
        val started = System.nanoTime()
        registry.awaitUnlocked(10_000) { true } shouldBe false
        (System.nanoTime() - started < 5_000_000_000L) shouldBe true
    }
}

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

/** Cloudflare challenge serialization, cooldowns, and browser clearance URL filtering. */
class BrowserChallengeAndClearanceTest {
    private val previous = DesktopBrowser.challengeSolver

    @AfterEach
    fun restore() {
        DesktopBrowser.challengeSolver = previous
    }

    @Test
    fun `parallel challenges for one host show one window`() {
        val solved = AtomicBoolean(false)
        val windows = AtomicInteger(0)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val asked = AtomicInteger(0)
        DesktopBrowser.challengeSolver = DesktopBrowser.ChallengeSolver { _, _, _ ->
            windows.incrementAndGet()
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            // The window stays open until every other challenge has arrived and waits for the host:
            // a thread that asked and is parked can only be waiting for the host's lock
            Await.until("the other challenges to wait for the host", timeoutMillis = 5_000) {
                asked.get() == 8 && threads.filter { it !== Thread.currentThread() }.all { it.state == Thread.State.WAITING }
            }
            solved.set(true)
            concurrent.decrementAndGet()
            true
        }
        val pool = Executors.newFixedThreadPool(8) { task -> Thread(task).also { threads += it } }
        val start = CountDownLatch(1)
        val results = (1..8).map {
            pool.submit<Boolean> {
                start.await()
                asked.incrementAndGet()
                DesktopBrowser.solveChallenge("https://example.org/page$it", "UA") { solved.get() }
            }
        }
        start.countDown()
        results.map { it.get(10, TimeUnit.SECONDS) } shouldBe List(8) { true }
        pool.shutdown()

        windows.get() shouldBe 1
        maxConcurrent.get() shouldBe 1
    }

    @Test
    fun `different hosts are not serialized`() {
        val locks = HostLocks()
        val inA = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            locks.withLock("a.example") {
                inA.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        inA.await(5, TimeUnit.SECONDS) shouldBe true
        // Another host gets its lock while a.example is held
        locks.withLock("b.example") { "done" } shouldBe "done"
        release.countDown()
        thread.join(5_000)
    }

    @Test
    fun `a challenge the user closed is not shown again within the cooldown`() {
        val minute = TimeUnit.MINUTES.toNanos(1)
        val failures = ChallengeFailures(cooldownNanos = 5 * minute)
        failures.failed("example.org", now = 0)
        // A request that starts after the failure, a minute later
        failures.shouldFailFast("example.org", waitStarted = minute, now = minute) shouldBe true
        // After the cooldown a new attempt is allowed again
        failures.shouldFailFast("example.org", waitStarted = 6 * minute, now = 6 * minute) shouldBe false
        // Another host is not affected
        failures.shouldFailFast("other.org", waitStarted = minute, now = minute) shouldBe false
    }

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
