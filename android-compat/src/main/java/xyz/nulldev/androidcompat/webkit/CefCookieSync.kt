// ZOUKAN -->
package xyz.nulldev.androidcompat.webkit

import android.webkit.CookieManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.CefApp
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
 * or at the latest after [maxMillis]. [onDone] is told whether the visit was complete: only one that
 * reached its last cookie is. One that ended by a timer may have missed cookies, or Chromium may
 * have had none, so nothing may be concluded from the cookies it returned.
 */
class CookieVisitEnd(
    private val onDone: (complete: Boolean) -> Unit,
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
        if (count + 1 >= total) finish(complete = true)
    }

    /** Ends the visit now; only the first call runs [onDone]. */
    fun finish(complete: Boolean = false) {
        if (!done.compareAndSet(false, true)) return
        runCatching { timers.forEach { it.cancel(false) } }
        runCatching { onDone(complete) }.onFailure { logger.warn(it) { "Finishing a cookie copy failed" } }
    }
}

/** A cookie as both stores identify it. */
data class SyncedCookie(
    val name: String,
    val domain: String,
    val path: String,
    val value: String,
)

/**
 * The app's cookies for a page to delete after a complete visit: copied to Chromium, and gone from
 * it. A cookie the app got after the copy was never in Chromium and stays; one Chromium has with
 * another value was changed there, and copying from Chromium updates it. Only cookies sent to
 * [pagePath] count, as Chromium lists only those for the page.
 */
fun staleAppCookies(
    appCookies: List<SyncedCookie>,
    copiedToCef: Set<SyncedCookie>,
    inChromium: List<SyncedCookie>,
    pagePath: String,
): List<SyncedCookie> {
    fun SyncedCookie.key() = Triple(name, domain.trimStart('.').lowercase(), path)

    fun SyncedCookie.exact() = copy(domain = domain.trimStart('.').lowercase())
    val copied = copiedToCef.mapTo(HashSet()) { it.exact() }
    val present = inChromium.mapTo(HashSet()) { it.key() }
    // The app's store matches a page by domain only; Chromium lists only the cookies whose path
    // matches the page, so a cookie for another path is not missing from it
    return appCookies.filter { pathMatches(pagePath, it.path) && it.exact() in copied && it.key() !in present }
}

/** Whether a cookie for [cookiePath] is sent to [pagePath] (RFC 6265 5.1.4, as Chromium does). */
internal fun pathMatches(pagePath: String, cookiePath: String): Boolean {
    val page = pagePath.ifEmpty { "/" }
    if (page == cookiePath) return true
    return page.startsWith(cookiePath) && (cookiePath.endsWith('/') || page[cookiePath.length] == '/')
}

/**
 * A cookie the app deleted, to delete from Chromium: named [name] for [url]'s host (every cookie
 * when both are empty), limited to [domain] and [path] when the expired Set-Cookie had them.
 */
data class CefCookieDeletion(
    val url: String,
    val name: String,
    val domain: String? = null,
    val path: String? = null,
) {
    /** Only cookies with the given Domain and Path go, not every cookie of that name. */
    val isExact get() = domain != null || path != null

    /** Whether Chromium's cookie ([cookieDomain] has a leading dot for a domain cookie) goes. */
    fun matches(
        host: String,
        cookieName: String,
        cookieDomain: String?,
        cookiePath: String?,
    ): Boolean {
        if (!cookieName.equals(name, ignoreCase = true)) return false
        val bare = cookieDomain.orEmpty().trimStart('.')
        val domainOk =
            if (domain != null) {
                bare.equals(domain.trimStart('.'), ignoreCase = true)
            } else {
                // The cookies the URL would be sent, as the app's store matches them
                host.equals(bare, ignoreCase = true) || host.endsWith(".$bare", ignoreCase = true)
            }
        return domainOk && (path == null || (cookiePath ?: "/") == path)
    }
}

/**
 * Deletions made while Chromium was not running. A deletion of every cookie replaces those before
 * it.
 */
