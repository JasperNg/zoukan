// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.AndroidCookieJar
import logcat.LogPriority
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.AsyncTimeout
import org.jsoup.Jsoup
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Solves Cloudflare challenges on desktop, like CloudflareInterceptor on Android.
 *
 * First a hidden WebView (Chromium through the Android compatibility layer) loads the page and
 * waits for Cloudflare's cf_clearance cookie. When that fails, for example on an interactive
 * Turnstile challenge, the page opens in a browser window so the user can solve it.
 *
 * The class keeps the Android name because Keiyoushi extensions look up the default client's
 * interceptors by simple name ("CloudflareInterceptor must be present in default client").
 */
class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
) : Interceptor {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Challenges are solved in Chromium, and Cloudflare only accepts the cf_clearance cookie with
        // the user agent that solved it. The cookie store keeps cf_clearance across restarts, so a
        // host that has one gets Chromium's user agent, in this run or a later one.
        val request = chain.request().let { original ->
            val browserUserAgent = DesktopBrowser.userAgent
            if (browserUserAgent != null && hasClearance(original)) {
                original.newBuilder().header("User-Agent", browserUserAgent).build()
            } else {
                original
            }
        }
        val sentClearance = clearance(request)
        val response = chain.proceed(request)
        if (!isChallenge(response)) return response

        try {
            response.close()
            // Parallel requests to one host that all hit the challenge solve it once: the others
            // wait here, then retry with the clearance the first one got, or fail with it
            val host = request.url.host
            val waitStarted = System.nanoTime()
            val solved = hostLocks.withLock(host) {
                val current = clearance(request)
                if (current != null && current != sentClearance) return@withLock true
                // An attempt that ended while this one waited failed (the user closed its window,
                // say): failing too, not showing the challenge again for each queued request
                val failedAt = lastFailure[host]
                if (failedAt != null && failedAt - waitStarted > 0) return@withLock false
                cookieManager.remove(request.url, COOKIE_NAMES, 0)
                val oldCookie = clearance(request)
                // Cloudflare rejects a user agent that does not match the browser (for example the
                // default Android one on desktop Chromium), so the challenge runs with Chromium's own
                val userAgent = DesktopBrowser.userAgent ?: request.header("User-Agent") ?: defaultUserAgentProvider()
                solveHeadless(request, userAgent, oldCookie) || run {
                    // The user may take minutes; the call timeout (2 min) would end the call first
                    (chain.call().timeout() as? AsyncTimeout)?.exit()
                    DesktopBrowser.solveChallenge(request.url.toString(), userAgent) { isSolved(request, oldCookie) }
                }.also { if (it) lastFailure.remove(host) else lastFailure[host] = System.nanoTime() }
            }
            if (!solved) throw IOException("Failed to bypass Cloudflare on ${request.url.host}")
            val browserUserAgent = DesktopBrowser.userAgent ?: return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("User-Agent", browserUserAgent).build())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // OkHttp's enqueue only handles IOExceptions
            throw IOException(e)
        }
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) return false
        val document = Jsoup.parse(response.peekBody(Long.MAX_VALUE).string(), response.request.url.toString())
        // Solve only captchas, not geo blocks
        return document.getElementById("challenge-error-title") != null ||
            document.getElementById("challenge-error-text") != null
    }

    private fun hasClearance(request: Request): Boolean = clearance(request) != null

    private fun clearance(request: Request): Cookie? =
        cookieManager.get(request.url).firstOrNull { it.name == CF_CLEARANCE }

    private fun isSolved(request: Request, oldCookie: Cookie?): Boolean =
        cookieManager.get(request.url).firstOrNull { it.name == CF_CLEARANCE }.let { it != null && it != oldCookie }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solveHeadless(request: Request, userAgent: String, oldCookie: Cookie?): Boolean {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        handler.post {
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = userAgent
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // The page reloads once the challenge is solved and sets cf_clearance
                            if (isSolved(request, oldCookie)) latch.countDown()
                        }
                    }
                    loadUrl(request.url.toString(), safeHeaders(request))
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Cloudflare: the WebView could not start" }
                latch.countDown()
            }
        }
        // Cookies also arrive between page loads; check them while waiting
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HEADLESS_TIMEOUT_SECONDS)
        while (!latch.await(500, TimeUnit.MILLISECONDS)) {
            if (isSolved(request, oldCookie) || System.nanoTime() > deadline) break
        }
        handler.post {
            webView?.run {
                stopLoading()
                destroy()
            }
        }
        return isSolved(request, oldCookie)
    }

    private fun safeHeaders(request: Request): Map<String, String> = request.headers
        .filter { (name, value) -> isRequestHeaderSafe(name, value) }
        .groupBy(keySelector = { (name, _) -> name }) { (_, value) -> value }
        .mapValues { it.value.getOrNull(0).orEmpty() }

    // Based on IsRequestHeaderSafe in Chromium's services/network/public/cpp/header_util.cc
    private fun isRequestHeaderSafe(name: String, value: String): Boolean {
        val lowerName = name.lowercase(Locale.ENGLISH)
        if (lowerName in UNSAFE_HEADER_NAMES || lowerName.startsWith("proxy-")) return false
        return !(lowerName == "connection" && value.lowercase(Locale.ENGLISH) == "upgrade")
    }

    private companion object {
        val hostLocks = HostLocks()

        // When the last attempt to solve each host's challenge failed; a success removes it
        val lastFailure = ConcurrentHashMap<String, Long>()
        const val CF_CLEARANCE = "cf_clearance"
        const val HEADLESS_TIMEOUT_SECONDS = 30L
        val ERROR_CODES = listOf(403, 503)
        val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        val COOKIE_NAMES = listOf(CF_CLEARANCE)
        val UNSAFE_HEADER_NAMES = listOf(
            "content-length", "host", "trailer", "te", "upgrade", "cookie2", "keep-alive", "transfer-encoding",
            "set-cookie",
        )
    }
}

