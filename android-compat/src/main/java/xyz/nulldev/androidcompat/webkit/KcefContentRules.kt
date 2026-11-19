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
/**
 * Guards a callback that Chromium's native code calls through JCEF (a request filter, a handler of
 * the browser windows): nothing is thrown back there, an [Error] included, as whether native code
 * clears a pending Java exception is not something to rely on. An exception is logged; an [Error]
 * (out of memory, a missing class) is logged and the first one is reported as not fatal
 * ([KcefRequestFilter.reportOnce]). The logging and the report themselves cannot throw either.
 */
object NativeCallback {
    /** [block]'s value, or [fallback] (what the callback does by default) when it fails. */
    inline fun <T> guard(fallback: T, what: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        failed(e, what)
        fallback
    }

    /** Logs [e] and reports an [Error] once; never throws, even out of memory. */
    @PublishedApi
    internal fun failed(e: Throwable, what: String) {
        try {
            if (e is Exception) {
                logger.warn(e) { "$what failed; Chromium goes on as by default" }
            } else {
                logger.error(e) { "$what failed with an error; Chromium goes on as by default" }
                KcefRequestFilter.reportOnce(e)
            }
        } catch (_: Throwable) {
            // Out of memory while logging or reporting: nothing more can be done, and nothing is thrown
        }
    }
}

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

    /**
     * Where an [Error] the filter threw is reported (the app's crash report, as not fatal), set by
     * the app at start; the first one only, as the filter runs for every request a page makes. It
     * cannot be rethrown: see [shouldBlock].
     */
    @Volatile
    var reportError: ((Throwable) -> Unit)? = null
        set(value) {
            field = value
            errorReported.set(false)
        }

    private val errorReported = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Whether [filter] cancels the request; false when there is none or it fails. Called from
     * Chromium's IO thread through JCEF's native glue (`onBeforeResourceLoad`), so nothing is
     * thrown back ([NativeCallback]): an [Error] (out of memory, a missing class) is logged and
     * given to [reportError]; any failure lets the request through, as the content blocker is a privacy feature, not a
     * security boundary (THREAT-MODEL.md).
     */
    fun shouldBlock(requestUrl: String?, pageUrl: String?): Boolean = NativeCallback.guard(false, "The request filter") {
        if (requestUrl.isNullOrEmpty()) return false
        val current = filter ?: return false
        current.shouldBlock(requestUrl, pageUrl)
    }

    /**
     * Gives [error] to [reportError] when no error was reported since it was set. Throws no
     * exception; an [Error] the report itself throws is caught by its caller, [NativeCallback].
     * For every callback from Chromium's native code (the built-in browser's too), which cannot
     * rethrow an [Error] either and share the one report. The hook runs on the calling thread
     * (Chromium's): the app's hook hands the writing to a background thread.
     */
    fun reportOnce(error: Throwable) {
        try {
            if (errorReported.compareAndSet(false, true)) reportError?.invoke(error)
        } catch (notReported: Exception) {
            logger.warn(notReported) { "Could not report the request filter's error" }
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
