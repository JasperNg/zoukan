package zoukan.desktop.network

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.AndroidCookieJar
import io.kotest.matchers.shouldBe
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.CookieHandler
import java.net.CookiePolicy
import java.net.URI

/**
 * android.webkit.CookieManager on desktop should behave as Chromium's on Android, which Komikku's
 * AndroidCookieJar and CloudflareInterceptor rely on. The store is installed as the app does it
 * (ZoukanRuntime): a java.net.CookieManager with the PersistentCookieStore as CookieHandler.
 */
class CookieManagerSemanticsTest {
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

    private fun save(store: PersistentCookieStore) {
        PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(store)
    }

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
        save(store)
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

        val cookie = java.net.HttpCookie("s", "v").apply {
            maxAge = 3600
            path = "/"
        }
        fresh.add(URI("https://example.org/"), cookie)
        save(fresh)
        aside.readBytes().toList() shouldBe bytes.toList()
        PersistentCookieStore(damaged).cookies.map { it.name } shouldBe listOf("s")
    }
}
