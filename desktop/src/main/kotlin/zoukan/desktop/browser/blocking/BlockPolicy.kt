package zoukan.desktop.browser.blocking

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which requests the content blocker cancels. A request is blocked only when all of these hold:
 *
 * - it is for something inside a page (a script, an image, a frame), not the page itself;
 * - its host, or a parent domain, is on the block list and not on its exceptions;
 * - it goes to another site than the page in the top frame (a site's own hosts are never blocked);
 * - it is not one the checks and sign-ins need (see [isAlwaysAllowed]).
 *
 * Anything it cannot read (a missing page address, an odd URL) is let through.
 */
object BlockPolicy {
    /**
     * Hosts never blocked, whatever a list says: Cloudflare's, hCaptcha's and reCAPTCHA's checks,
     * and the sign-in pages of the trackers and of Google. Each covers its subdomains.
     */
    private val ALWAYS_ALLOWED = HostSet.of(
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        "www.google.com",
        "accounts.google.com",
        "www.gstatic.com",
        "anilist.co",
        "myanimelist.net",
        "shikimori.one",
        "bgm.tv",
        "bangumi.tv",
        "mangadex.org",
        "e-hentai.org",
        "exhentai.org",
    )

    /**
     * Whether to cancel a subresource or frame request to [requestUrl] on the page at [pageUrl]:
     * its host is in [hosts] and not in [exceptions] (the user's `@@||host^` rules).
     */
    fun shouldBlock(
        requestUrl: String?,
        pageUrl: String?,
        hosts: HostSet,
        exceptions: HostSet = HostSet.EMPTY,
    ): Boolean {
        if (hosts.size == 0) return false
        val request = requestUrl?.toHttpUrlOrNull() ?: return false
        if (!hosts.matches(request.host) || exceptions.matches(request.host) || isAlwaysAllowed(request)) return false
        val page = pageUrl?.toHttpUrlOrNull() ?: return false
        return !sameSite(request, page)
    }

    /** Whether [url] is one the content blocker always lets through (see [ALWAYS_ALLOWED]). */
    fun isAlwaysAllowed(url: HttpUrl): Boolean =
        url.encodedPath.startsWith("/cdn-cgi/") || ALWAYS_ALLOWED.matches(url.host)

    /** Whether [a] and [b] belong to the same site: the same registrable domain (or host). */
    fun sameSite(a: HttpUrl, b: HttpUrl): Boolean = site(a) == site(b)

    /** Whether [a] and [b] are web addresses of the same site; false when either is not one. */
    fun sameSite(a: String?, b: String?): Boolean {
        val first = a?.toHttpUrlOrNull() ?: return false
        val second = b?.toHttpUrlOrNull() ?: return false
        return sameSite(first, second)
    }

    private fun site(url: HttpUrl): String = url.topPrivateDomain() ?: url.host
}
