// ZOUKAN -->
package xyz.nulldev.androidcompat.webkit

import android.webkit.CookieManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.net.CookieHandler
import java.net.CookieStore
import java.net.HttpCookie
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Every cookie in the store with the URI it was stored under. The JDK store keys its URIs as
 * http://host and leaves Secure cookies out of a lookup by an http URI, so each one is also looked
 * up by https.
 */
fun CookieStore.cookiesByUri(): List<Pair<URI, HttpCookie>> =
    urIs.flatMap { uri ->
        val secure = runCatching { URI("https", uri.host, null, null, null) }.getOrNull()
        (get(uri) + secure?.let { get(it) }.orEmpty()).distinct().map { uri to it }
    }

/**
 * A cookie store that knows when each cookie expires. HttpCookie keeps its max age relative to when
 * it was created, which it does not expose, so a copy that added the max age to the current time
 * would extend the cookie's life each time.
 */
interface CookieExpirySource {
    /** When [cookie] expires, in epoch milliseconds, or null when the store does not know. */
    fun expiresAt(cookie: HttpCookie): Long?
}

/**
 * Calls [onDone] once a cookie visit ends: after its last cookie ([visited] with the last count), or
 * when no cookie came within [quietMillis] (Chromium does not call the visitor when there are none),
 * or at the latest after [maxMillis].
 */
class CookieVisitEnd(
    private val onDone: () -> Unit,
    scheduler: ScheduledExecutorService,
    quietMillis: Long,
    maxMillis: Long,
) {
    private val done = AtomicBoolean(false)

    @Volatile
    private var visited = false

    private val timers =
        listOf(
            scheduler.schedule({ if (!visited) finish() }, quietMillis, TimeUnit.MILLISECONDS),
            scheduler.schedule({ finish() }, maxMillis, TimeUnit.MILLISECONDS),
        )

    /** Chromium visited cookie [count] (from 0) of [total]. */
    fun visited(
        count: Int,
        total: Int,
    ) {
        visited = true
        if (count + 1 >= total) finish()
    }

    /** Ends the visit now; only the first call runs [onDone]. */
    fun finish() {
        if (!done.compareAndSet(false, true)) return
        runCatching { timers.forEach { it.cancel(false) } }
        runCatching { onDone() }.onFailure { logger.warn(it) { "Finishing a cookie copy failed" } }
    }
}

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
        val expiries = store as? CookieExpirySource
        store.cookiesByUri().forEach { (uri, cookie) ->
            runCatching {
                // A cookie without a Domain attribute is host-only: Chromium keeps it so
                // when its domain is empty, and would send it to every subdomain otherwise
                val host = (cookie.domain ?: uri.host).trimStart('.')
                val expiresAt = expiresAt(cookie, expiries?.expiresAt(cookie), System.currentTimeMillis())
                cef.setCookie("https://$host", cookie.toCefCookie(cookie.domain.orEmpty(), expiresAt))
            }.onFailure { logger.warn(it) { "Copying cookie ${cookie.name} to Chromium failed" } }
        }
    }

    /**
     * Copies Chromium's cookies for [url] to the app, then calls [onDone] (once, on another thread).
     * It returns at once: Chromium visits the cookies on its own thread and each one is applied as
     * it comes. (Chromium never calls the visitor when there are none, and on its UI thread it
     * cannot call it while this waits, so waiting for the visit froze the browser window and copied
     * nothing.) So the visit has ended after its last cookie, or when none came within a short time.
     */
    fun copyFromCef(
        url: String,
        onDone: () -> Unit = {},
    ) {
        val end = CookieVisitEnd(onDone, scheduler, VISIT_QUIET_MILLIS, VISIT_MAX_MILLIS)
        try {
            if (!url.startsWith("http")) return end.finish()
            val cef = CefCookieManager.getGlobalManager() ?: return end.finish()
            val manager = CookieManager.getInstance()
            val started =
                cef.visitUrlCookies(url, true) { cookie, count, total, _ ->
                    runCatching { manager.setCookie(url, cookie.toSetCookieHeader()) }
                        .onFailure { logger.warn(it) { "Copying cookie ${cookie.name} from Chromium failed" } }
                    end.visited(count, total)
                    true
                }
            if (!started) end.finish()
        } catch (e: Exception) {
            logger.warn(e) { "Copying cookies from Chromium failed" }
            end.finish()
        }
    }

    // No visitor call this long after the visit started: there were no cookies
    private const val VISIT_QUIET_MILLIS = 200L

    // A visit that stopped part way still ends
    private const val VISIT_MAX_MILLIS = 2_000L

    private val scheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CefCookieSync").apply { isDaemon = true }
        }
    }

    /**
     * When a cookie copied to Chromium expires: the store's own record when it has one, else the max
     * age counted from [now]. Null for a session cookie.
     */
    fun expiresAt(
        cookie: HttpCookie,
        known: Long?,
        now: Long,
    ): Long? =
        when {
            cookie.maxAge < 0 -> null
            known != null -> known
            else -> now + cookie.maxAge * 1000
        }

    private fun HttpCookie.toCefCookie(
        domain: String,
        expiresAt: Long?,
    ): CefCookie {
        val now = Date()
        val expires = expiresAt?.let { Date(it) }
        return CefCookie(name, value, domain, path ?: "/", secure, isHttpOnly, now, now, expires != null, expires)
    }

    private fun CefCookie.toSetCookieHeader(): String =
        buildString {
            append(name).append('=').append(value)
            // Chromium gives a host-only cookie its host as domain and a domain cookie a leading
            // dot; a Domain attribute would have the app send a host-only one to other hosts
            if (domain?.startsWith('.') == true) append("; Domain=").append(domain)
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
