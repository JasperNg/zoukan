package xyz.nulldev.androidcompat.webkit

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.print.PrintDocumentAdapter
import android.util.Log
import android.util.SparseArray
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.textclassifier.TextClassifier
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebView.PictureListener
import android.webkit.WebView.VisualStateCallback
import android.webkit.WebViewClient
import android.webkit.WebViewProvider
import android.webkit.WebViewProvider.ScrollDelegate
import android.webkit.WebViewProvider.ViewDelegate
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import kotlinx.coroutines.runBlocking
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
// ZOUKAN -->
import org.cef.browser.CefBrowserHeadless
// ZOUKAN <--
import org.cef.callback.CefCallback
// ZOUKAN -->
import org.cef.callback.CefJSDialogCallback
// ZOUKAN <--
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
// ZOUKAN -->
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
// ZOUKAN <--
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRenderHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefPostData
import org.cef.network.CefPostDataElement
import org.cef.network.CefRequest
import org.cef.network.CefResponse
// ZOUKAN -->
import uy.kohesive.injekt.injectLazy
// ZOUKAN <--
import java.awt.Rectangle
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import javax.swing.JPanel
import kotlin.math.min

class KcefWebViewProvider(
    private val view: WebView,
) : WebViewProvider {
    private val settings = KcefWebSettings()
    private var viewClient = WebViewClient()
    private var chromeClient = WebChromeClient()
    private val renderHandler = RenderHandler()
    private val handler = Handler(view.webViewLooper)
    // ZOUKAN -->
    // Lifecycle, JS bridge, and cleanup collaborators; mappings stays a field for thread-safety tests
    private val lifecycle = KcefLifecycleState(cleanupRegistry)
    private val jsBridge = KcefJsBridge(
        isDestroyed = { lifecycle.destroyed },
        postToLooper = { handler.post(it) },
    )
    private val mappings = jsBridge.mappings
    private val urlHttpMapping: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    // Set on the looper, consumed on Chromium's IO thread
    @Volatile
    private var initialRequestData: InitialRequestData? = null

    private var kcefClient: CefClient?
        get() = lifecycle.native.client
        set(value) {
            lifecycle.native.client = value
        }
    private var browser: CefBrowser?
        get() = lifecycle.native.browser
        set(value) {
            lifecycle.native.browser = value
        }

    private val destroyed: Boolean
        get() = lifecycle.destroyed
    // ZOUKAN <--

    // ZOUKAN -->
    // Chromium's client keeps its handlers for as long as Chromium runs, so it gets weak forwarders
    // to these: a WebView dropped without destroy() can then be collected, and its browser disposed
    private val lifeSpanHandler = LifeSpanHandler()
    private val loadHandler = LoadHandler()
    private val requestHandler = RequestHandler()
    private val messageRouterHandler = MessageRouterHandler()
    private val displayHandler = DisplayHandler()
    private val jsDialogHandler = JsDialogHandler()

    // Which of a page's alerts, confirms and prompts reach the WebChromeClient
    private val dialogLimiter = JsDialogLimiter()

    // The address the extension last asked to load: the top frame may go there whatever its scheme
    // (see KcefNavigationPolicy); set on the looper, read on Chromium's UI thread
    @Volatile
    private var requestedUrl: String? = null
    // ZOUKAN <--

    companion object {
        const val TAG = "KcefWebViewProvider"
        const val QUERY_FN = "__\$_suwayomiQuery"
        const val QUERY_CANCEL_FN = "__\$_suwayomiQueryCancel"

        // ZOUKAN -->
        /**
         * The host of [url] for the log, without the path, query or user info that can carry a
         * sign-in code or token. A URL without a host gives its scheme ("about:"), and one that
         * does not parse gives "?". Never throws.
         */
        fun hostForLog(url: String?): String {
            if (url.isNullOrBlank()) return "?"
            return try {
                val uri = java.net.URI(url.trim())
                uri.host ?: uri.scheme?.let { "$it:" } ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        // ZOUKAN <--

        // ZOUKAN -->
        private val cleanupRegistry = KcefCleanupRegistry<KcefWebViewProvider>()

        /**
         * Disposes the browsers that destroy() left for later. Chromium crashes when it shuts down
         * with browsers still open, so this runs before it does (on the AWT thread).
         */
        @JvmStatic
        fun disposePending() = cleanupRegistry.disposePending { it.destroy() }
        // ZOUKAN <--

        // ZOUKAN -->
        /** Chromium's severity of a console message as Android's level (console.debug is VERBOSE). */
        @JvmStatic
        fun consoleLevel(level: CefSettings.LogSeverity): ConsoleMessage.MessageLevel =
            when (level) {
                CefSettings.LogSeverity.LOGSEVERITY_VERBOSE -> ConsoleMessage.MessageLevel.DEBUG
                CefSettings.LogSeverity.LOGSEVERITY_WARNING -> ConsoleMessage.MessageLevel.WARNING
                CefSettings.LogSeverity.LOGSEVERITY_ERROR,
                CefSettings.LogSeverity.LOGSEVERITY_FATAL,
                -> ConsoleMessage.MessageLevel.ERROR
                else -> ConsoleMessage.MessageLevel.LOG
            }
        // ZOUKAN <--

        private val initHandler: InitBrowserHandler /* ZOUKAN --> */by injectLazy()/* ZOUKAN <-- */
    }

    interface InitBrowserHandler {
        fun init(provider: KcefWebViewProvider): Unit
    }

    private inner class LifeSpanHandler : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(browser: CefBrowser) {
            // ZOUKAN -->
            KcefDevTools.applyUserAgentOverride(browser, settings.userAgentString, settings.getUserAgentMetadata())
            KcefDevTools.enableTouch(browser)
            // ZOUKAN <--
        }

        // ZOUKAN -->
        // Never a native window (see KcefPopupPolicy); the address loads here when the extension
        // let scripts open windows, as Android does with multiple windows off
        override fun onBeforePopup(
            browser: CefBrowser,
            frame: CefFrame?,
            targetUrl: String?,
            targetFrameName: String?,
        ): Boolean {
            val target = KcefPopupPolicy.loadInPlace(targetUrl, settings.javaScriptCanOpenWindowsAutomatically)
            if (target != null) {
                runCatching { browser.loadURL(target) }.onFailure { Log.w(TAG, "Loading a pop-up's address failed", it) }
            } else {
                Log.v(TAG, "Dropped a pop-up to ${targetUrl?.take(100)}")
            }
            return true
        }
        // ZOUKAN <--
    }

    private data class InitialRequestData(
        private val additionalHttpHeaders: Map<String, String>? = null,
        private val myPostData: ByteArray? = null,
    ) {
        fun apply(request: CefRequest?) {
            request?.apply {
                Log.v(TAG, "Initial request: applying headers and post data")
                if (!additionalHttpHeaders.isNullOrEmpty()) {
                    additionalHttpHeaders.forEach {
                        setHeaderByName(it.key, it.value, true)
                    }
                }

                if (myPostData != null) {
                    this.postData =
                        CefPostData.create().apply {
                            addElement(
                                CefPostDataElement.create().apply {
                                    setToBytes(myPostData.size, myPostData)
                                },
                            )
                        }
                }
            }
        }
    }

    private class CefWebResourceRequest(
        val request: CefRequest?,
        val frame: CefFrame?,
        val redirect: Boolean,
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(request?.url)

        override fun isForMainFrame(): Boolean = frame?.isMain ?: false

        override fun isRedirect(): Boolean = redirect

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = request?.method ?: "GET"

        override fun getRequestHeaders(): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            request?.getHeaderMap(headers)
            return headers
        }
    }

    // ZOUKAN -->
    // Upstream JCEF has no media access permission handler, so pages cannot ask for the camera or
    // microphone. Suwayomi's CefPermissionRequest and PermissionHandler are left out.
    /*
    private class CefPermissionRequest(
        private val url: String,
        private val permissionMask: Int,
        private val callback: CefMediaAccessCallback,
    ) : PermissionRequest() {
        override fun getOrigin(): Uri = Uri.parse(url)

        override fun getResources(): Array<String> {
            val retVal = mutableListOf<String>()
            if ((permissionMask and (1 shl 0)) > 0) retVal.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if ((permissionMask and (1 shl 1)) > 0) retVal.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            return retVal.toTypedArray()
        }

        override fun grant(resources: Array<String>) {
            // TODO: respect given resource grant
            callback.Continue(permissionMask)
        }

        override fun deny() {
            callback.Cancel()
        }
    }
    */
    // ZOUKAN <--

    // ZOUKAN -->
    // Inner, to hand console messages to the WebChromeClient (see WeakDisplayHandler)
    private inner class DisplayHandler : CefDisplayHandlerAdapter() {
        // ZOUKAN <--
        override fun onConsoleMessage(
            browser: CefBrowser,
            level: CefSettings.LogSeverity,
            message: String,
            source: String,
            line: Int,
        ): Boolean {
            Log.v(TAG, "$source:$line[$level]: $message")
            // ZOUKAN -->
            // As on Android, where extensions read results of injected scripts from console.log
            // in WebChromeClient.onConsoleMessage, which also calls the deprecated 3-argument one
            val consoleMessage = ConsoleMessage(message, source, line, consoleLevel(level))
            handler.post {
                if (!destroyed) chromeClient.onConsoleMessage(consoleMessage)
            }
            // ZOUKAN <--
            return true
        }

        override fun onAddressChange(
            browser: CefBrowser,
            frame: CefFrame,
            url: String,
        ) {
            Log.d(TAG, "Navigate to $url")
        }

        override fun onStatusMessage(
            browser: CefBrowser,
            value: String,
        ) {
            Log.v(TAG, "Status update: $value")
        }
    }

    @Suppress("DEPRECATION")
    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadEnd(
            browser: CefBrowser,
            frame: CefFrame,
            httpStatusCode: Int,
        ) {
            // ZOUKAN -->
            // Only the main frame ends the page, as on Android: an iframe (Turnstile, an ad) that
            // finishes must not run onPageFinished, the error callbacks or the cookie copy
            if (!frame.isMain) return
            // ZOUKAN <--
            val url = frame.url ?: ""
            Log.v(TAG, "Load end $url")
            handler.post {
                if (httpStatusCode == 404) {
                    viewClient.onReceivedError(
                        view,
                        WebViewClient.ERROR_FILE_NOT_FOUND,
                        "Not Found",
                        url,
                    )
                }
                if (httpStatusCode == 429) {
                    viewClient.onReceivedError(
                        view,
                        WebViewClient.ERROR_TOO_MANY_REQUESTS,
                        "Too Many Requests",
                        url,
                    )
                }
                if (httpStatusCode >= 400) {
                    // TODO: create request and response
                    // viewClient.onReceivedHttpError(_view, ...);
                }
                // ZOUKAN -->
                // Let the app see cookies the page set (cf_clearance) before onPageFinished, where a
                // client checks for them. Chromium visits them on its own thread, so onPageFinished
                // is posted once the visit ends; a page that starts loading first gets it before
                // its onPageStarted, as on Android
                val finish = PageFinish(url)
                pendingFinish = finish
                val userAgent = settings.userAgentString
                CefCookieSync.copyFromCef(url) {
                    runCatching { CefCookieSync.onCopiedFromCef?.invoke(url, userAgent) }
                    handler.post { finish.run() }
                }
                // ZOUKAN <--
            }
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String,
        ) {
            // ZOUKAN -->
            // Only the host: the path and query of a failed load can carry a sign-in code or token
            Log.w(TAG, "Load error (${hostForLog(failedUrl)}) [$errorCode]: $errorText")
            // ZOUKAN <--
            // TODO: translate correctly
            // ZOUKAN -->
            // Only the main frame's error reaches the client, as Android's onReceivedError
            if (!frame.isMain) return
            // Read the URL now; the frame can be gone when the main looper runs this
            val frameUrl = frame.url ?: failedUrl ?: ""
            handler.post {
                viewClient.onReceivedError(view, WebViewClient.ERROR_UNKNOWN, errorText, frameUrl)
            }
            // ZOUKAN <--
        }

        override fun onLoadStart(
            browser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType,
        ) {
            Log.v(TAG, "Load start, pushing mappings")
            // ZOUKAN -->
            jsBridge.pushMappings { js, sourceUrl ->
                browser.executeJavaScript(js, sourceUrl, 0)
            }
            // ZOUKAN <--

            // ZOUKAN -->
            // The mappings above go to every frame (an iframe's page may call them), but only the
            // main frame starts the page, as on Android
            if (!frame.isMain) return
            // Read the URL now; the frame can be gone when the main looper runs this
            val frameUrl = frame.url ?: ""
            handler.post {
                // The last page's onPageFinished first, if its cookie visit has not ended
                pendingFinish?.run()
                viewClient.onPageStarted(view, frameUrl, null)
            }
            // ZOUKAN <--
        }
    }

    // ZOUKAN -->
    /** The onPageFinished of a page whose cookies are still being copied; main looper only. */
    private var pendingFinish: PageFinish? = null

    /** Calls onPageFinished for [url] once; runs on the main looper. */
    private inner class PageFinish(private val url: String) {
        private var done = false

        fun run() {
            if (done) return
            done = true
            if (pendingFinish === this) pendingFinish = null
            viewClient.onPageFinished(view, url)
            chromeClient.onProgressChanged(view, 100)
        }
    }
    // ZOUKAN <--

    private inner class MessageRouterHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean =
            // ZOUKAN -->
            jsBridge.handleQuery(
                request,
                object : KcefJsBridge.QueryResponder {
                    override fun success(response: String) = callback.success(response)

                    override fun failure(
                        errorCode: Int,
                        errorMessage: String?,
                    ) = callback.failure(errorCode, errorMessage)
                },
            )
        // ZOUKAN <--
    }

    private abstract class ArrayResponseResourceHandler : CefResourceHandlerAdapter() {
        protected var resolvedData: ByteArray? = null
        protected var readOffset = 0

        override fun getResponseHeaders(
            response: CefResponse,
            responseLength: IntRef,
            redirectUrl: StringRef,
        ) {
            responseLength.set(resolvedData?.size ?: 0)
            response.status = 200
            response.statusText = "OK"
            response.mimeType = "text/html"
        }

        override fun readResponse(
            dataOut: ByteArray,
            bytesToRead: Int,
            bytesRead: IntRef,
            callback: CefCallback,
        ): Boolean {
            val data = resolvedData ?: return false
            val bytesToTransfer = min(bytesToRead, data.size - readOffset)
            Log.v(
                TAG,
                "readResponse: $readOffset/${data.size}, reading $bytesToRead->$bytesToTransfer",
            )
            data.copyInto(dataOut, startIndex = readOffset, endIndex = readOffset + bytesToTransfer)
            bytesRead.set(bytesToTransfer)
            readOffset += bytesToTransfer
            return bytesToTransfer != 0
        }
    }

    private class WebResponseResourceHandler(
        val webResponse: WebResourceResponse,
    ) : ArrayResponseResourceHandler() {
        override fun processRequest(
            request: CefRequest,
            callback: CefCallback,
        ): Boolean {
            Log.v(TAG, "Handling request from client's response for ${request.url}")
            try {
                resolvedData = webResponse.data.readAllBytes()
                Log.v(TAG, "Resolved client response for ${resolvedData?.size} bytes")
            } catch (e: IOException) {
                Log.w(TAG, "Failed to read client data", e)
            }
            callback.Continue()
            return true
        }

        override fun getResponseHeaders(
            response: CefResponse,
            responseLength: IntRef,
            redirectUrl: StringRef,
        ) {
            super.getResponseHeaders(response, responseLength, redirectUrl)
            webResponse.responseHeaders?.forEach { response.setHeaderByName(it.key, it.value, true) }
            response.status = webResponse.statusCode
            response.mimeType = webResponse.mimeType
        }
    }

    private class HtmlResponseResourceHandler(
        val html: String,
    ) : ArrayResponseResourceHandler() {
        override fun processRequest(
            request: CefRequest,
            callback: CefCallback,
        ): Boolean {
            Log.v(TAG, "Handling request from HTML cache for ${request.url}")
            resolvedData = html.toByteArray()
            callback.Continue()
            return true
        }
    }

    // ZOUKAN -->
    // isNavigation: whether the request loads a page (the main frame or an iframe) and not a
    // subresource such as an image or a script
    private inner class ResourceRequestHandler(
        private val isNavigation: Boolean,
    ) : CefResourceRequestHandlerAdapter() {
        // ZOUKAN <--
        override fun onBeforeResourceLoad(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): Boolean {
            initialRequestData?.apply(request)
            initialRequestData = null

            // ZOUKAN -->
            // The app's content blocker, for what a page loads besides its own main frame
            val resourceType = runCatching { request.resourceType }.getOrNull()
            if (resourceType != null && resourceType != CefRequest.ResourceType.RT_MAIN_FRAME) {
                val pageUrl = runCatching { browser?.url }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { request.firstPartyForCookies }.getOrNull()?.takeIf { it.isNotBlank() }
                if (KcefRequestFilter.shouldBlock(request.url, pageUrl)) {
                    Log.v(TAG, "Blocked ${request.url?.take(100)}")
                    return true
                }
            }
            // ZOUKAN <--

            // TODO: we should be calling this on the handler, since CEF calls us on its IO thread
            // thus if a client tried to use WebView#loadUrl as the docs suggest, this fails
            val cancel =
                // ZOUKAN -->
                // Asked about navigations only, as on Android, not about every image or script.
                // Android calls it on the WebView's thread, so it may call WebView methods
                if (isNavigation) {
                    WebView.callClientOffThread {
                        viewClient.shouldOverrideUrlLoading(
                            view,
                            CefWebResourceRequest(request, frame, false),
                        )
                    }
                } else {
                    false
                }
            // ZOUKAN <--
            Log.v(TAG, "Resource ${request.url}, result is cancel? $cancel")

            // ZOUKAN -->
            // The resource's URL, read now: the request can change when the main looper runs this
            val resourceUrl = request.url ?: ""
            handler.post { viewClient.onLoadResource(view, resourceUrl) }
            // ZOUKAN <--

            return cancel || settings.blockNetworkLoads
        }

        override fun getResourceHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
        ): CefResourceHandler? {
            val isInitialLoad = frame.url == "" && request.method == "GET"
            Log.v(TAG, "Request ${request.method} ${request.url} is initial? $isInitialLoad")
            // NOTE: we should be calling this on the handler, since CEF calls us on its IO thread
            // but docs say "This method is called on a thread other than the UI thread" so should be fine
            val response =
                if (isInitialLoad) {
                    null
                } else {
                    viewClient.shouldInterceptRequest(
                        view,
                        CefWebResourceRequest(request, frame, false),
                    )
                }
            if (response == null) {
                // prefer user's response override
                urlHttpMapping[request.url.trimEnd('/')]?.let {
                    return HtmlResponseResourceHandler(it)
                }
            }
            response ?: return null
            return WebResponseResourceHandler(response)
        }
    }

    @Suppress("DEPRECATION")
    private inner class RequestHandler : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String,
            disableDefaultHandling: BoolRef,
        ): CefResourceRequestHandler = /* ZOUKAN --> */ResourceRequestHandler(isNavigation)/* ZOUKAN <-- */

        // ZOUKAN -->
        // The top frame goes to web pages only, besides the address the extension asked for (see
        // KcefNavigationPolicy). The client still hears of the address first, as Android asks
        // shouldOverrideUrlLoading about every navigation: an extension may catch its own scheme
        override fun onBeforeBrowse(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
            if (!frame.isMain) return false
            val url = runCatching { request.url }.getOrNull()
            if (KcefNavigationPolicy.isAllowedInWebView(url, requestedUrl)) return false
            Log.w(TAG, "Cancelled a navigation of the top frame to a ${url?.substringBefore(':')?.take(20)}: address")
            if (!destroyed) {
                // Posted, not waited for: the answer is not needed, and an extension that blocks
                // here must not hold up Chromium's UI thread. The request is read now, while it lives
                val navigation =
                    runCatching {
                        val headers = mutableMapOf<String, String>()
                        runCatching { request.getHeaderMap(headers) }
                        SnapshotWebResourceRequest(
                            url = url,
                            method = request.method ?: "GET",
                            headers = headers,
                            forMainFrame = true,
                            redirect = isRedirect,
                        )
                    }.getOrNull()
                if (navigation != null) {
                    KcefCancelledNavigation.tellLater(
                        post = { handler.post(it) },
                        tell = { if (!destroyed) viewClient.shouldOverrideUrlLoading(view, navigation) },
                        onFailure = { Log.w(TAG, "shouldOverrideUrlLoading failed", it) },
                    )
                }
            }
            return true
        }
        // ZOUKAN <--

        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: CefRequestHandler.TerminationStatus,
            errorCode: Int,
            errorString: String,
        ) {
            handler.post {
                viewClient.onRenderProcessGone(
                    view,
                    object : RenderProcessGoneDetail() {
                        override fun didCrash(): Boolean = status == CefRequestHandler.TerminationStatus.TS_PROCESS_CRASHED

                        override fun rendererPriorityAtExit(): Int = -1
                    },
                )
            }
        }
    }

    // ZOUKAN -->
    /*
    private inner class PermissionHandler : CefPermissionHandler {
        override fun onRequestMediaAccessPermission(
            browser: CefBrowser,
            frame: CefFrame,
            requestingUrl: String,
            requestedPermissions: Int,
            callback: CefMediaAccessCallback,
        ): Boolean {
            handler.post {
                Log.v(TAG, "Checking permission for $requestingUrl: $requestedPermissions")
                chromeClient.onPermissionRequest(CefPermissionRequest(requestingUrl, requestedPermissions, callback))
            }
            return true
        }
    }
    */
    // ZOUKAN <--

    // ZOUKAN -->
    /**
     * A page's alert, confirm and prompt go to the extension's WebChromeClient (onJsAlert,
     * onJsConfirm, onJsPrompt) on the WebView's looper, as on Android; one the client does not
     * handle is cancelled, as nobody could answer it in a hidden WebView, and so is one it keeps
     * but does not answer within [JsDialogAnswer.DIALOG_TIMEOUT_MILLIS]. A dialog from a frame of
     * another origin, or past the limit (see [JsDialogLimiter]), is suppressed. A page never keeps
     * itself from being left or closed: onbeforeunload is always answered "leave".
     */
    private inner class JsDialogHandler : CefJSDialogHandlerAdapter() {
        override fun onJSDialog(
            browser: CefBrowser,
            originUrl: String?,
            dialogType: CefJSDialogHandler.JSDialogType?,
            messageText: String?,
            defaultPromptText: String?,
            callback: CefJSDialogCallback,
            suppressMessage: BoolRef,
        ): Boolean {
            val pageUrl = runCatching { browser.url }.getOrNull()
            if (destroyed || dialogType == null || !dialogLimiter.allow(pageUrl, originUrl, System.currentTimeMillis())) {
                Log.v(TAG, "Suppressed a JavaScript dialog")
                suppressMessage.set(true)
                return false
            }
            // Answered exactly once: by the client, by the cancel below, at once when the looper
            // drops the post, or by the timeout, so the page never waits forever on a dialog
            val dialog = JsDialogAnswer({ ok, text -> callback.Continue(ok, text) })
            val url = pageUrl ?: originUrl.orEmpty()
            val message = messageText.orEmpty()
            dialog.post({ handler.post(it) }) {
                val handled =
                    !destroyed &&
                        runCatching {
                            when (dialogType) {
                                CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT ->
                                    chromeClient.onJsAlert(view, url, message, android.webkit.JsResult { dialog.answer(it.result, null) })
                                CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_CONFIRM ->
                                    chromeClient.onJsConfirm(view, url, message, android.webkit.JsResult { dialog.answer(it.result, null) })
                                CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_PROMPT ->
                                    chromeClient.onJsPrompt(
                                        view,
                                        url,
                                        message,
                                        defaultPromptText,
                                        android.webkit.JsPromptResult { dialog.answer(it.result, (it as? android.webkit.JsPromptResult)?.stringResult) },
                                    )
                            }
                        }.onFailure { Log.w(TAG, "The WebChromeClient failed on a JavaScript dialog", it) }
                            .getOrDefault(false)
                if (!handled) dialog.answer(false, null)
            }
            return true
        }

        override fun onBeforeUnloadDialog(
            browser: CefBrowser,
            messageText: String?,
            isReload: Boolean,
            callback: CefJSDialogCallback,
        ): Boolean {
            runCatching { callback.Continue(true, "") }
            return true
        }
    }

    /**
     * The answer to one JavaScript dialog, given to Chromium ([continueDialog]) exactly once,
     * whichever comes first: the WebChromeClient's confirm or cancel, a cancel because the post to
     * the WebView's looper was dropped (see [post]), or a cancel when [scheduleTimeout] runs its
     * task. Extensions' WebViews are hidden, so a dialog the client keeps but never answers would
     * otherwise block the page in Chromium forever. Thread-safe; never blocks the caller.
     */
    class JsDialogAnswer(
        private val continueDialog: (ok: Boolean, text: String) -> Unit,
        scheduleTimeout: (Runnable) -> java.util.concurrent.Future<*> = { scheduleOnTimeoutThread(it, DIALOG_TIMEOUT_MILLIS) },
    ) {
        private val answered = java.util.concurrent.atomic.AtomicBoolean(false)

        // Scheduled last: a timeout that fires at once finds the guard ready
        private val timeout: java.util.concurrent.Future<*>? =
            runCatching {
                scheduleTimeout(
                    Runnable {
                        if (answer(false, null)) Log.w(TAG, "A JavaScript dialog was not answered in time and was cancelled")
                    },
                )
            }.onFailure { Log.w(TAG, "Could not set a timeout on a JavaScript dialog", it) }
                .getOrNull()

        /** Whether the dialog has been answered. */
        val isAnswered: Boolean
            get() = answered.get()

        /**
         * Answers the dialog with [ok] and, for a prompt, [text]. Returns false, doing nothing, if
         * it was answered before. Never throws.
         */
        fun answer(
            ok: Boolean,
            text: String?,
        ): Boolean {
            if (!answered.compareAndSet(false, true)) return false
            timeout?.cancel(false)
            runCatching { continueDialog(ok, text.orEmpty()) }
                .onFailure { Log.w(TAG, "Answering a JavaScript dialog failed", it) }
            return true
        }

        /**
         * Hands [task] to [post]; if the post is refused (false, as when the looper has quit) or
         * throws, the dialog is cancelled at once. Never throws.
         */
        fun post(
            post: (Runnable) -> Boolean,
            task: () -> Unit,
        ) {
            val posted =
                try {
                    post(Runnable { task() })
                } catch (e: Exception) {
                    Log.w(TAG, "Posting a JavaScript dialog to the WebView's looper failed", e)
                    false
                }
            if (!posted && answer(false, null)) Log.w(TAG, "A JavaScript dialog could not reach the WebView's looper and was cancelled")
        }

        companion object {
            /** How long a dialog waits for the WebChromeClient before it is cancelled. */
            const val DIALOG_TIMEOUT_MILLIS = 60_000L

            // One daemon thread for all dialogs' timeouts; a cancelled timeout leaves its queue
            private val timeouts by lazy {
                java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
                    Thread(runnable, "KcefJsDialogTimeout").apply { isDaemon = true }
                }.apply { removeOnCancelPolicy = true }
            }

            /** Runs [task] after [delayMillis] on the shared timeout thread. */
            fun scheduleOnTimeoutThread(
                task: Runnable,
                delayMillis: Long,
            ): java.util.concurrent.Future<*> = timeouts.schedule(task, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
    // ZOUKAN <--

    private class RenderHandler : CefRenderHandlerAdapter() {
        override fun getViewRect(browser: CefBrowser): Rectangle = /* ZOUKAN --> */Rectangle(0, 0, xyz.nulldev.androidcompat.res.DesktopResources.SCREEN_WIDTH_PIXELS, xyz.nulldev.androidcompat.res.DesktopResources.SCREEN_HEIGHT_PIXELS)/* ZOUKAN <-- */

        // ZOUKAN -->
        override fun addOnPaintListener(listener: java.util.function.Consumer<org.cef.browser.CefPaintEvent>) {}

        override fun setOnPaintListener(listener: java.util.function.Consumer<org.cef.browser.CefPaintEvent>) {}

        override fun removeOnPaintListener(listener: java.util.function.Consumer<org.cef.browser.CefPaintEvent>) {}
        // ZOUKAN <--

        override fun onPaint(
            browser: CefBrowser,
            popup: Boolean,
            dirtyRects: Array<Rectangle>,
            buffer: ByteBuffer,
            width: Int,
            height: Int,
        ) {
            // do nothing
        }
    }

    override fun init(
        javaScriptInterfaces: Map<String, Any>?,
        privateBrowsing: Boolean,
    ) {
        Log.v(TAG, "KcefWebViewProvider: initialize")
        destroy()
        kcefClient =
            // ZOUKAN -->
            // blockingOffEdt, not runBlocking: Chromium's start needs the AWT thread
            blockingOffEdt {
                // ZOUKAN <--
                CefHelper.createClient().apply {
                    // ZOUKAN -->
                    addLifeSpanHandler(WeakLifeSpanHandler(lifeSpanHandler))
                    addDisplayHandler(WeakDisplayHandler(displayHandler))
                    addLoadHandler(WeakLoadHandler(loadHandler))
                    addRequestHandler(WeakRequestHandler(requestHandler))
                    addJSDialogHandler(WeakJsDialogHandler(jsDialogHandler))
                    // A hidden WebView never saves files
                    addDownloadHandler(CefDownloadBlocker)
                    // ZOUKAN <--
                    // ZOUKAN -->
                    // No addPermissionHandler(PermissionHandler()), see CefPermissionRequest
                    // ZOUKAN <--

                    val config = CefMessageRouter.CefMessageRouterConfig()
                    config.jsQueryFunction = QUERY_FN
                    config.jsCancelFunction = QUERY_CANCEL_FN
                    addMessageRouter(CefMessageRouter.create(config, /* ZOUKAN --> */WeakMessageRouterHandler(messageRouterHandler)/* ZOUKAN <-- */))
                }
            }
        // ZOUKAN -->
        lifecycle.markInitialized(this)
        // ZOUKAN <--
        initHandler.init(this)
    }

    // Deprecated - should never be called
    override fun setHorizontalScrollbarOverlay(overlay: Boolean): Unit = throw RuntimeException("Stub!")

    // Deprecated - should never be called
    override fun setVerticalScrollbarOverlay(overlay: Boolean): Unit = throw RuntimeException("Stub!")

    // Deprecated - should never be called
    override fun overlayHorizontalScrollbar(): Boolean = throw RuntimeException("Stub!")

    // Deprecated - should never be called
    override fun overlayVerticalScrollbar(): Boolean = throw RuntimeException("Stub!")

    override fun getVisibleTitleHeight(): Int = throw RuntimeException("Stub!")

    override fun getCertificate(): SslCertificate = throw RuntimeException("Stub!")

    override fun setCertificate(certificate: SslCertificate): Unit = throw RuntimeException("Stub!")

    override fun savePassword(
        host: String,
        username: String,
        password: String,
    ): Unit = throw RuntimeException("Stub!")

    override fun setHttpAuthUsernamePassword(
        host: String,
        realm: String,
        username: String,
        password: String,
    ): Unit = throw RuntimeException("Stub!")

    override fun getHttpAuthUsernamePassword(
        host: String,
        realm: String,
    ): Array<String> = throw RuntimeException("Stub!")

    override fun destroy() {
        // ZOUKAN -->
        if (postedToLooper { destroy() }) return
        // Disposed after the main looper's queued work: answering on a disposed browser crashed Chromium.
        lifecycle.markDestroyed(
            owner = this,
            postToLooper = { handler.post(it) },
            onClear = { jsBridge.clearPendingChunks() },
        )
        // ZOUKAN <--
    }

    // ZOUKAN -->
    /**
     * A client callback that Android runs on the WebView's thread runs on one of Chromium's here,
     * and the provider is not thread-safe. A WebView call made inside such a callback is posted to
     * the WebView's looper (in order) and this returns true; any other call returns false.
     */
    private fun postedToLooper(call: () -> Unit): Boolean {
        if (!WebView.isInClientCallback() || android.os.Looper.myLooper() == view.webViewLooper) return false
        handler.post(call)
        return true
    }
    // ZOUKAN <--

    // ZOUKAN -->
    // Android ignores calls on a destroyed WebView; the browser and client are gone after destroy()
    private fun ignoredAfterDestroy(call: String): Boolean = lifecycle.ignoredAfterDestroy(call)
    // ZOUKAN <--

    override fun setNetworkAvailable(networkUp: Boolean): Unit = throw RuntimeException("Stub!")

    override fun saveState(outState: Bundle): WebBackForwardList = throw RuntimeException("Stub!")

    override fun savePicture(
        b: Bundle,
        dest: File,
    ): Boolean = throw RuntimeException("Stub!")

    override fun restorePicture(
        b: Bundle,
        src: File,
    ): Boolean = throw RuntimeException("Stub!")

    override fun restoreState(inState: Bundle): WebBackForwardList = throw RuntimeException("Stub!")

    // ZOUKAN -->
    private fun startPageLoad(
        url: String,
        prepareRequest: () -> Unit,
    ) {
        // After queued work, as in destroy(); a queued answer to the old page needs it
        cleanupRegistry.disposeBrowserLater(browser) { handler.post(it) }
        chromeClient.onProgressChanged(view, 0)
        prepareRequest()
        requestedUrl = url
        browser =
            // Upstream JCEF has no CefRendering; a headless browser takes the render handler instead
            CefBrowserHeadless(kcefClient!!, url, renderHandler)
                .apply {
                    // NOTE: Without this, we don't seem to be receiving any events
                    createImmediately()
                }
    }
    // ZOUKAN <--

    override fun loadUrl(
        loadUrl: String,
        additionalHttpHeaders: Map<String, String>,
    ) {
        // ZOUKAN -->
        if (postedToLooper { loadUrl(loadUrl, additionalHttpHeaders) }) return
        if (ignoredAfterDestroy("loadUrl")) return
        startPageLoad(loadUrl) {
            initialRequestData = InitialRequestData(additionalHttpHeaders = additionalHttpHeaders)
        }
        // ZOUKAN <--
        Log.d(TAG, "Page loaded at URL $loadUrl")
    }

    override fun loadUrl(url: String) {
        loadUrl(url, mapOf())
    }

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        // ZOUKAN -->
        if (postedToLooper { postUrl(url, postData) }) return
        if (ignoredAfterDestroy("postUrl")) return
        startPageLoad(url) {
            initialRequestData = InitialRequestData(myPostData = postData)
        }
        // ZOUKAN <--
        Log.d(TAG, "Page posted at URL $url")
    }

    override fun loadData(
        data: String,
        mimeType: String?,
        encoding: String?,
    ) {
        loadDataWithBaseURL(null, data, mimeType, encoding, null)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        // ZOUKAN -->
        if (postedToLooper { loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl) }) return
        if (ignoredAfterDestroy("loadDataWithBaseURL")) return
        val url = baseUrl ?: "about:blank"
        startPageLoad(url) {
            urlHttpMapping[url.trimEnd('/')] = data
        }
        // ZOUKAN <--
        Log.d(TAG, "Page loaded from data at base URL $baseUrl")
    }

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>?,
    ) {
        // ZOUKAN -->
        if (postedToLooper { evaluateJavaScript(script, resultCallback) }) return
        if (ignoredAfterDestroy("evaluateJavascript")) return
        val target = browser!!
        jsBridge.evaluateJavaScript(
            script = script,
            evaluate = { normalized, cb -> target.evaluateJavaScript(normalized, cb) },
            onResult = { resultCallback?.onReceiveValue(it) },
        )
        // ZOUKAN <--
    }

    override fun saveWebArchive(filename: String): Unit = throw RuntimeException("Stub!")

    override fun saveWebArchive(
        basename: String,
        autoname: Boolean,
        callback: ValueCallback<String>,
    ): Unit = throw RuntimeException("Stub!")

    override fun stopLoading() {
        // ZOUKAN -->
        if (postedToLooper { stopLoading() }) return
        if (ignoredAfterDestroy("stopLoading")) return
        // ZOUKAN <--
        browser!!.stopLoad()
    }

    override fun reload() {
        // ZOUKAN -->
        if (postedToLooper { reload() }) return
        if (ignoredAfterDestroy("reload")) return
        // ZOUKAN <--
        browser!!.reload()
    }

    // ZOUKAN -->
    override fun canGoBack(): Boolean = if (ignoredAfterDestroy("canGoBack")) false else browser!!.canGoBack()
    // ZOUKAN <--

    override fun goBack() {
        // ZOUKAN -->
        if (postedToLooper { goBack() }) return
        if (ignoredAfterDestroy("goBack")) return
        // ZOUKAN <--
        browser!!.goBack()
    }

    // ZOUKAN -->
    override fun canGoForward(): Boolean = if (ignoredAfterDestroy("canGoForward")) false else browser!!.canGoForward()
    // ZOUKAN <--

    override fun goForward() {
        // ZOUKAN -->
        if (postedToLooper { goForward() }) return
        if (ignoredAfterDestroy("goForward")) return
        // ZOUKAN <--
        browser!!.goForward()
    }

    override fun canGoBackOrForward(steps: Int): Boolean = throw RuntimeException("Stub!")

    override fun goBackOrForward(steps: Int): Unit = throw RuntimeException("Stub!")

    override fun isPrivateBrowsingEnabled(): Boolean = throw RuntimeException("Stub!")

    override fun pageUp(top: Boolean): Boolean = throw RuntimeException("Stub!")

    override fun pageDown(bottom: Boolean): Boolean = throw RuntimeException("Stub!")

    // ZOUKAN -->
    // Windowless pages are not painted, so the visual state is ready once queued work has run
    override fun insertVisualStateCallback(
        requestId: Long,
        callback: VisualStateCallback,
    ) {
        handler.post { callback.onComplete(requestId) }
    }
    // ZOUKAN <--

    override fun clearView(): Unit = throw RuntimeException("Stub!")

    override fun capturePicture(): Picture = throw RuntimeException("Stub!")

    override fun createPrintDocumentAdapter(documentName: String): PrintDocumentAdapter = throw RuntimeException("Stub!")

    override fun getScale(): Float = throw RuntimeException("Stub!")

    override fun setInitialScale(scaleInPercent: Int): Unit = throw RuntimeException("Stub!")

    override fun invokeZoomPicker(): Unit = throw RuntimeException("Stub!")

    override fun getHitTestResult(): HitTestResult = throw RuntimeException("Stub!")

    override fun requestFocusNodeHref(hrefMsg: Message): Unit = throw RuntimeException("Stub!")

    override fun requestImageRef(msg: Message): Unit = throw RuntimeException("Stub!")

    // ZOUKAN -->
    // Null once destroyed or before the first load, as on Android
    override fun getUrl(): String? = if (destroyed) null else browser?.url

    override fun getOriginalUrl(): String? = if (destroyed) null else browser?.url
    // ZOUKAN <--

    override fun getTitle(): String = throw RuntimeException("Stub!")

    override fun getFavicon(): Bitmap = throw RuntimeException("Stub!")

    override fun getTouchIconUrl(): String = throw RuntimeException("Stub!")

    override fun getProgress(): Int = throw RuntimeException("Stub!")

    override fun getContentHeight(): Int = throw RuntimeException("Stub!")

    override fun getContentWidth(): Int = throw RuntimeException("Stub!")

    override fun pauseTimers() {
        Log.v(TAG, "pauseTimers: doing nothing")
    }

    override fun resumeTimers() {
        Log.v(TAG, "resumeTimers: doing nothing")
    }

    override fun onPause() {
        Log.v(TAG, "onPause: doing nothing")
    }

    override fun onResume() {
        Log.v(TAG, "onResume: doing nothing")
    }

    override fun isPaused(): Boolean = false

    override fun freeMemory(): Unit = throw RuntimeException("Stub!")

    override fun clearCache(includeDiskFiles: Boolean): Unit = throw RuntimeException("Stub!")

    override fun clearFormData(): Unit = throw RuntimeException("Stub!")

    override fun clearHistory(): Unit = throw RuntimeException("Stub!")

    override fun clearSslPreferences(): Unit = throw RuntimeException("Stub!")

    override fun copyBackForwardList(): WebBackForwardList = throw RuntimeException("Stub!")

    override fun setFindListener(listener: WebView.FindListener): Unit = throw RuntimeException("Stub!")

    override fun findNext(forward: Boolean): Unit = throw RuntimeException("Stub!")

    override fun findAll(find: String): Int = throw RuntimeException("Stub!")

    override fun findAllAsync(find: String): Unit = throw RuntimeException("Stub!")

    override fun showFindDialog(
        text: String,
        showIme: Boolean,
    ): Boolean = throw RuntimeException("Stub!")

    override fun clearMatches(): Unit = throw RuntimeException("Stub!")

    override fun documentHasImages(response: Message): Unit = throw RuntimeException("Stub!")

    override fun setWebViewClient(client: WebViewClient) {
        viewClient = client
    }

    override fun getWebViewClient(): WebViewClient = viewClient

    override fun getWebViewRenderProcess(): WebViewRenderProcess? = throw RuntimeException("Stub!")

    override fun setWebViewRenderProcessClient(
        executor: Executor?,
        client: WebViewRenderProcessClient?,
    ): Unit = throw RuntimeException("Stub!")

    override fun getWebViewRenderProcessClient(): WebViewRenderProcessClient? = throw RuntimeException("Stub!")

    override fun setDownloadListener(listener: DownloadListener): Unit = throw RuntimeException("Stub!")

    override fun setWebChromeClient(client: WebChromeClient) {
        chromeClient = client
    }

    override fun getWebChromeClient(): WebChromeClient = chromeClient

    @Suppress("DEPRECATION")
    override fun setPictureListener(listener: PictureListener): Unit = throw RuntimeException("Stub!")

    // ZOUKAN -->
    /**
     * Public adapter kept for cross-module callers and tests; delegates chunk reassembly to
     * [KcefCallChunkAssembler].
     */
    class CallChunkAssembler(
        chunkChars: Int,
        maxCallChars: Int,
        maxPending: Int,
        maxPendingChars: Int,
    ) {
        private val delegate = KcefCallChunkAssembler(chunkChars, maxCallChars, maxPending, maxPendingChars)

        val pendingChars: Long
            get() = delegate.pendingChars

        fun add(
            chunkId: String,
            index: Int,
            total: Int,
            data: String,
        ): String? = delegate.add(chunkId, index, total, data)

        fun clear() = delegate.clear()
    }
    // ZOUKAN <--

    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        // ZOUKAN -->
        if (postedToLooper { addJavascriptInterface(obj, interfaceName) }) return
        jsBridge.addJavascriptInterface(obj, interfaceName)
        // ZOUKAN <--
    }

    override fun removeJavascriptInterface(interfaceName: String) {
        // ZOUKAN -->
        if (postedToLooper { removeJavascriptInterface(interfaceName) }) return
        jsBridge.removeJavascriptInterface(interfaceName)
        // ZOUKAN <--
    }

    override fun createWebMessageChannel(): Array<WebMessagePort> = throw RuntimeException("Stub!")

    override fun postMessageToMainFrame(
        message: WebMessage,
        targetOrigin: Uri,
    ): Unit = throw RuntimeException("Stub!")

    override fun getSettings(): WebSettings = settings

    override fun setMapTrackballToArrowKeys(setMap: Boolean): Unit = throw RuntimeException("Stub!")

    override fun flingScroll(
        vx: Int,
        vy: Int,
    ): Unit = throw RuntimeException("Stub!")

    override fun getZoomControls(): View = throw RuntimeException("Stub!")

    override fun canZoomIn(): Boolean = throw RuntimeException("Stub!")

    override fun canZoomOut(): Boolean = throw RuntimeException("Stub!")

    override fun zoomBy(zoomFactor: Float): Boolean = throw RuntimeException("Stub!")

    override fun zoomIn(): Boolean = throw RuntimeException("Stub!")

    override fun zoomOut(): Boolean = throw RuntimeException("Stub!")

    override fun dumpViewHierarchyWithProperties(
        out: BufferedWriter,
        level: Int,
    ): Unit = throw RuntimeException("Stub!")

    override fun findHierarchyView(
        className: String,
        hashCode: Int,
    ): View = throw RuntimeException("Stub!")

    override fun setRendererPriorityPolicy(
        rendererRequestedPriority: Int,
        waivedWhenNotVisible: Boolean,
    ): Unit = throw RuntimeException("Stub!")

    override fun getRendererRequestedPriority(): Int = throw RuntimeException("Stub!")

    override fun getRendererPriorityWaivedWhenNotVisible(): Boolean = throw RuntimeException("Stub!")

    @SuppressWarnings("unused")
    override fun setTextClassifier(textClassifier: TextClassifier?) {
    }

    override fun getTextClassifier(): TextClassifier = TextClassifier.NO_OP

    // -------------------------------------------------------------------------
    // Provider internal methods
    // -------------------------------------------------------------------------

    override fun getViewDelegate(): ViewDelegate = throw RuntimeException("Stub!")

    // ZOUKAN -->
    /**
     * Runs [block] to the end and returns its result. Extensions create WebViews in
     * withContext(Dispatchers.Main), the AWT event thread on desktop, and JCEF's first start waits
     * for that thread (SwingUtilities.invokeAndWait), so blocking it would deadlock. There it waits
     * in a secondary loop, which keeps dispatching AWT events.
     */
    private fun <T> blockingOffEdt(block: suspend () -> T): T {
        if (!java.awt.EventQueue.isDispatchThread()) return runBlocking { block() }
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>?>(null)
        val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        kotlin.concurrent.thread(name = "kcef-client", isDaemon = true) {
            result.set(runCatching { runBlocking { block() } })
            loop.exit()
        }
        // enter() returns at once when exit() came first
        while (result.get() == null) loop.enter()
        return result.get()!!.getOrThrow()
    }

    /**
     * Sends a WebView touch event to the page as a real touch, through the DevTools protocol, so the
     * page gets trusted touch and pointer events with pointerType "touch" (and the click a tap
     * makes). Sites that load content only after a finger's tap ignore mouse events. The display
     * density is 1, so view pixels are CSS pixels.
     */
    fun dispatchTouch(event: MotionEvent): Boolean = KcefDevTools.dispatchTouch(browser, event)
    // ZOUKAN <--

    override fun getScrollDelegate(): ScrollDelegate = throw RuntimeException("Stub!")

    override fun notifyFindDialogDismissed(): Unit = throw RuntimeException("Stub!")

    // -------------------------------------------------------------------------
    // View / ViewGroup delegation methods
    // -------------------------------------------------------------------------

    class KcefViewDelegate : ViewDelegate {
        override fun shouldDelayChildPressedState(): Boolean = throw RuntimeException("Stub!")

        override fun onProvideVirtualStructure(structure: android.view.ViewStructure): Unit = throw RuntimeException("Stub!")

        override fun onProvideAutofillVirtualStructure(
            @SuppressWarnings("unused") structure: android.view.ViewStructure,
            @SuppressWarnings("unused") flags: Int,
        ) {
        }

        override fun autofill(
            @SuppressWarnings("unused") values: SparseArray<AutofillValue>,
        ) {
        }

        override fun isVisibleToUserForAutofill(
            @SuppressWarnings("unused") virtualId: Int,
        ): Boolean {
            return true // true is the default value returned by View.isVisibleToUserForAutofill()
        }

        override fun onProvideContentCaptureStructure(
            @SuppressWarnings("unused") structure: android.view.ViewStructure,
            @SuppressWarnings("unused") flags: Int,
        ) {
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = throw RuntimeException("Stub!")

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo): Unit = throw RuntimeException("Stub!")

        override fun onInitializeAccessibilityEvent(event: AccessibilityEvent): Unit = throw RuntimeException("Stub!")

        override fun performAccessibilityAction(
            action: Int,
            arguments: Bundle,
        ): Boolean = throw RuntimeException("Stub!")

        override fun setOverScrollMode(mode: Int): Unit = throw RuntimeException("Stub!")

        override fun setScrollBarStyle(style: Int): Unit = throw RuntimeException("Stub!")

        override fun onDrawVerticalScrollBar(
            canvas: Canvas,
            scrollBar: Drawable,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onOverScrolled(
            scrollX: Int,
            scrollY: Int,
            clampedX: Boolean,
            clampedY: Boolean,
        ): Unit = throw RuntimeException("Stub!")

        override fun onWindowVisibilityChanged(visibility: Int): Unit = throw RuntimeException("Stub!")

        override fun onDraw(canvas: Canvas): Unit = throw RuntimeException("Stub!")

        override fun setLayoutParams(layoutParams: LayoutParams): Unit = throw RuntimeException("Stub!")

        override fun performLongClick(): Boolean = throw RuntimeException("Stub!")

        override fun onConfigurationChanged(newConfig: Configuration): Unit = throw RuntimeException("Stub!")

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection = throw RuntimeException("Stub!")

        override fun onDragEvent(event: DragEvent): Boolean = throw RuntimeException("Stub!")

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onKeyUp(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onAttachedToWindow(): Unit = throw RuntimeException("Stub!")

        override fun onDetachedFromWindow(): Unit = throw RuntimeException("Stub!")

        override fun onMovedToDisplay(
            displayId: Int,
            config: Configuration,
        ) {
        }

        override fun onVisibilityChanged(
            changedView: View,
            visibility: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onWindowFocusChanged(hasWindowFocus: Boolean): Unit = throw RuntimeException("Stub!")

        override fun onFocusChanged(
            focused: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect,
        ): Unit = throw RuntimeException("Stub!")

        override fun setFrame(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onSizeChanged(
            w: Int,
            h: Int,
            ow: Int,
            oh: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun onScrollChanged(
            l: Int,
            t: Int,
            oldl: Int,
            oldt: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = throw RuntimeException("Stub!")

        override fun onTouchEvent(ev: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onHoverEvent(event: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onGenericMotionEvent(event: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun onTrackballEvent(ev: MotionEvent): Boolean = throw RuntimeException("Stub!")

        override fun requestFocus(
            direction: Int,
            previouslyFocusedRect: Rect,
        ): Boolean = throw RuntimeException("Stub!")

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ): Unit = throw RuntimeException("Stub!")

        override fun requestChildRectangleOnScreen(
            child: View,
            rect: Rect,
            immediate: Boolean,
        ): Boolean = throw RuntimeException("Stub!")

        override fun setBackgroundColor(color: Int): Unit = throw RuntimeException("Stub!")

        override fun setLayerType(
            layerType: Int,
            paint: Paint,
        ) {
            // ignore
        }

        override fun preDispatchDraw(canvas: Canvas): Unit = throw RuntimeException("Stub!")

        override fun onStartTemporaryDetach(): Unit = throw RuntimeException("Stub!")

        override fun onFinishTemporaryDetach(): Unit = throw RuntimeException("Stub!")

        override fun onActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent,
        ): Unit = throw RuntimeException("Stub!")

        override fun getHandler(originalHandler: Handler): Handler = throw RuntimeException("Stub!")

        override fun findFocus(originalFocusedView: View): View = throw RuntimeException("Stub!")

        @SuppressWarnings("unused")
        override fun onCheckIsTextEditor(): Boolean = false

        @SuppressWarnings("unused")
        override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets? = null

        @SuppressWarnings("unused")
        override fun onResolvePointerIcon(
            event: MotionEvent,
            pointerIndex: Int,
        ): PointerIcon? = null
    }

    class KcefScrollDelegate : ScrollDelegate {
        override fun computeHorizontalScrollRange(): Int = throw RuntimeException("Stub!")

        override fun computeHorizontalScrollOffset(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollRange(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollOffset(): Int = throw RuntimeException("Stub!")

        override fun computeVerticalScrollExtent(): Int = throw RuntimeException("Stub!")

        override fun computeScroll(): Unit = throw RuntimeException("Stub!")
    }
}
