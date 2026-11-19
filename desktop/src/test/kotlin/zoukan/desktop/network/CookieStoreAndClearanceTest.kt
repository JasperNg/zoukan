package zoukan.desktop.network

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.interceptor.ClearanceUserAgents
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.webkit.CefCookieDeletion
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import xyz.nulldev.androidcompat.webkit.PendingCefCookieDeletions
import xyz.nulldev.androidcompat.webkit.SyncedCookie
import xyz.nulldev.androidcompat.webkit.cookiesByUri
import xyz.nulldev.androidcompat.webkit.staleAppCookies
import zoukan.desktop.app.SelfTest
import zoukan.desktop.testing.Await
import java.io.File
import java.io.FileInputStream
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

/** Forces an immediate synchronous save on [PersistentCookieStore] for persistence tests. */
internal fun PersistentCookieStore.saveImmediately() {
    PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(this)
}

/**
 * Persistent cookie store expiry, `android.webkit.CookieManager` / `AndroidCookieJar` semantics,
 * close/self-test lifecycle, and Chromium-to-app cookie reconciliation.
 */
class CookieStoreAndSyncTest {
    @TempDir
    lateinit var root: File

    private lateinit var file: File
    private lateinit var store: PersistentCookieStore
    private var previous: CookieHandler? = null

    private val manager get() = CookieManager.getInstance()

    @BeforeEach
    fun install() {
        previous = CookieHandler.getDefault()
        file = File(root, "cookies.json")
        store = PersistentCookieStore(file)
        CookieHandler.setDefault(java.net.CookieManager(store, CookiePolicy.ACCEPT_ALL))
    }

    @AfterEach
    fun restore() {
        CookieHandler.setDefault(previous)
    }

    private fun httpCookie(name: String, value: String = "v", maxAge: Long = 3600) = HttpCookie(name, value).apply {
        this.maxAge = maxAge
        path = "/"
        version = 0
    }

    private fun syncedCookie(name: String, value: String, domain: String = "example.org", path: String = "/") =
        SyncedCookie(name, domain, path, value)

    /** The store's failed saves in a row, read under its lock as the store writes them. */
    private fun failedSaves(store: PersistentCookieStore): Int = synchronized(store) {
        PersistentCookieStore::class.java.getDeclaredField("failedSaves").apply { isAccessible = true }.getInt(store)
    }

    // --- Expiry & persistence ---

    @Test
    fun `cookies refreshed on every response do not grow the expiry map`() {
        val localStore = PersistentCookieStore(File(root, "expiry-cookies.json"))
        repeat(5_000) { localStore.add(URI("https://example.org/"), httpCookie("__cf_bm", value = "v$it")) }
        localStore.get(URI("https://example.org/")).size shouldBe 1
        localStore.trackedExpiryCount() shouldBeLessThan 600
    }

    @Test
    fun `a removed cookie's expiry is dropped at the next save`() {
        val localStore = PersistentCookieStore(File(root, "removed-expiry.json"))
        val c = httpCookie("session")
        localStore.add(URI("https://example.org/"), c)
        localStore.remove(URI("https://example.org/"), c)
        localStore.saveImmediately()
        localStore.trackedExpiryCount() shouldBe 0
    }

    @Test
    fun `the store reports the same absolute expiry each time it is asked`() {
        val localStore = PersistentCookieStore(File(root, "absolute-expiry.json"))
        val c = httpCookie("cf_clearance")
        val before = System.currentTimeMillis()
        localStore.add(URI("https://example.org/"), c)
        val expires = localStore.expiresAt(c)!!
        (expires >= before + 3_600_000) shouldBe true
        // A later "now": an expiry worked out again from the max age would differ
        val added = System.currentTimeMillis()
        Await.until("the clock to move on") { System.currentTimeMillis() > added }
        localStore.expiresAt(c) shouldBe expires
        // What a copy to Chromium uses: the known expiry, not the max age from now
        CefCookieSync.expiresAt(c, expires, now = expires) shouldBe expires
    }

