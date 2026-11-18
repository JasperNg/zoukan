package zoukan.desktop.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which cookies a browsing or challenge window exchanges with the app's cookie store. Kept free of
 * Chromium types, so tests can drive it without JCEF. Thread-safe: Chromium's threads call it.
 *
 * - To Chromium, when the window opens, go only the app's cookies for the start address's site
 *   (registrable domain, subdomains included). Cookies Chromium already keeps are never removed.
 * - From Chromium, after a page loads and when the window closes, come only the cookies of a page
 *   on a trusted site: the start site, a site the user went to (typed address, a link clicked, a
 *   pop-up opened from the bar) and a site a trusted page redirected to (a source's new domain).
 *   A page a script sent the window to without the user (an ad's redirect) is not trusted.
 *
 * A sign-in window has no scope (it keeps the earlier behaviour: every cookie both ways), since a
 * sign-in may pass through other sites' pages that set the cookies it needs.
 */
class BrowserCookieScope(startUrl: String) {
    private val startSite = site(startUrl)
    private val trusted = HashSet<String>().apply { startSite?.let(::add) }

    // Whether the navigation the next redirect belongs to was trusted
    private var lastNavigationTrusted = true

    /** Whether the app's cookie for [host] (a cookie's domain without its dot) goes to Chromium. */
    fun copiesToChromium(host: String): Boolean {
        val start = startSite ?: return false
        val cookieSite = site("https://${host.trimStart('.')}/") ?: return false
        return cookieSite == start
    }

    /** The user chose to go to [url] (typed it, or opened a pop-up from the bar). */
    @Synchronized
    fun userOpened(url: String) {
        site(url)?.let(trusted::add)
    }

    /**
     * The top frame is about to go to [url]. [userGesture] is Chromium's word that the user started
     * it; [isRedirect] that a server redirected the navigation before it.
     */
    @Synchronized
    fun navigating(url: String, userGesture: Boolean, isRedirect: Boolean) {
        val target = site(url) ?: return
        val ok = target in trusted || userGesture || (isRedirect && lastNavigationTrusted)
        if (ok) trusted += target
        lastNavigationTrusted = ok
    }

    /** Whether the cookies of the page at [url] are copied to the app. */
    @Synchronized
    fun copiesFromChromium(url: String?): Boolean {
        val target = url?.let(::site) ?: return false
        return target in trusted
    }

    companion object {
        /** The registrable domain of [url] (its host when it has none, as for an IP address), or null. */
        internal fun site(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            return (parsed.topPrivateDomain() ?: parsed.host).lowercase()
        }
    }
}
