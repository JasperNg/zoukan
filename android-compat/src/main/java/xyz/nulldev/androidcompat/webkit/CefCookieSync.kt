// ZOUKAN -->
package xyz.nulldev.androidcompat.webkit

import android.webkit.CookieManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.net.CookieHandler
import java.net.HttpCookie
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Keeps Chromium's cookie store and the app's cookie store (java.net.CookieHandler, which
 * android.webkit.CookieManager and OkHttp read) in step.
 *
 * On Android the WebView and OkHttp share one cookie store. On desktop Chromium keeps its own, so a
 * cookie a page sets (Cloudflare's cf_clearance, a login session) is copied to the app after each
 * page load, and the app's cookies are copied to Chromium before a browser opens.
 */
object CefCookieSync {
    /** Copies every cookie the app has to Chromium. */
    fun copyToCef() {
        val store = (CookieHandler.getDefault() as? java.net.CookieManager)?.cookieStore ?: return
        val cef = CefCookieManager.getGlobalManager() ?: return
        store.urIs.forEach { uri ->
            store.get(uri).forEach { cookie ->
                runCatching {
                    // A cookie without a Domain attribute is host-only: Chromium keeps it so
                    // when its domain is empty, and would send it to every subdomain otherwise
                    val host = (cookie.domain ?: uri.host).trimStart('.')
                    cef.setCookie("https://$host", cookie.toCefCookie(cookie.domain.orEmpty()))
                }.onFailure { logger.warn(it) { "Copying cookie ${cookie.name} to Chromium failed" } }
            }
        }
    }

    /** Copies Chromium's cookies for [url] to the app, waiting at most [timeoutMs]. */
    fun copyFromCef(
        url: String,
        timeoutMs: Long = 2000,
    ) {
        if (!url.startsWith("http")) return
        val cef = CefCookieManager.getGlobalManager() ?: return
        val done = CountDownLatch(1)
        val cookies = mutableListOf<CefCookie>()
        val visiting =
            cef.visitUrlCookies(url, true) { cookie, count, total, _ ->
                synchronized(cookies) { cookies += cookie }
                if (count + 1 >= total) done.countDown()
                true
            }
        // The visitor is never called when there are no cookies, so this waits for the timeout only
        // when the visit could not start or is still running.
        if (visiting) done.await(timeoutMs, TimeUnit.MILLISECONDS)
        val manager = CookieManager.getInstance()
        synchronized(cookies) {
            cookies.forEach { manager.setCookie(url, it.toSetCookieHeader()) }
        }
    }

    private fun HttpCookie.toCefCookie(domain: String): CefCookie {
        val now = Date()
        val expires = if (maxAge >= 0) Date(System.currentTimeMillis() + maxAge * 1000) else null
        return CefCookie(name, value, domain, path ?: "/", secure, isHttpOnly, now, now, expires != null, expires)
    }

    private fun CefCookie.toSetCookieHeader(): String =
        buildString {
            append(name).append('=').append(value)
            if (!domain.isNullOrEmpty()) append("; Domain=").append(domain)
            append("; Path=").append(path ?: "/")
            if (hasExpires && expires != null) {
                append("; Expires=")
                append(
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.ofEpochMilli(expires.time).atOffset(ZoneOffset.UTC),
                    ),
                )
            }
            if (secure) append("; Secure")
            if (httponly) append("; HttpOnly")
        }
}
// ZOUKAN <--