    @Test
    fun `without a known expiry the max age counts from now, and session cookies have none`() {
        CefCookieSync.expiresAt(httpCookie("a", maxAge = 10), known = null, now = 1_000) shouldBe 11_000
        CefCookieSync.expiresAt(httpCookie("b", maxAge = -1), known = 5L, now = 1_000) shouldBe null
    }

    @Test
    fun `saving replaces an existing cookie file and leaves no temp file`() {
        val target = File(root, "replace-cookies.json")
        val localStore = PersistentCookieStore(target)
        localStore.add(URI("https://example.org/"), httpCookie("first"))
        localStore.saveImmediately()
        localStore.add(URI("https://example.org/"), httpCookie("second"))
        localStore.saveImmediately()

        File(target.path + ".tmp").exists() shouldBe false
        val names = PersistentCookieStore(target).get(URI("https://example.org/")).map { it.name }.toSet()
        names shouldBe setOf("first", "second")
        target.length() shouldNotBe 0L
    }

    @Test
    fun `a save that fails while another program has the file open is tried again`() {
        // Windows refuses to replace a file that a stream without delete sharing holds open, as a
        // virus scanner or a backup tool may; elsewhere the replace never fails this way
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val target = File(root, "retry-cookies.json")
        val localStore = PersistentCookieStore(target, saveDelayMillis = 50, retryDelayMillis = 100)
        localStore.add(URI("https://example.org/"), httpCookie("session", value = "kept"))
        localStore.saveImmediately()
        target.readText().contains("kept") shouldBe true

        FileInputStream(target).use {
            localStore.removeAll()
            // The scheduled save meets the open file
            Await.until("the scheduled save to fail on the open file") { failedSaves(localStore) > 0 }
            target.readText().contains("kept") shouldBe true
        }

        // A later try writes the cleared store
        Await.until("a later save to write the cleared store", 5_000) { !target.readText().contains("kept") }
        target.readText().contains("kept") shouldBe false
    }

    // --- CookieManager semantics ---

