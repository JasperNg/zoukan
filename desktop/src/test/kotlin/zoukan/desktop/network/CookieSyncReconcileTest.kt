package zoukan.desktop.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.SyncedCookie
import xyz.nulldev.androidcompat.webkit.staleAppCookies

/** After a complete visit, app cookies that Chromium had and dropped are removed from the app. */
class CookieSyncReconcileTest {
    private fun cookie(name: String, value: String, domain: String = "example.org", path: String = "/") =
        SyncedCookie(name, domain, path, value)

    @Test
    fun `a cookie Chromium no longer has for the page is removed from the app`() {
        val session = cookie("session", "abc")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(cookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe listOf(session)
    }

    @Test
    fun `a cookie the app got after the copy is kept`() {
        val stale = staleAppCookies(
            appCookies = listOf(cookie("fresh", "1")),
            copiedToCef = emptySet(),
            inChromium = listOf(cookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie Chromium changed is not removed`() {
        val session = cookie("session", "abc")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(cookie("session", "def")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `domains compare without the leading dot and ignoring case`() {
        val session = cookie("session", "abc", domain = "Example.org")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(cookie("session", "abc", domain = ".example.ORG")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie for another path than the page is kept, as Chromium does not list it there`() {
        val account = cookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(cookie("cf_clearance", "x")),
            pagePath = "/",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a path prefix that is not a whole segment does not match`() {
        val account = cookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(cookie("cf_clearance", "x")),
            pagePath = "/accounts",
        )
        stale shouldBe emptyList()
    }

    @Test
    fun `a cookie for the page's parent path that Chromium dropped is removed`() {
        val account = cookie("session", "abc", path = "/account")
        val stale = staleAppCookies(
            appCookies = listOf(account),
            copiedToCef = setOf(account),
            inChromium = listOf(cookie("cf_clearance", "x")),
            pagePath = "/account/settings",
        )
        stale shouldBe listOf(account)
    }
}
