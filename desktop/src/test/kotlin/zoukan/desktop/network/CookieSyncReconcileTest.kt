package zoukan.desktop.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.SyncedCookie
import xyz.nulldev.androidcompat.webkit.staleAppCookies

/** After a complete visit, app cookies that Chromium had and dropped are removed from the app. */
class CookieSyncReconcileTest {
    private fun cookie(name: String, value: String, domain: String = "example.org") =
        SyncedCookie(name, domain, "/", value)

    @Test
    fun `a cookie Chromium no longer has for the page is removed from the app`() {
        val session = cookie("session", "abc")
        val stale = staleAppCookies(
            appCookies = listOf(session),
            copiedToCef = setOf(session),
            inChromium = listOf(cookie("cf_clearance", "x")),
        )
        stale shouldBe listOf(session)
    }

    @Test
    fun `a cookie the app got after the copy is kept`() {
        val stale = staleAppCookies(
            appCookies = listOf(cookie("fresh", "1")),
            copiedToCef = emptySet(),
            inChromium = listOf(cookie("cf_clearance", "x")),
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
        )
        stale shouldBe emptyList()
    }
}
