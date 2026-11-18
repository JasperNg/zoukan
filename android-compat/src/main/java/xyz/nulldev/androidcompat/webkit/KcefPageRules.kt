// ZOUKAN -->
package xyz.nulldev.androidcompat.webkit

import android.net.Uri
import android.webkit.WebResourceRequest
import java.net.URI

/**
 * Which addresses the top frame of a browser window or an extension's WebView may go to: web pages
 * (http, https) and the blank page. `data:`, `javascript:`, `file:`, `blob:`, `chrome:` and custom
 * app schemes are refused, as the start address and pop-ups already are.
 */
object KcefNavigationPolicy {
    /** Whether the top frame may load [url]. Never throws. */
    fun isAllowedMainFrameUrl(url: String?): Boolean {
        val address = url?.trim().orEmpty()
        val scheme = address.substringBefore(':', "").lowercase()
        if (scheme == "http" || scheme == "https") return true
        val lower = address.lowercase()
        return lower == "about:blank" || lower.startsWith("about:blank#") || lower.startsWith("about:blank?")
    }

    /**
     * As [isAllowedMainFrameUrl], but an extension's WebView also loads the exact address the
     * extension itself asked for ([requestedByApp]: its loadUrl, postUrl or loadDataWithBaseURL),
     * as Android does; only where the page goes from there is checked.
     */
    fun isAllowedInWebView(
        url: String?,
        requestedByApp: String?,
    ): Boolean = isAllowedMainFrameUrl(url) || (url != null && requestedByApp != null && url == requestedByApp)
}

/**
 * Decides whether a page's alert, confirm or prompt is shown, for one browser. A dialog from a frame
 * of another origin than the page in the top frame (an ad's or a widget's iframe) never is. The top
 * page gets [maxDialogs] dialogs in [windowMillis]; one more within that time silences the page
 * until the top frame goes to another address. Thread-safe.
 *
 * Chromium tells a dialog's origin, not its frame, so an iframe of the page's own origin counts as
 * the page. A page with no web origin (the blank page, or an extension's loadDataWithBaseURL with
 * no base address) gets its dialogs as on Android, under the same limit, when the dialog has no web
 * origin either.
 */
class JsDialogLimiter(
    private val maxDialogs: Int = 3,
    private val windowMillis: Long = 10_000,
) {
    private var page: String? = null
    private val shown = ArrayDeque<Long>()
    private var silenced = false

    /**
     * Whether to show a dialog that a frame of [dialogOrigin] opened at [nowMillis], while the top
     * frame shows [pageUrl]. A suppressed dialog does not count. Never throws.
     */
    @Synchronized
    fun allow(
        pageUrl: String?,
        dialogOrigin: String?,
        nowMillis: Long,
    ): Boolean {
        // Both null is a page with no web origin showing its own dialog; an opaque dialog on a web
        // page, or a web dialog on an opaque page, is another frame's
        if (origin(pageUrl) != origin(dialogOrigin)) return false
        // A history.pushState to another address also lifts the silence (accepted)
        val key = pageUrl.orEmpty().substringBefore('#')
        if (key != page) {
            page = key
            shown.clear()
            silenced = false
        }
        if (silenced) return false
        while (shown.isNotEmpty() && nowMillis - shown.first() >= windowMillis) shown.removeFirst()
        if (shown.size >= maxDialogs) {
            silenced = true
            return false
        }
        shown.addLast(nowMillis)
        return true
    }

    private fun origin(url: String?): String? {
        val uri = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port =
            when {
                uri.port != -1 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> -1
            }
        return "$scheme://$host:$port"
    }
}

/**
 * Tells the client of a top-frame navigation that was cancelled, without waiting for it: [post] runs
 * [tell] later on the WebView's looper, so an extension that blocks in shouldOverrideUrlLoading
 * cannot hold up Chromium's UI thread, and with it every browser. Its answer is not needed: the
 * navigation is cancelled anyway. A failure in [tell] goes to [onFailure] and no further. Never throws.
 */
object KcefCancelledNavigation {
    fun tellLater(
        post: (Runnable) -> Unit,
        tell: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        runCatching {
            post(Runnable { runCatching(tell).onFailure { runCatching { onFailure(it) } } })
        }.onFailure { runCatching { onFailure(it) } }
    }
}

/**
 * A navigation request as it was when Chromium asked about it. Chromium's own request object may be
 * gone by the time a posted callback reads it.
 */
class SnapshotWebResourceRequest(
    private val url: String?,
    private val method: String,
    private val headers: Map<String, String>,
    private val forMainFrame: Boolean,
    private val redirect: Boolean,
    private val gesture: Boolean = false,
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame(): Boolean = forMainFrame

    override fun isRedirect(): Boolean = redirect

    override fun hasGesture(): Boolean = gesture

    override fun getMethod(): String = method

    override fun getRequestHeaders(): Map<String, String> = headers
}
// ZOUKAN <--