class PendingCefCookieDeletions {
    private val deletions = mutableListOf<CefCookieDeletion>()

    /**
     * Keeps [deletion] for later unless [now] (checked under the same lock as [drain]) says to make
     * it at once. Returns true when it should be made now.
     */
    @Synchronized
    fun addUnless(
        deletion: CefCookieDeletion,
        now: () -> Boolean,
    ): Boolean {
        if (now()) return true
        if (deletion.url.isEmpty() && deletion.name.isEmpty() && !deletion.isExact) deletions.clear()
        deletions += deletion
        return false
    }

    /** Every waiting deletion, in order; none wait after. */
    @Synchronized
    fun drain(): List<CefCookieDeletion> = deletions.toList().also { deletions.clear() }
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
    /**
     * Copies the app's cookies to Chromium: every one, or with [includeHost] only those whose host
     * (the Domain attribute without its dot, else the host they were stored for) it accepts. A
     * cookie Chromium already has is never removed from it.
     */
    fun copyToCef(includeHost: ((String) -> Boolean)? = null) {
        val store = (CookieHandler.getDefault() as? java.net.CookieManager)?.cookieStore ?: return
        val cef = CefCookieManager.getGlobalManager() ?: return
        copyToCef(store, cef, includeHost)
    }

    internal fun copyToCef(
        store: CookieStore,
        cef: CefCookieManager,
        includeHost: ((String) -> Boolean)? = null,
    ) {
        // Before the app's cookies: those set again since are copied back after their deletion
        applyPendingDeletions(cef)
        val expiries = store as? CookieExpirySource
        val copied = HashSet<SyncedCookie>()
        store.cookiesByUri().forEach { (uri, cookie) ->
            runCatching {
                // A cookie without a Domain attribute is host-only: Chromium keeps it so
                // when its domain is empty, and would send it to every subdomain otherwise
                val host = (cookie.domain ?: uri.host).trimStart('.')
                // A filter that fails leaves the cookie out: it is only not copied
                if (includeHost != null && !runCatching { includeHost(host) }.getOrDefault(false)) return@runCatching
                val expiresAt = expiresAt(cookie, expiries?.expiresAt(cookie), System.currentTimeMillis())
                cef.setCookie("https://$host", cookie.toCefCookie(cookie.domain.orEmpty(), expiresAt))
                copied += cookie.toSynced(uri)
            }.onFailure { logger.warn(it) { "Copying cookie ${cookie.name} to Chromium failed" } }
        }
        synchronized(copiedToCef) {
            copiedToCef.clear()
            copiedToCef.addAll(copied)
        }
    }

    /** What the last [copyToCef] put in Chromium. */
    private val copiedToCef = HashSet<SyncedCookie>()

    internal fun copiedToCefSnapshot(): Set<SyncedCookie> = synchronized(copiedToCef) { copiedToCef.toSet() }

    private fun HttpCookie.toSynced(uri: URI) =
        SyncedCookie(name, (domain ?: uri.host).orEmpty().trimStart('.'), path ?: "/", value)

