// ZOUKAN -->
package xyz.nulldev.androidcompat.webkit

import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.browser.CefBrowser
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandlerAdapter

private val logger = KotlinLogging.logger {}

/**
 * What extensions' hidden WebViews may load, set by the app at start. Android-compat knows no
 * block list; the app decides. Every answer fails open: a filter that throws blocks nothing.
 */
object KcefRequestFilter {
    /**
     * Returns true to cancel a request for a subresource or a frame inside a page. [pageUrl] is the
     * address of the page in the top frame, or null when it is not known. The main frame's own
     * navigations are never asked about.
     */
    fun interface Filter {
        fun shouldBlock(requestUrl: String, pageUrl: String?): Boolean
    }

    @Volatile
    var filter: Filter? = null

    /** Whether [filter] cancels the request; false when there is none or it fails. */
    fun shouldBlock(requestUrl: String?, pageUrl: String?): Boolean {
        if (requestUrl.isNullOrEmpty()) return false
        val current = filter ?: return false
        return try {
            current.shouldBlock(requestUrl, pageUrl)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            logger.warn(e) { "The request filter failed; the request goes ahead" }
            false
        }
    }
}

/**
 * What a hidden WebView does with a page's request for a new window. It never gets a native
 * window: one would escape the app lock and the secure screen, and nobody would see or close it.
 */
object KcefPopupPolicy {
    /**
     * The address to load in the WebView itself, or null to drop the pop-up. As on Android with
     * multiple windows off, a script's window.open loads in place only when the extension let
     * JavaScript open windows ([WebSettings.setJavaScriptCanOpenWindowsAutomatically]).
     */
    fun loadInPlace(
        targetUrl: String?,
        javaScriptCanOpenWindows: Boolean,
    ): String? {
        if (!javaScriptCanOpenWindows || targetUrl.isNullOrBlank()) return null
        val scheme = targetUrl.substringBefore(':', "").lowercase()
        return targetUrl.takeIf { scheme == "http" || scheme == "https" }
    }
}

/**
 * Cancels every download Chromium starts. Neither the built-in browser nor an extension's WebView
 * saves files from the web: the app downloads chapters itself.
 */
object CefDownloadBlocker : CefDownloadHandlerAdapter() {
    override fun onBeforeDownload(
        browser: CefBrowser?,
        downloadItem: CefDownloadItem?,
        suggestedName: String?,
        callback: CefBeforeDownloadCallback?,
    ): Boolean {
        logger.info { "A page started a download; it is cancelled" }
        // Handled, and Continue is never called: the download gets no place to go
        return true
    }

    override fun onDownloadUpdated(
        browser: CefBrowser?,
        downloadItem: CefDownloadItem?,
        callback: CefDownloadItemCallback?,
    ) {
        // In case one started anyway
        val item = downloadItem ?: return
        val active = runCatching { !item.isComplete && !item.isCanceled }.getOrDefault(true)
        if (active) runCatching { callback?.cancel() }
    }
}
// ZOUKAN <--
