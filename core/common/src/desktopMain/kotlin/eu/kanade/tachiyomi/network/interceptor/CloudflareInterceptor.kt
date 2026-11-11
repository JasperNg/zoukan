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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val response = chain.proceed(request)
        if (!isChallenge(response)) return response

        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie = cookieManager.get(request.url).firstOrNull { it.name == CF_CLEARANCE }
            // Cloudflare rejects a user agent that does not match the browser (for example the
            // default Android one on desktop Chromium), so the challenge runs with Chromium's own
            val browserUserAgent = DesktopBrowser.userAgent
            val userAgent = browserUserAgent ?: request.header("User-Agent") ?: defaultUserAgentProvider()
            val solved = solveHeadless(request, userAgent, oldCookie) || run {
                // The user may take minutes; the call timeout (2 min) would end the call first
                (chain.call().timeout() as? AsyncTimeout)?.exit()
                DesktopBrowser.solveChallenge(request.url.toString(), userAgent) { isSolved(request, oldCookie) }
            }
            if (!solved) throw IOException("Failed to bypass Cloudflare on ${request.url.host}")
            if (browserUserAgent == null) return chain.proceed(request)
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

    private fun hasClearance(request: Request): Boolean =
        cookieManager.get(request.url).any { it.name == CF_CLEARANCE }

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

    fun solveChallenge(url: String, userAgent: String, isSolved: () -> Boolean): Boolean =
        challengeSolver?.solve(url, userAgent, isSolved) ?: false
}
// ZOUKAN <--
