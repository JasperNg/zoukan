package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CefCookieSyncTest {
    private var previousCookieHandler: CookieHandler? = null

    @BeforeEach
    fun saveCookieHandler() {
        previousCookieHandler = CookieHandler.getDefault()
        CefCookieSync.pending.drain()
        CefCookieSync.copyToCef(CookieManager().cookieStore, mockk(relaxed = true))
    }

    @AfterEach
    fun restoreCookieHandler() {
        CookieHandler.setDefault(previousCookieHandler)
        CefCookieSync.pending.drain()
        CefCookieSync.copyToCef(CookieManager().cookieStore, mockk(relaxed = true))
        CefCookieSync.onCopiedFromCef = null
    }

    @Test
    fun `pathMatches applies RFC 6265 segment-boundary rules`() {
        pathMatches("/docs", "/docs").shouldBeTrue()
        pathMatches("/docs/page", "/docs").shouldBeTrue()
        pathMatches("/docs/page", "/docs/").shouldBeTrue()
        pathMatches("", "/").shouldBeTrue()
        pathMatches("/any", "/").shouldBeTrue()

        // Look-alike prefix without slash boundary does not match
        pathMatches("/docsearch", "/docs").shouldBeFalse()
        pathMatches("/docs", "/docs/").shouldBeFalse()
    }

    @Test
    fun `CefCookieDeletion matches host-only domain and path scopes`() {
        val broad = CefCookieDeletion("https://sub.example.com/a", "sid")
        broad.isExact.shouldBeFalse()
        broad.matches("sub.example.com", "SID", "sub.example.com", "/").shouldBeTrue()
        broad.matches("sub.example.com", "sid", ".example.com", "/other").shouldBeTrue()
        broad.matches("notexample.com", "sid", ".example.com", "/").shouldBeFalse()

        val exact = CefCookieDeletion("https://sub.example.com/a", "sid", domain = "example.com", path = "/a")
        exact.isExact.shouldBeTrue()
        exact.matches("sub.example.com", "sid", ".Example.com", "/a").shouldBeTrue()
        exact.matches("sub.example.com", "sid", "sub.example.com", "/a").shouldBeFalse()
        exact.matches("sub.example.com", "sid", ".example.com", "/b").shouldBeFalse()
        exact.matches("sub.example.com", "sid", ".example.com", null).shouldBeFalse()
    }

    @Test
    fun `PendingCefCookieDeletions queues until ready and clears earlier items on delete-all`() {
        val pending = PendingCefCookieDeletions()
        val first = CefCookieDeletion("https://a.example/", "c1")
        val clearAll = CefCookieDeletion("", "")
        val after = CefCookieDeletion("https://b.example/", "c2")

        pending.addUnless(first) { false }.shouldBeFalse()
        pending.addUnless(clearAll) { false }.shouldBeFalse()
        pending.addUnless(after) { false }.shouldBeFalse()

        pending.drain() shouldContainExactly listOf(clearAll, after)
        pending.drain() shouldContainExactly emptyList()

        // When ready immediately, nothing is queued
        pending.addUnless(first) { true }.shouldBeTrue()
        pending.drain() shouldContainExactly emptyList()
    }

    @Test
    fun `expiresAt converts Max-Age known expiry and session cookies`() {
        val session = HttpCookie("s", "1").apply { maxAge = -1 }
        CefCookieSync.expiresAt(session, known = 999_999L, now = 1_000L).shouldBeNull()

        val maxAgeOnly = HttpCookie("m", "1").apply { maxAge = 60 }
        CefCookieSync.expiresAt(maxAgeOnly, known = null, now = 1_000L) shouldBe 61_000L

        val withKnown = HttpCookie("k", "1").apply { maxAge = 60 }
        CefCookieSync.expiresAt(withKnown, known = 50_000L, now = 1_000L) shouldBe 50_000L
    }

    @Test
    fun `toCefCookie and toSetCookieHeader preserve host-only domain expiry Secure and HttpOnly`() {
        val expiresMillis = 1_750_000_000_000L
        val domainCookie =
            HttpCookie("cf_clearance", "tok").apply {
                domain = ".example.com"
                path = "/manga"
                secure = true
                isHttpOnly = true
                maxAge = 3600
            }
        val cefDomain = with(CefCookieSync) { domainCookie.toCefCookie(domainCookie.domain.orEmpty(), expiresMillis) }
        cefDomain.domain shouldBe ".example.com"
        cefDomain.path shouldBe "/manga"
        cefDomain.secure shouldBe true
        cefDomain.httponly shouldBe true
        cefDomain.hasExpires shouldBe true
        cefDomain.expires shouldBe Date(expiresMillis)

        val domainHeader = with(CefCookieSync) { cefDomain.toSetCookieHeader() }
        domainHeader shouldContain "cf_clearance=tok"
        domainHeader shouldContain "; Domain=.example.com"
        domainHeader shouldContain "; Path=/manga"
        domainHeader shouldContain "; Expires="
        domainHeader shouldContain "GMT"
        domainHeader shouldContain "; Secure"
        domainHeader shouldContain "; HttpOnly"

        // Host-only session cookie has empty CEF domain and omits Domain and Expires in Set-Cookie
        val hostOnlySession =
            HttpCookie("sess", "abc").apply {
                path = null
                secure = false
                isHttpOnly = false
                maxAge = -1
            }
        val cefHostOnly = with(CefCookieSync) { hostOnlySession.toCefCookie("", null) }
        cefHostOnly.domain shouldBe ""
        cefHostOnly.path shouldBe "/"
        cefHostOnly.hasExpires shouldBe false
        cefHostOnly.expires.shouldBeNull()

        val cefHostOnlyReturnedFromChromium =
            CefCookie("sess", "abc", "example.com", "/", false, false, Date(), Date(), false, null)
        val hostOnlyHeader = with(CefCookieSync) { cefHostOnlyReturnedFromChromium.toSetCookieHeader() }
        hostOnlyHeader shouldBe "sess=abc; Path=/"
        hostOnlyHeader shouldNotContain "Domain="
        hostOnlyHeader shouldNotContain "Expires="
        hostOnlyHeader shouldNotContain "Secure"
        hostOnlyHeader shouldNotContain "HttpOnly"
    }

    @Test
    fun `staleAppCookies only removes copied cookies missing from Chromium for matching path`() {
        val copied =
            setOf(
                SyncedCookie("gone", ".example.com", "/app", "1"),
                SyncedCookie("kept", "example.com", "/app", "2"),
                SyncedCookie("otherPath", "example.com", "/other", "3"),
            )
        val app =
            listOf(
                SyncedCookie("gone", "example.com", "/app", "1"),
                SyncedCookie("kept", "example.com", "/app", "2"),
                SyncedCookie("otherPath", "example.com", "/other", "3"),
                SyncedCookie("addedLater", "example.com", "/app", "4"),
            )
        val inChromium =
            listOf(
                SyncedCookie("kept", "example.com", "/app", "changed-in-cef"),
            )

        staleAppCookies(app, copied, inChromium, "/app/read") shouldContainExactly listOf(
            SyncedCookie("gone", "example.com", "/app", "1"),
        )
    }

    @Test
    fun `cookiesByUri includes Secure cookies stored under https`() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val httpUri = URI("http://example.com/")
        val secureCookie = HttpCookie("sec", "v").apply {
            path = "/"
            secure = true
        }
        val plainCookie = HttpCookie("plain", "v2").apply {
            path = "/"
            secure = false
        }
        manager.cookieStore.add(httpUri, secureCookie)
        manager.cookieStore.add(httpUri, plainCookie)

        val entries = manager.cookieStore.cookiesByUri().map { it.second.name }
        entries shouldContainExactlyInAnyOrder listOf("sec", "plain")
    }

    @Test
    fun `copyToCef syncs host-only domain expiry filter and pending deletions`() {
        val baseStore = CookieManager(null, CookiePolicy.ACCEPT_ALL).cookieStore
        val store = object : CookieStore by baseStore, CookieExpirySource {
            override fun expiresAt(cookie: HttpCookie): Long? =
                if (cookie.name == "knownExp") 1_800_000_000_000L else null
        }

        val siteA = URI("https://a.example/")
        val siteB = URI("https://b.example/")
        store.add(
            siteA,
            HttpCookie("knownExp", "1").apply {
                path = "/"
                maxAge = 100
                secure = true
            },
        )
        store.add(
            siteA,
            HttpCookie("dom", "2").apply {
                domain = ".a.example"
                path = "/docs"
                isHttpOnly = true
                maxAge = -1
            },
        )
        store.add(
            siteB,
            HttpCookie("excluded", "3").apply {
                path = "/"
                maxAge = 100
            },
        )

        // Queue a pending deletion before Chromium is initialized
        CefCookieSync.pending.addUnless(CefCookieDeletion("https://a.example/", "old")) { false }

        val cef = mockk<CefCookieManager>(relaxed = true)
        val urls = mutableListOf<String>()
        val cookies = mutableListOf<CefCookie>()
        every { cef.setCookie(capture(urls), capture(cookies)) } returns true

        CefCookieSync.copyToCef(store, cef) { host ->
            if (host == "b.example") error("filter exception must not abort")
            host == "a.example"
        }

        verify(exactly = 1) { cef.deleteCookies("https://a.example/", "old") }
        CefCookieSync.pending.drain() shouldBe emptyList()

        urls shouldContainExactly listOf("https://a.example", "https://a.example")
        val byName = cookies.associateBy { it.name }
        byName.keys shouldContainExactlyInAnyOrder setOf("knownExp", "dom")
        byName.getValue("knownExp").apply {
            domain shouldBe ""
            secure shouldBe true
            hasExpires shouldBe true
            expires shouldBe Date(1_800_000_000_000L)
        }
        byName.getValue("dom").apply {
            domain shouldBe ".a.example"
            httponly shouldBe true
            hasExpires shouldBe false
        }

        CefCookieSync.copiedToCefSnapshot().map { it.name } shouldContainExactlyInAnyOrder listOf("knownExp", "dom")
    }

    @Test
    fun `removeStaleAppCookies removes missing copied cookies from default CookieHandler`() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
        val uri = URI("https://example.com/reader")
        manager.cookieStore.add(uri, HttpCookie("stale", "1").apply { path = "/reader" })
        manager.cookieStore.add(uri, HttpCookie("keep", "2").apply { path = "/reader" })

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(manager.cookieStore, cef)

        CefCookieSync.removeStaleAppCookies(
            url = "https://example.com/reader",
            inChromium = listOf(SyncedCookie("keep", "example.com", "/reader", "2")),
        )

        manager.cookieStore.get(uri).map { it.name } shouldContainExactly listOf("keep")
    }

    @Test
    fun `scoped copyToCef for another host preserves existing host snapshots and normalizes host keys`() {
        val store = CookieManager(null, CookiePolicy.ACCEPT_ALL).cookieStore
        store.add(URI("https://A.Example/"), HttpCookie("a1", "1").apply { path = "/" })
        store.add(URI("https://b.example/"), HttpCookie("b1", "2").apply { path = "/" })
        store.add(URI("https://c.example/"), HttpCookie("c1", "3").apply { path = "/" })

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(store, cef) { it == "a.example" }

        CefCookieSync.copiedToCefSnapshot("A.Example").map { it.name } shouldContainExactly listOf("a1")
        CefCookieSync.copiedToCefSnapshot(".a.example").map { it.name } shouldContainExactly listOf("a1")
        CefCookieSync.copiedToCefSnapshot("b.example") shouldBe emptySet()
        CefCookieSync.copiedToCefSnapshot().map { it.name } shouldContainExactly listOf("a1")

        CefCookieSync.copyToCef(store, cef) { it == "b.example" }

        CefCookieSync.copiedToCefSnapshot("a.example").map { it.name } shouldContainExactly listOf("a1")
        CefCookieSync.copiedToCefSnapshot("b.example").map { it.name } shouldContainExactly listOf("b1")
        CefCookieSync.copiedToCefSnapshot("c.example") shouldBe emptySet()
        CefCookieSync.copiedToCefSnapshot().map { it.name } shouldContainExactlyInAnyOrder listOf("a1", "b1")
    }

    @Test
    fun `scoped copyToCef replaces only matched host buckets and clears emptied matched buckets`() {
        val store = CookieManager(null, CookiePolicy.ACCEPT_ALL).cookieStore
        val uriA = URI("https://a.example/")
        val uriB = URI("https://b.example/")
        val cookieB1 = HttpCookie("b1", "2").apply { path = "/" }
        store.add(uriA, HttpCookie("a1", "1").apply { path = "/" })
        store.add(uriB, cookieB1)

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(store, cef) { it == "a.example" }
        CefCookieSync.copyToCef(store, cef) { it == "b.example" }

        // Replace b1 with b2 in app store, then re-sync only b.example
        store.remove(uriB, cookieB1)
        val cookieB2 = HttpCookie("b2", "3").apply { path = "/" }
        store.add(uriB, cookieB2)
        CefCookieSync.copyToCef(store, cef) { it == "b.example" }

        CefCookieSync.copiedToCefSnapshot("a.example").map { it.name } shouldContainExactly listOf("a1")
        CefCookieSync.copiedToCefSnapshot("b.example").map { it.name } shouldContainExactly listOf("b2")

        // Remove all b.example cookies and re-sync b.example; a.example stays intact even if filter throws on it
        store.remove(uriB, cookieB2)
        CefCookieSync.copyToCef(store, cef) { host ->
            if (host == "a.example") error("filter exception must preserve bucket")
            host == "b.example"
        }

        CefCookieSync.copiedToCefSnapshot("a.example").map { it.name } shouldContainExactly listOf("a1")
        CefCookieSync.copiedToCefSnapshot("b.example") shouldBe emptySet()
    }

    @Test
    fun `removeStaleAppCookies removes host A stale cookies after a scoped copyToCef for host B`() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
        val uriA = URI("https://a.example/reader")
        val uriB = URI("https://b.example/reader")
        manager.cookieStore.add(uriA, HttpCookie("staleA", "1").apply { path = "/reader" })
        manager.cookieStore.add(uriA, HttpCookie("keepA", "2").apply { path = "/reader" })
        manager.cookieStore.add(uriB, HttpCookie("keepB", "3").apply { path = "/reader" })

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "a.example" }
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "b.example" }

        CefCookieSync.removeStaleAppCookies(
            url = "https://a.example/reader",
            inChromium = listOf(SyncedCookie("keepA", "a.example", "/reader", "2")),
        )

        manager.cookieStore.get(uriA).map { it.name } shouldContainExactly listOf("keepA")
        manager.cookieStore.get(uriB).map { it.name } shouldContainExactly listOf("keepB")
    }

    @Test
    fun `removeStaleAppCookies reconciles domain cookies on subdomains without leaking across lookalike hosts`() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
        val subUri = URI("https://sub.a.example/page")
        val lookalikeUri = URI("https://mya.example/page")
        manager.cookieStore.add(
            subUri,
            HttpCookie("dom", "1").apply {
                domain = ".a.example"
                path = "/"
            },
        )
        manager.cookieStore.add(subUri, HttpCookie("sub", "2").apply { path = "/" })
        manager.cookieStore.add(lookalikeUri, HttpCookie("other", "3").apply { path = "/" })

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "a.example" || it == "sub.a.example" }
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "mya.example" }

        CefCookieSync.removeStaleAppCookies(url = "https://sub.a.example/page", inChromium = emptyList())
        CefCookieSync.removeStaleAppCookies(url = "mailto:user@a.example", inChromium = emptyList())

        manager.cookieStore.get(subUri) shouldBe emptyList()
        manager.cookieStore.get(lookalikeUri).map { it.name } shouldContainExactly listOf("other")
        CefCookieSync.copiedToCefSnapshot("mya.example").map { it.name } shouldContainExactly listOf("other")
    }

    @Test
    fun `unscoped copyToCef reconciles all hosts cleanly`() {
        val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        CookieHandler.setDefault(manager)
        val uriA = URI("https://a.example/")
        val uriB = URI("https://b.example/")
        val uriC = URI("https://c.example/")
        val cookieA1 = HttpCookie("a1", "1").apply { path = "/" }
        manager.cookieStore.add(uriA, cookieA1)
        manager.cookieStore.add(uriB, HttpCookie("b1", "2").apply { path = "/" })

        val cef = mockk<CefCookieManager>(relaxed = true)
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "a.example" }
        CefCookieSync.copyToCef(manager.cookieStore, cef) { it == "b.example" }

        manager.cookieStore.remove(uriA, cookieA1)
        manager.cookieStore.add(uriC, HttpCookie("c1", "3").apply { path = "/" })
        CefCookieSync.copyToCef(manager.cookieStore, cef, includeHost = null)

        CefCookieSync.copiedToCefSnapshot("a.example") shouldBe emptySet()
        CefCookieSync.copiedToCefSnapshot("b.example").map { it.name } shouldContainExactly listOf("b1")
        CefCookieSync.copiedToCefSnapshot("c.example").map { it.name } shouldContainExactly listOf("c1")

        // A cookie added to a.example after the unscoped sync is not treated as stale; b1 is
        manager.cookieStore.add(uriA, HttpCookie("freshA", "4").apply { path = "/" })
        CefCookieSync.removeStaleAppCookies(url = "https://a.example/", inChromium = emptyList())
        CefCookieSync.removeStaleAppCookies(url = "https://b.example/", inChromium = emptyList())

        manager.cookieStore.get(uriA).map { it.name } shouldContainExactly listOf("freshA")
        manager.cookieStore.get(uriB) shouldBe emptyList()
    }

    @Test
    fun `CookieVisitEnd completes once and handles quiet and max timers`() {
        val scheduled = mutableListOf<Runnable>()
        val futures = mutableListOf<ScheduledFuture<*>>()
        val fakeScheduler = mockk<ScheduledExecutorService> {
            every { schedule(any<Runnable>(), any(), any()) } answers {
                scheduled += firstArg<Runnable>()
                mockk<ScheduledFuture<*>>(relaxed = true).also { futures += it }
            }
            every { schedule(any<Callable<*>>(), any(), any()) } throws UnsupportedOperationException()
        }

        val results = mutableListOf<Boolean>()
        val visit = CookieVisitEnd({ results += it }, fakeScheduler, 200L, 2_000L)
        visit.visited(0, 2)
        results shouldBe emptyList()
        visit.visited(1, 2)
        visit.finish(complete = false) // ignored after completion
        results shouldContainExactly listOf(true)
        futures.forEach { verify { it.cancel(false) } }

        // Quiet timer ends incomplete visit when nothing was visited
        scheduled.clear()
        val quietResults = mutableListOf<Boolean>()
        CookieVisitEnd({ quietResults += it }, fakeScheduler, 200L, 2_000L)
        scheduled[0].run()
        scheduled[1].run()
        quietResults shouldContainExactly listOf(false)

        // Non-HTTP copyFromCef calls onDone immediately without starting Chromium
        var called = 0
        CefCookieSync.copyFromCef("about:blank") { called++ }
        called shouldBe 1
    }
}
