package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The RFC 6265 rules CookieManagerImpl applies (Zoukan's changes): path-match, default-path and
 * whether a Set-Cookie header deletes the cookie. The desktop's CookieManagerSemanticsTest checks
 * them through the cookie store; these pin the edge cases of the rules themselves.
 */
class CookieRulesTest {
    @Test
    fun `a cookie path matches itself and the paths under it, not a look-alike prefix`() {
        CookieManagerImpl.pathMatches("/", "/") shouldBe true
        CookieManagerImpl.pathMatches("/", "/anything/at/all") shouldBe true
        CookieManagerImpl.pathMatches("/docs", "/docs") shouldBe true
        CookieManagerImpl.pathMatches("/docs", "/docs/page") shouldBe true
        CookieManagerImpl.pathMatches("/docs/", "/docs/page") shouldBe true
        // A prefix that is not a whole path segment is another path
        CookieManagerImpl.pathMatches("/docs", "/docsearch") shouldBe false
        CookieManagerImpl.pathMatches("/docs/", "/docs") shouldBe false
        CookieManagerImpl.pathMatches("/docs/page", "/docs") shouldBe false
    }

    @Test
    fun `the default path is the directory of the URL path`() {
        CookieManagerImpl.defaultPath(URI("https://example.org")) shouldBe "/"
        CookieManagerImpl.defaultPath(URI("https://example.org/")) shouldBe "/"
        CookieManagerImpl.defaultPath(URI("https://example.org/page")) shouldBe "/"
        CookieManagerImpl.defaultPath(URI("https://example.org/a/b")) shouldBe "/a"
        CookieManagerImpl.defaultPath(URI("https://example.org/a/b/")) shouldBe "/a/b"
        CookieManagerImpl.defaultPath(URI("https://example.org/a/b?x=/c/d")) shouldBe "/a"
    }

    @Test
    fun `only a Max-Age of zero or less deletes, and without one the header does not decide`() {
        CookieManagerImpl.hasExpiredAttribute("a=1; Max-Age=0") shouldBe true
        CookieManagerImpl.hasExpiredAttribute("a=1; Max-Age=-1") shouldBe true
        CookieManagerImpl.hasExpiredAttribute("a=1; max-age = -5; Path=/") shouldBe true
        CookieManagerImpl.hasExpiredAttribute("a=1; Max-Age=3600") shouldBe false
        CookieManagerImpl.hasExpiredAttribute("a=1; Max-Age=+10") shouldBe false
        // No Max-Age: java.net decides from Expires
        CookieManagerImpl.hasExpiredAttribute("a=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT") shouldBe null
        CookieManagerImpl.hasExpiredAttribute("a=1") shouldBe null
        // A value that only looks like the attribute is not one
        CookieManagerImpl.hasExpiredAttribute("note=max-age=0") shouldBe null
    }
}