    @Test
    fun `AndroidCookieJar remove leaves no cookie and no empty one, also after a restart`() {
        val url = "https://example.org/".toHttpUrl()
        val jar = AndroidCookieJar()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.parse(url, "a=1; Max-Age=3600")!!,
                Cookie.parse(url, "b=2; Max-Age=3600; Domain=example.org")!!,
                Cookie.parse(url, "c=3; Max-Age=3600; Secure")!!,
            ),
        )
        jar.get(url).map { it.name }.toSet() shouldBe setOf("a", "b", "c")

        jar.remove(url) shouldBe 3

        jar.get(url) shouldBe emptyList()
        manager.getCookie(url.toString()) shouldBe null
        store.cookies shouldBe emptyList()
        store.saveImmediately()
        PersistentCookieStore(file).cookies shouldBe emptyList()
    }

    @Test
    fun `an expired Set-Cookie removes only the cookie it names, also when it is not the first`() {
        val url = "https://example.org/"
        manager.setCookie(url, "a=1; Path=/; Max-Age=3600")
        manager.setCookie(url, "b=2; Path=/; Max-Age=3600")
        manager.setCookie(url, "c=3; Path=/; Max-Age=3600")

        manager.setCookie(url, " b=;Max-Age=-1")
        manager.getCookie(url) shouldBe "a=1; c=3"

        // Expires in the past, as OkHttp writes a cookie a server deleted
        manager.setCookie(url, "c=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        manager.getCookie(url) shouldBe "a=1"
        store.cookies.map { it.name } shouldBe listOf("a")
    }

    @Test
    fun `the same cookie from OkHttp and from Chromium is kept once`() {
        val url = "https://example.org/".toHttpUrl()
        // OkHttp's Cookie.toString writes domain=example.org
        AndroidCookieJar().saveFromResponse(url, listOf(Cookie.parse(url, "cf_clearance=old; Max-Age=3600; Domain=example.org")!!))
        // CefCookieSync writes Chromium's domain, .example.org
        manager.setCookie(url.toString(), "cf_clearance=new; Domain=.example.org; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT")

        store.cookies.filter { it.name == "cf_clearance" }.map { it.value } shouldBe listOf("new")
        manager.getCookie(url.toString()) shouldBe "cf_clearance=new"
        manager.getCookie("https://www.example.org/") shouldBe "cf_clearance=new"
    }

    @Test
    fun `a domain cookie goes to its domain and the hosts under it, not to look-alike hosts`() {
        manager.setCookie("https://www.toon.com/", "s=1; Domain=toon.com; Path=/; Max-Age=3600")

        manager.getCookie("https://webtoon.com/") shouldBe null
        manager.getCookie("https://www.webtoon.com/") shouldBe null
        manager.getCookie("https://toon.com/") shouldBe "s=1"
        manager.getCookie("https://www.toon.com/") shouldBe "s=1"
        manager.getCookie("https://a.b.toon.com/") shouldBe "s=1"
    }

    @Test
    fun `host-only cookies work for IP hosts and localhost and stay on their host`() {
        manager.setCookie("http://127.0.0.1:8080/", "h=1; Path=/; Max-Age=3600")
        manager.setCookie("http://localhost:4567/", "l=1; Path=/; Max-Age=3600")

        manager.getCookie("http://127.0.0.1:8080/api") shouldBe "h=1"
        manager.getCookie("http://27.0.0.1/") shouldBe null
        manager.getCookie("http://localhost:4567/") shouldBe "l=1"
        manager.getCookie("http://a.localhost/") shouldBe null
    }

    @Test
    fun `a cookie without a Path gets the URL's directory, and replaces the same cookie with it`() {
        manager.setCookie("https://example.org/manga/1", "p=1; Max-Age=3600")
        store.cookies.single().path shouldBe "/manga"
        manager.setCookie("https://example.org/manga/2", "p=2; Path=/manga; Max-Age=3600")
        store.cookies.map { it.value } shouldBe listOf("2")

        manager.setCookie("https://example.org", "q=1; Max-Age=3600")
        store.cookies.single { it.name == "q" }.path shouldBe "/"
    }

    @Test
    fun `getCookie sends only cookies whose path matches, the longest path first`() {
        manager.setCookie("https://example.org/", "a=1; Path=/; Max-Age=3600")
        manager.setCookie("https://example.org/api/x", "a=2; Path=/api; Max-Age=3600")
        manager.setCookie("https://example.org/", "b=3; Path=/apis; Max-Age=3600")

        manager.getCookie("https://example.org/") shouldBe "a=1"
        manager.getCookie("https://example.org") shouldBe "a=1"
        manager.getCookie("https://example.org/api") shouldBe "a=2; a=1"
        manager.getCookie("https://example.org/api/list") shouldBe "a=2; a=1"
        manager.getCookie("https://example.org/apis/1") shouldBe "b=3; a=1"

        // A cookie saved without a path (before paths were defaulted) goes everywhere, as "/"
        store.add(URI("https://example.org/"), HttpCookie("old", "1").apply { maxAge = 3600 })
        manager.getCookie("https://example.org/api") shouldBe "a=2; a=1; old=1"
    }

    @Test
    fun `expiring a path-scoped cookie keeps the same name at another path`() {
        manager.setCookie("https://example.org/", "sess=root; Path=/; Max-Age=3600")
        manager.setCookie("https://example.org/api/", "sess=api; Path=/api; Max-Age=3600")

        manager.setCookie("https://example.org/api/", "sess=; Path=/api; Max-Age=0")
        manager.getCookie("https://example.org/api/") shouldBe "sess=root"
    }

    @Test
    fun `a Chromium deletion with a Path or Domain matches only those cookies`() {
        val broad = CefCookieDeletion("https://example.org/", "sess")
        broad.isExact shouldBe false

        val scoped = CefCookieDeletion("https://example.org/api/", "sess", path = "/api")
        scoped.isExact shouldBe true
        scoped.matches("example.org", "sess", "example.org", "/api") shouldBe true
        scoped.matches("example.org", "sess", "example.org", "/") shouldBe false
        scoped.matches("example.org", "sess", ".example.org", "/api") shouldBe true
        scoped.matches("example.org", "sess", "other.org", "/api") shouldBe false
        scoped.matches("example.org", "other", "example.org", "/api") shouldBe false

        val domain = CefCookieDeletion("https://www.example.org/", "sess", domain = "example.org")
        domain.matches("www.example.org", "sess", ".example.org", "/x") shouldBe true
        domain.matches("www.example.org", "sess", "www.example.org", "/") shouldBe false
    }

    @Test
    fun `Chromium deletions made before it runs wait, and a clear replaces those before it`() {
        val pending = PendingCefCookieDeletions()
        val named = CefCookieDeletion("https://example.org/", "a")
        pending.addUnless(named) { true } shouldBe true
        pending.drain() shouldBe emptyList()

        pending.addUnless(named) { false } shouldBe false
        pending.drain() shouldBe listOf(named)
        pending.drain() shouldBe emptyList()

        val all = CefCookieDeletion("", "")
        val after = CefCookieDeletion("https://example.org/", "b")
        pending.addUnless(named) { false }
        pending.addUnless(all) { false }
        pending.addUnless(after) { false }
        pending.drain() shouldBe listOf(all, after)
    }

    @Test
    fun `getCookie is null when there are no cookies, as on Android`() {
        manager.getCookie("https://example.org/") shouldBe null
        AndroidCookieJar().remove("https://example.org/".toHttpUrl()) shouldBe 0
    }

    @Test
    fun `a cookie file that cannot be read is kept aside before a save replaces it`() {
        val damaged = File(root, "damaged.json")
        val bytes = byteArrayOf(0x01, 0x02, 0x7f, 'x'.code.toByte())
        damaged.writeBytes(bytes)

        val fresh = PersistentCookieStore(damaged)
        val aside = File(root, "damaged.json.unreadable")
        aside.readBytes().toList() shouldBe bytes.toList()

        val cookie = HttpCookie("s", "v").apply {
            maxAge = 3600
            path = "/"
        }
        fresh.add(URI("https://example.org/"), cookie)
        fresh.saveImmediately()
        aside.readBytes().toList() shouldBe bytes.toList()
        PersistentCookieStore(damaged).cookies.map { it.name } shouldBe listOf("s")
    }

    // --- Store close & self-test lifecycle ---

    @Test
    fun `close writes the pending change`() {
        val target = File(root, "profile/cookies.json")
        val localStore = PersistentCookieStore(target, saveDelayMillis = 60_000)
        localStore.add(URI("https://example.org/"), httpCookie("session"))
        target.exists() shouldBe false
        localStore.close()
        target.isFile shouldBe true
        PersistentCookieStore(target).get(URI("https://example.org/")).map { it.name } shouldBe listOf("session")
    }

    @Test
    fun `nothing is written after close, so a deleted folder stays deleted`() {
        val profile = File(root, "profile")
        val localStore = PersistentCookieStore(File(profile, "cookies.json"), saveDelayMillis = 10)
        localStore.add(URI("https://example.org/"), httpCookie("a"))
        localStore.close()
        profile.deleteRecursively() shouldBe true
        localStore.add(URI("https://example.org/"), httpCookie("b"))
        // What the shutdown hook would run
        localStore.saveImmediately()
        profile.exists() shouldBe false
        // The store still works in memory
        localStore.get(URI("https://example.org/")).map { it.name }.sorted() shouldBe listOf("a", "b")
    }

    @Test
    fun `the self-test closes the installed store before deleting its profile`() {
        val profile = File(root, "zoukan-selftest").apply { mkdirs() }
        val localStore = PersistentCookieStore(File(profile, "cookies.json"), saveDelayMillis = 60_000)
        CookieHandler.setDefault(java.net.CookieManager(localStore, CookiePolicy.ACCEPT_ALL))
        localStore.add(URI("https://example.org/"), httpCookie("a"))
        SelfTest.deleteProfile(profile) shouldBe true
        localStore.saveImmediately()
        profile.exists() shouldBe false
    }

    // --- Chromium cookie sync reconciliation ---

    @Test
    fun `a cookie Chromium no longer has for the page is removed from the app`() {
        val session = syncedCookie("session", "abc")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(syncedCookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe listOf(session)
    }

    @Test
    fun `a cookie the app got after the copy is kept`() {
        val stale = staleAppCookies(
            appCookies = listOf(syncedCookie("fresh", "1")),
            copiedToCef = emptySet(),
            inChromium = listOf(syncedCookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie Chromium changed is not removed`() {
        val session = syncedCookie("session", "abc")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(syncedCookie("session", "def")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `domains compare without the leading dot and ignoring case`() {
        val session = syncedCookie("session", "abc", domain = "Example.org")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(syncedCookie("session", "abc", domain = ".example.ORG")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie for another path than the page is kept, as Chromium does not list it there`() {
        val account = syncedCookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(syncedCookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a path prefix that is not a whole segment does not match`() {
        val account = syncedCookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(syncedCookie("cf_clearance", "x")),
            pagePath = "/accounts",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie for the page's parent path that Chromium dropped is removed`() {
        val account = syncedCookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(syncedCookie("cf_clearance", "x")),
            pagePath = "/account/settings",
        )
        stale shouldBe listOf(account)
    }
}

/**
 * Security properties of cookie storage and `cf_clearance` user-agent binding:
 * `Secure` cookies stay out of plain HTTP and survive disk persistence, and `cf_clearance`
 * cookies remember which user agent solved them without storing cookie values in the clear.
 */
class CookieSecurityAndClearanceTest {
    @TempDir
    lateinit var root: File

    // --- Secure cookies ---

    private fun httpCookie(name: String, secure: Boolean) = HttpCookie(name, "$name-value").apply {
        maxAge = 3600
        path = "/"
        this.secure = secure
    }

    @Test
    fun `cookiesByUri includes Secure cookies`() {
        val store = java.net.CookieManager().cookieStore
        store.add(URI("https://example.org/"), httpCookie("plain", secure = false))
        store.add(URI("https://example.org/"), httpCookie("cf_clearance", secure = true))

        // What enumerating by the stored URIs alone gives
        store.urIs.flatMap { store.get(it) }.map { it.name } shouldBe listOf("plain")

        val found = store.cookiesByUri()
        found.map { it.second.name }.toSet() shouldBe setOf("plain", "cf_clearance")
        found.map { it.first.host }.toSet() shouldBe setOf("example.org")
    }

    @Test
    fun `a Secure cookie is saved and read back`() {
        val file = File(root, "cookies.json")
        val store = PersistentCookieStore(file)
        store.add(URI("https://example.org/"), httpCookie("cf_clearance", secure = true))
        store.saveImmediately()

        val reloaded = PersistentCookieStore(file)
        val cookie = reloaded.get(URI("https://example.org/")).single()
        cookie.name shouldBe "cf_clearance"
        cookie.value shouldBe "cf_clearance-value"
        cookie.secure shouldBe true
        // Still not sent over plain http
        reloaded.get(URI("http://example.org/")) shouldBe emptyList()
    }

    // --- Clearance user-agent binding ---

    private val extensionUa = "Mozilla/5.0 (Linux; Android 10; K) Mobile"
    private val chromiumUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/152.0.0.0"

    private class MapStore : ClearanceUserAgents.Store {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private fun clearance(value: String, domain: String = "example.org") =
        Cookie.Builder().name("cf_clearance").value(value).domain(domain).build()

    @Test
    fun `a clearance solved with the request's user agent keeps it`() {
        ClearanceUserAgents.overrideFor(extensionUa, extensionUa, chromiumUa) shouldBe null
    }

    @Test
    fun `a clearance solved with Chromium's user agent replaces the request's`() {
        ClearanceUserAgents.overrideFor(extensionUa, chromiumUa, chromiumUa) shouldBe chromiumUa
    }

    @Test
    fun `a clearance solved with another extension's user agent replaces the request's`() {
        ClearanceUserAgents.overrideFor(chromiumUa, extensionUa, chromiumUa) shouldBe extensionUa
    }

    @Test
    fun `a clearance of unknown origin is taken as solved with Chromium's user agent`() {
        ClearanceUserAgents.overrideFor(extensionUa, null, chromiumUa) shouldBe chromiumUa
        ClearanceUserAgents.overrideFor(chromiumUa, null, chromiumUa) shouldBe null
        // Without Chromium nothing is replaced
        ClearanceUserAgents.overrideFor(extensionUa, null, null) shouldBe null
    }

    @Test
    fun `challenges try the request's user agent first, then Chromium's`() {
        ClearanceUserAgents.solveOrder(extensionUa, chromiumUa) shouldBe listOf(extensionUa, chromiumUa)
        ClearanceUserAgents.solveOrder(chromiumUa, chromiumUa) shouldBe listOf(chromiumUa)
        ClearanceUserAgents.solveOrder(extensionUa, null) shouldBe listOf(extensionUa)
    }

    @Test
    fun `a clearance obtained in the browser window keeps the window's user agent`() {
        val agents = ClearanceUserAgents(MapStore())
        val cookie = clearance("solved-by-hand")
        agents.recordIfUnknown(cookie, extensionUa) // the window ran with the source's user agent
        ClearanceUserAgents.overrideFor(extensionUa, agents.userAgentOf(cookie), chromiumUa) shouldBe null
    }

    @Test
    fun `a browser window does not replace a known record`() {
        val agents = ClearanceUserAgents(MapStore())
        val cookie = clearance("solved-headless")
        agents.record(cookie, chromiumUa)
        agents.recordIfUnknown(cookie, extensionUa)
        agents.userAgentOf(cookie) shouldBe chromiumUa
    }

    @Test
    fun `a domain clearance recorded on www applies to api of the same domain`() {
        val agents = ClearanceUserAgents(MapStore())
        // As AndroidCookieJar.get parses it: "name=value" against the request URL, so domain = request host
        val onWww = Cookie.parse("https://www.example.org/".toHttpUrl(), "cf_clearance=shared")!!
        val onApi = Cookie.parse("https://api.example.org/".toHttpUrl(), "cf_clearance=shared")!!
        agents.record(onWww, extensionUa)
        agents.userAgentOf(onApi) shouldBe extensionUa
    }

    @Test
    fun `the recorded user agent belongs to the cookie it was recorded for`() {
        val store = MapStore()
        val records = ClearanceUserAgents(store)
        val cookie = clearance("first")
        records.userAgentOf(cookie) shouldBe null

        records.record(cookie, extensionUa)
        records.userAgentOf(cookie) shouldBe extensionUa
        // Survives a restart: a new instance over the same store
        ClearanceUserAgents(store).userAgentOf(cookie) shouldBe extensionUa

        // A new clearance (expired, cleared, solved again elsewhere) has no record yet
        records.userAgentOf(clearance("second")) shouldBe null
        // Nor does another domain's
        records.userAgentOf(clearance("first", domain = "other.example")) shouldBe null

        records.record(clearance("second"), chromiumUa)
        records.userAgentOf(clearance("second")) shouldBe chromiumUa
        records.userAgentOf(cookie) shouldBe null
        // The cookie's value is not stored in the clear
        store.map.values.any { "second" in it } shouldBe false
    }
}
