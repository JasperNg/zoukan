package xyz.nulldev.androidcompat.webkit

import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI

@Suppress("DEPRECATION")
class CookieManagerImpl : CookieManager() {
    // ZOUKAN -->
    // Read each time: the instance is a process-wide singleton, and the handler is installed at startup
    private val cookieHandler get() = CookieHandler.getDefault() as java.net.CookieManager
    // ZOUKAN <--
    private var acceptCookie = true
    private var acceptThirdPartyCookies = true
    private var allowFileSchemeCookies = false

    override fun setAcceptCookie(accept: Boolean) {
        acceptCookie = accept
    }

    override fun acceptCookie(): Boolean = acceptCookie

    override fun setAcceptThirdPartyCookies(
        webview: WebView?,
        accept: Boolean,
    ) {
        acceptThirdPartyCookies = accept
    }

    override fun acceptThirdPartyCookies(webview: WebView?): Boolean = acceptThirdPartyCookies

    override fun setCookie(
        url: String,
        value: String?,
    ) {
        val uri =
            if (url.startsWith("http")) {
                URI(url)
            } else {
                URI("http://$url")
            }

        // ZOUKAN -->
        // As Chromium's CookieManager: a Set-Cookie that has expired (Max-Age <= 0, or Expires in
        // the past) deletes the cookie instead of storing an empty one. java.net reads Max-Age=-1
        // as "session cookie" and matches a cookie by name, domain and path, so without this
        // AndroidCookieJar.remove (name=;Max-Age=-1, no domain or path) added a cookie and removed none.
        val expired = value?.let { hasExpiredAttribute(it) }
        val store = cookieHandler.cookieStore
        HttpCookie.parse(value).forEach {
            if (expired ?: (it.maxAge == 0L)) {
                removeMatching(store, uri, url, it)
            } else {
                // Chromium's default path, as OkHttp writes it, so the same cookie set with and
                // without a Path replaces itself instead of being kept twice
                if (it.path == null) it.path = defaultPath(uri)
                store.add(uri, it)
            }
        }
        // ZOUKAN <--
    }

    // ZOUKAN -->
    /**
     * Removes every cookie [uri] would be sent named as [expired], limited to its Domain and Path
     * when it has them, from the app and from Chromium.
     */
    private fun removeMatching(
        store: java.net.CookieStore,
        uri: URI,
        url: String,
        expired: HttpCookie,
    ) {
        val name = expired.name.trim()
        val domain = expired.domain?.trimStart('.')
        val secure = runCatching { URI("https", uri.host, null, null, null) }.getOrNull()
        (store.get(uri) + secure?.let { store.get(it) }.orEmpty())
            .distinct()
            .filter { it.name.equals(name, ignoreCase = true) }
            .filter { domain == null || it.domain?.trimStart('.').equals(domain, ignoreCase = true) }
            .filter { expired.path == null || it.path == expired.path }
            .forEach { store.remove(uri, it) }
        // Chromium would otherwise hand the cookie back after the next page load. Limited to the
        // same Domain and Path, so expiring a path-scoped cookie keeps the browser's other one
        CefCookieSync.deleteFromCef(if (url.startsWith("http")) url else "https://$url", name, domain, expired.path)
    }
    // ZOUKAN <--

    override fun setCookie(
        url: String,
        value: String?,
        callback: ValueCallback<Boolean>?,
    ) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    // ZOUKAN -->
    // Null when there are none, as on Android
    override fun getCookie(url: String): String? {
        val uri =
            if (url.startsWith("http")) {
                URI(url)
            } else {
                URI("http://$url")
            }
        // As Chromium, only cookies whose Path matches the URL's, the longest path first
        val requestPath = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        return cookieHandler.cookieStore
            .get(uri)
            .filter { pathMatches(it.path ?: "/", requestPath) }
            .sortedByDescending { (it.path ?: "/").length }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }
    }
    // ZOUKAN <--

    @Deprecated("Deprecated in Java")
    override fun removeSessionCookie() {}

    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {}

    @Deprecated("Deprecated in Java")
    override fun removeExpiredCookie() {}

    @Deprecated("Deprecated in Java")
    override fun removeAllCookie() {
        cookieHandler.cookieStore.removeAll()
    }

    override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        val removedCookies = cookieHandler.cookieStore.removeAll()
        // ZOUKAN -->
        // Android's WebView shares this store, so its cookies go too
        CefCookieSync.deleteFromCef("", "")
        // ZOUKAN <--
        callback?.onReceiveValue(removedCookies)
    }

    override fun hasCookies(): Boolean = cookieHandler.cookieStore.cookies.isNotEmpty()

    override fun flush() {}

    override fun allowFileSchemeCookiesImpl(): Boolean = allowFileSchemeCookies

    override fun setAcceptFileSchemeCookiesImpl(accept: Boolean) {
        allowFileSchemeCookies = acceptCookie
    }

    // ZOUKAN -->
    internal companion object {
        private val maxAgeAttribute = Regex("""(?i)(?:^|;)\s*max-age\s*=\s*([+-]?\d+)""")

        /**
         * True when [header] has a Max-Age of 0 or less, false for a positive one, null without one
         * (Max-Age wins over Expires, which java.net turns into a max age of 0 when it is past).
         */
        fun hasExpiredAttribute(header: String): Boolean? =
            maxAgeAttribute.find(header)?.groupValues?.get(1)?.toLongOrNull()?.let { it <= 0 }

        /** RFC 6265's path-match: [requestPath] is [cookiePath] or a path under it. */
        fun pathMatches(
            cookiePath: String,
            requestPath: String,
        ): Boolean =
            requestPath == cookiePath ||
                (
                    requestPath.startsWith(cookiePath) &&
                        (cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/')
                )

        /** RFC 6265's default-path: the URL path up to its last "/", or "/". */
        fun defaultPath(uri: URI): String {
            val path = uri.rawPath
            if (path.isNullOrEmpty() || !path.startsWith("/")) return "/"
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash == 0) "/" else path.substring(0, lastSlash)
        }
    }
    // ZOUKAN <--
}