/**
 * The browser window of the desktop app, set by the app at start. Shared code that needs the user
 * to see a page (a Cloudflare challenge, a site login) goes through here.
 */
object DesktopBrowser {
    fun interface ChallengeSolver {
        /**
         * Shows [url] in a browser window with [userAgent] until [isSolved] returns true, the user
         * closes the window, or a timeout. Returns whether it was solved.
         */
        fun solve(url: String, userAgent: String, isSolved: () -> Boolean): Boolean
    }

    @Volatile
    var challengeSolver: ChallengeSolver? = null

    /**
     * The user agent of the app's Chromium, set by the app at start. Challenges are solved with it,
     * and requests to hosts that hold a cf_clearance cookie use it.
     */
    @Volatile
    var userAgent: String? = null

    private val hostLocks = HostLocks()

    /**
     * Shows one challenge window per host at a time. A caller that waited for another one's
     * window checks [isSolved] again first and opens none when it is already solved.
     */
    fun solveChallenge(url: String, userAgent: String, isSolved: () -> Boolean): Boolean {
        val solver = challengeSolver ?: return false
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return hostLocks.withLock(host) { isSolved() || solver.solve(url, userAgent, isSolved) }
    }
}

/**
 * One lock per host. A lock is dropped once nobody holds or waits for it, so the map holds only
 * hosts in use.
 */
class HostLocks {
    private class Entry {
        val lock = ReentrantLock()
        var users = 0 // guarded by the map's compute
    }

    private val locks = ConcurrentHashMap<String, Entry>()

    fun <T> withLock(host: String, block: () -> T): T {
        val key = host.lowercase(Locale.ENGLISH)
        val entry = locks.compute(key) { _, existing -> (existing ?: Entry()).also { it.users++ } }!!
        try {
            entry.lock.lockInterruptibly()
            try {
                return block()
            } finally {
                entry.lock.unlock()
            }
        } finally {
            locks.compute(key) { _, existing -> existing?.takeIf { --it.users > 0 } }
        }
    }
}
// ZOUKAN <--