    /**
     * After a complete visit of [url], removes the app's cookies for it that were copied to Chromium
     * and that Chromium no longer has: the user signed out there. Logs the count only.
     */
    internal fun removeStaleAppCookies(
        url: String,
        inChromium: List<SyncedCookie>,
    ) {
        val store = (CookieHandler.getDefault() as? java.net.CookieManager)?.cookieStore ?: return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val app = store.get(uri).associateBy { it.toSynced(uri) }
        val copied = synchronized(copiedToCef) { copiedToCef.toSet() }
        val stale = staleAppCookies(app.keys.toList(), copied, inChromium, uri.rawPath.orEmpty())
        stale.forEach { app[it]?.let { cookie -> store.remove(uri, cookie) } }
        if (stale.isNotEmpty()) logger.info { "Removed ${stale.size} cookies the built-in browser no longer has" }
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
        val seen = java.util.Collections.synchronizedList(mutableListOf<SyncedCookie>())
        val end =
            CookieVisitEnd(
                { complete ->
                    // Only a visit that reached its last cookie shows what Chromium has
                    if (complete) {
                        runCatching { removeStaleAppCookies(url, seen.toList()) }
                            .onFailure { logger.warn(it) { "Removing cookies Chromium dropped failed" } }
                    }
                    onDone()
                },
                scheduler,
                VISIT_QUIET_MILLIS,
                VISIT_MAX_MILLIS,
            )
        try {
            if (!url.startsWith("http")) return end.finish()
            val cef = CefCookieManager.getGlobalManager() ?: return end.finish()
            applyPendingDeletions(cef)
            val manager = CookieManager.getInstance()
            val started =
                cef.visitUrlCookies(url, true) { cookie, count, total, _ ->
                    runCatching { manager.setCookie(url, cookie.toSetCookieHeader()) }
                        .onFailure { logger.warn(it) { "Copying cookie ${cookie.name} from Chromium failed" } }
                    seen += SyncedCookie(cookie.name, cookie.domain.orEmpty(), cookie.path ?: "/", cookie.value)
                    end.visited(count, total)
                    true
                }
            if (!started) end.finish()
        } catch (e: Exception) {
            logger.warn(e) { "Copying cookies from Chromium failed" }
            end.finish()
        }
    }

    /**
     * Deletes Chromium's cookies named [name] for [url]'s host (every cookie when both are empty),
     * so a cookie the app deleted is not copied back after the next page load. With a [domain] or
     * [path] (the expired Set-Cookie's attributes) only the cookies with that Domain and Path go.
     *
     * Getting Chromium's cookie manager before it runs would start it, so until then the deletion
     * waits in [pending] and is made when Chromium starts ([copyToCef]): Chromium keeps its
     * cookies on disk, and would otherwise hand the deleted cookie back.
     */
    fun deleteFromCef(
        url: String,
        name: String,
        domain: String? = null,
        path: String? = null,
    ) {
        val deletion = CefCookieDeletion(url, name, domain, path)
        runCatching {
            if (!pending.addUnless(deletion) { CefApp.getState() == CefApp.CefAppState.INITIALIZED }) return
            CefCookieManager.getGlobalManager()?.let { delete(it, deletion) }
        }.onFailure { logger.warn(it) { "Deleting cookie $name from Chromium failed" } }
    }

    /** Deletions made before Chromium ran, made when it starts. */
    internal val pending = PendingCefCookieDeletions()

    /**
     * Called by an extension's WebView with its page URL and user agent once Chromium's cookies for
     * it are copied to the app; the app uses it to remember which user agent a clearance was got with.
     */
    @Volatile
    var onCopiedFromCef: ((url: String, userAgent: String) -> Unit)? = null

    private fun applyPendingDeletions(cef: CefCookieManager) {
        pending.drain().forEach { deletion ->
            runCatching { delete(cef, deletion) }
                .onFailure { logger.warn(it) { "Deleting cookie ${deletion.name} from Chromium failed" } }
        }
    }

    private fun delete(
        cef: CefCookieManager,
        deletion: CefCookieDeletion,
    ) {
        if (!deletion.isExact) {
            cef.deleteCookies(deletion.url, deletion.name)
            return
        }
        // deleteCookies matches a host and name only; the visitor can delete one cookie
        val host = runCatching { URI(deletion.url).host }.getOrNull() ?: return
        cef.visitAllCookies { cookie, _, _, delete ->
            if (deletion.matches(host, cookie.name, cookie.domain, cookie.path)) delete.set(true)
            true
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

    internal fun HttpCookie.toCefCookie(
        domain: String,
        expiresAt: Long?,
    ): CefCookie {
        val now = Date()
        val expires = expiresAt?.let { Date(it) }
        return CefCookie(name, value, domain, path ?: "/", secure, isHttpOnly, now, now, expires != null, expires)
    }

    internal fun CefCookie.toSetCookieHeader(): String =
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
