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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
// ZOUKAN -->
import org.cef.browser.CefBrowserHeadless
// ZOUKAN <--
import org.cef.callback.CefCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
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
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.javaMethod

class KcefWebViewProvider(
    private val view: WebView,
) : WebViewProvider {
    private val settings = KcefWebSettings()
    private var viewClient = WebViewClient()
    private var chromeClient = WebChromeClient()
    private val renderHandler = RenderHandler()
    private val mappings: MutableList<FunctionMapping> = mutableListOf()
    private val urlHttpMapping: MutableMap<String, String> = mutableMapOf()
    private var initialRequestData: InitialRequestData? = null

    private var kcefClient: CefClient? = null
    private var browser: CefBrowser? = null

    private val handler = Handler(view.webViewLooper)

    companion object {
        const val TAG = "KcefWebViewProvider"
        const val QUERY_FN = "__\$_suwayomiQuery"
        const val QUERY_CANCEL_FN = "__\$_suwayomiQueryCancel"

        // ZOUKAN: characters per message of a JavaScript-interface call
        private const val CHUNK_CHARS = 16_000

        // ZOUKAN -->
        private val pendingDisposals = java.util.concurrent.ConcurrentLinkedQueue<PendingDisposal>()

        /**
         * Disposes the browsers that destroy() left for later. Chromium crashes when it shuts down
         * with browsers still open, so this runs before it does (on the AWT thread).
         */
        @JvmStatic
        fun disposePending() {
            // WebViews an extension never destroyed too
            synchronized(liveProviders) { liveProviders.toList() }.forEach { runCatching { it.destroy() } }
            while (true) pendingDisposals.poll()?.run() ?: break
        }

        // Providers with a browser; weak, so a WebView dropped without destroy() is still collected
        private val liveProviders: MutableSet<KcefWebViewProvider> =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))
        // ZOUKAN <--

        private val initHandler: InitBrowserHandler /* ZOUKAN --> */by injectLazy()/* ZOUKAN <-- */
    }

    interface InitBrowserHandler {
        fun init(provider: KcefWebViewProvider): Unit
    }

    private inner class LifeSpanHandler : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(browser: CefBrowser) {
            val ua = settings.userAgentString
            val args =
                settings.getUserAgentMetadata()?.let {
                    Log.d(TAG, "Using user-agent $ua with metadata $it")
                    """
                {
                    "userAgent": ${Json.encodeToString(ua)},
                    "userAgentMetadata": ${Json.encodeToString(it)}
                }
                """
                } ?: run {
                    Log.d(TAG, "Using user-agent $ua without metadata")
                    """
                {
                    "userAgent": ${Json.encodeToString(ua)}
                }
                """
                }
            browser.devToolsClient.executeDevToolsMethod("Emulation.setUserAgentOverride", args).whenComplete { res, ex ->
                if (ex != null) {
                    Log.e(TAG, "Failed to set user agent", ex)
                } else {
                    Log.v(TAG, "Successfully set user agent: $res")
                }
            }
            // ZOUKAN -->
            enableTouch(browser)
            // ZOUKAN <--
        }
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

    private class DisplayHandler : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser,
            level: CefSettings.LogSeverity,
            message: String,
            source: String,
            line: Int,
        ): Boolean {
            Log.v(TAG, "$source:$line[$level]: $message")
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
                // Let the app see cookies the page set (cf_clearance) before the client checks them
                CefCookieSync.copyFromCef(url)
                // ZOUKAN <--
                viewClient.onPageFinished(view, url)
                chromeClient.onProgressChanged(view, 100)
            }
        }

        override fun onLoadError(
            browser: CefBrowser,
            frame: CefFrame,
            errorCode: CefLoadHandler.ErrorCode,
            errorText: String,
            failedUrl: String,
        ) {
            Log.w(TAG, "Load error ($failedUrl) [$errorCode]: $errorText")
            // TODO: translate correctly
            // ZOUKAN: read the URL now; the frame can be gone when the main looper runs this
            val frameUrl = frame.url ?: failedUrl ?: ""
            handler.post {
                viewClient.onReceivedError(view, WebViewClient.ERROR_UNKNOWN, errorText, frameUrl)
            }
        }

        override fun onLoadStart(
            browser: CefBrowser,
            frame: CefFrame,
            transitionType: CefRequest.TransitionType,
        ) {
            Log.v(TAG, "Load start, pushing mappings")
            mappings.forEach {
                val js =
                    """
                        window.${it.interfaceName} = window.${it.interfaceName} || {}
                        window.${it.interfaceName}.${it.functionName} = async function() {
                            const args = await Promise.all(Array.from(arguments));
                            // ZOUKAN -->
                            // ASCII only (\uXXXX escapes), and in chunks: a long call (Comix's
                            // chapter list) reached Kotlin cut short, and sent whole crashed Chromium
                            const request = JSON.stringify({
                                functionName: ${Json.encodeToString(it.functionName)},
                                interfaceName: ${Json.encodeToString(it.interfaceName)},
                                args,
                            }).replace(/[\u007f-\uffff]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
                            const query = r => new Promise((resolve, reject) => {
                                window.${QUERY_FN}({
                                    request: r,
                                    persistent: false,
                                    onSuccess: resolve,
                                    onFailure: (_, err) => reject(err),
                                })
                            });
                            const size = $CHUNK_CHARS;
                            if (request.length <= size) return query(request);
                            const id = Math.random().toString(36).slice(2) + Date.now().toString(36);
                            const total = Math.ceil(request.length / size);
                            let result;
                            for (let i = 0; i < total; i++) {
                                result = await query(JSON.stringify({ chunkId: id, index: i, total, data: request.slice(i * size, (i + 1) * size) }));
                            }
                            return result;
                            // ZOUKAN <--
                        }
                        """
                browser.executeJavaScript(js, "SUWAYOMI ${it.toNice()}", 0)
            }

            // ZOUKAN: read the URL now; the frame can be gone when the main looper runs this
            val frameUrl = frame.url ?: ""
            handler.post { viewClient.onPageStarted(view, frameUrl, null) }
        }
    }

    private inner class MessageRouterHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            // ZOUKAN -->
            val whole = if (request.startsWith("{\"chunkId\"")) {
                val chunk = try {
                    Json.decodeFromString<CallChunk>(request)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid request chunk received $e")
                    return false
                }
                val parts = callChunks.computeIfAbsent(chunk.chunkId) { arrayOfNulls(chunk.total) }
                if (chunk.total != parts.size || chunk.index !in parts.indices) return false
                parts[chunk.index] = chunk.data
                if (parts.any { it == null }) {
                    callback.success("")
                    return true
                }
                callChunks.remove(chunk.chunkId)
                parts.joinToString("")
            } else {
                request
            }
            // ZOUKAN <--
            val invoke =
                try {
                    Json.decodeFromString<FunctionCall>(/* ZOUKAN */ whole)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid request received (${whole.length} chars) $e")
                    return false
                }
            // TODO: Use a map
            mappings
                .find {
                    it.functionName == invoke.functionName &&
                        it.interfaceName == invoke.interfaceName
                }?.let {
                    handler.post {
                        try {
                            Log.v(
                                TAG,
                                "Received request to invoke ${it.toNice()} with ${invoke.args.size} args",
                            )
                            // NOTE: first argument is
                            // implicitly this
                            val retval = it.fn.call(it.obj, *invoke.args)
                            callback.success(retval.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "JS-invoke on ${it.toNice()} failed:", e)
                            callback.failure(0, e.message)
                        }
                    }
                    return true
                }
            return false
        }
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

    private inner class ResourceRequestHandler : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): Boolean {
            initialRequestData?.apply(request)
            initialRequestData = null

            // TODO: we should be calling this on the handler, since CEF calls us on its IO thread
            // thus if a client tried to use WebView#loadUrl as the docs suggest, this fails
            val cancel =
                viewClient.shouldOverrideUrlLoading(
                    view,
                    CefWebResourceRequest(request, frame, false),
                )
            Log.v(TAG, "Resource ${request.url}, result is cancel? $cancel")

            // ZOUKAN: read the URL now; the frame can be gone when the main looper runs this
            val frameUrl = frame?.url ?: ""
            handler.post { viewClient.onLoadResource(view, frameUrl) }

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
        ): CefResourceRequestHandler = ResourceRequestHandler()

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

    private class RenderHandler : CefRenderHandlerAdapter() {
        override fun getViewRect(browser: CefBrowser): Rectangle = Rectangle(0, 0, 1280, 2856)

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
            // ZOUKAN: blockingOffEdt, not runBlocking: Chromium's start needs the AWT thread
            blockingOffEdt {
                CefHelper.createClient().apply {
                    addLifeSpanHandler(LifeSpanHandler())
                    addDisplayHandler(DisplayHandler())
                    addLoadHandler(LoadHandler())
                    addRequestHandler(RequestHandler())
                    // ZOUKAN: no addPermissionHandler(PermissionHandler()), see CefPermissionRequest

                    val config = CefMessageRouter.CefMessageRouterConfig()
                    config.jsQueryFunction = QUERY_FN
                    config.jsCancelFunction = QUERY_CANCEL_FN
                    addMessageRouter(CefMessageRouter.create(config, MessageRouterHandler()))
                }
            }
        initHandler.init(this)
        liveProviders += this // ZOUKAN
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
        // Disposed after the main looper's queued work: a page's call into a Java object runs
        // there and answers the page (callback.success) after the method returns, and a method
        // that ends the run (Comix's payload) has the extension destroy the WebView meanwhile.
        // Answering on a disposed browser crashed Chromium. Disposal itself stays on the AWT
        // thread, where JCEF does its work.
        val oldBrowser = browser
        val oldClient = kcefClient
        browser = null
        kcefClient = null
        // Chunks of a call the page never finished go with the page
        callChunks.clear()
        liveProviders.remove(this)
        if (oldBrowser == null && oldClient == null) return
        val disposal = PendingDisposal(oldBrowser, oldClient)
        pendingDisposals += disposal
        handler.post { javax.swing.SwingUtilities.invokeLater { disposal.run() } }
        // ZOUKAN <--
    }

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

    override fun loadUrl(
        loadUrl: String,
        additionalHttpHeaders: Map<String, String>,
    ) {
        browser?.dispose()
        chromeClient.onProgressChanged(view, 0)
        initialRequestData = InitialRequestData(additionalHttpHeaders = additionalHttpHeaders)
        browser =
            // ZOUKAN -->
            // Upstream JCEF has no CefRendering; a headless browser takes the render handler instead
            CefBrowserHeadless(kcefClient!!, loadUrl, renderHandler)
                // ZOUKAN <--
                .apply {
                    // NOTE: Without this, we don't seem to be receiving any events
                    createImmediately()
                }
        Log.d(TAG, "Page loaded at URL $loadUrl")
    }

    override fun loadUrl(url: String) {
        loadUrl(url, mapOf())
    }

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        browser?.dispose()
        chromeClient.onProgressChanged(view, 0)
        initialRequestData = InitialRequestData(myPostData = postData)
        browser =
            // ZOUKAN -->
            // Upstream JCEF has no CefRendering; a headless browser takes the render handler instead
            CefBrowserHeadless(kcefClient!!, url, renderHandler)
                // ZOUKAN <--
                .apply {
                    // NOTE: Without this, we don't seem to be receiving any events
                    createImmediately()
                }
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
        browser?.dispose()
        chromeClient.onProgressChanged(view, 0)
        val url = baseUrl ?: "about:blank"
        urlHttpMapping[url.trimEnd('/')] = data

        browser =
            // ZOUKAN -->
            // Upstream JCEF has no CefRendering; a headless browser takes the render handler instead
            CefBrowserHeadless(kcefClient!!, url, renderHandler)
                // ZOUKAN <--
                .apply {
                    // NOTE: Without this, we don't seem to be receiving any events
                    createImmediately()
                }
        Log.d(TAG, "Page loaded from data at base URL $baseUrl")
    }

    override fun evaluateJavaScript(
        script: String,
        resultCallback: ValueCallback<String>?,
    ) {
        browser!!.evaluateJavaScript(script.removePrefix("javascript:")) {
            Log.v(TAG, "JS returned: $it")
            handler.post { resultCallback?.onReceiveValue(it) }
        }
    }

    override fun saveWebArchive(filename: String): Unit = throw RuntimeException("Stub!")

    override fun saveWebArchive(
        basename: String,
        autoname: Boolean,
        callback: ValueCallback<String>,
    ): Unit = throw RuntimeException("Stub!")

    override fun stopLoading() {
        browser!!.stopLoad()
    }

    override fun reload() {
        browser!!.reload()
    }

    override fun canGoBack(): Boolean = browser!!.canGoBack()

    override fun goBack() {
        browser!!.goBack()
    }

    override fun canGoForward(): Boolean = browser!!.canGoForward()

    override fun goForward() {
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

    override fun getUrl(): String = browser!!.url

    override fun getOriginalUrl(): String = browser!!.url

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
    private class PendingDisposal(private val browser: CefBrowser?, private val client: CefClient?) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)

        fun run() {
            if (!done.compareAndSet(false, true)) return
            pendingDisposals.remove(this)
            runCatching { browser?.dispose() }.onFailure { Log.w(TAG, "Disposing a browser failed", it) }
            runCatching { client?.dispose() }.onFailure { Log.w(TAG, "Disposing a browser client failed", it) }
        }
    }

    /** A piece of a long JavaScript-interface call; see onLoadStart. */
    @Serializable
    private data class CallChunk(
        val chunkId: String,
        val index: Int,
        val total: Int,
        val data: String,
    )

    private val callChunks = java.util.concurrent.ConcurrentHashMap<String, Array<String?>>()
    // ZOUKAN <--

    @Serializable
    private data class FunctionCall(
        val interfaceName: String,
        val functionName: String,
        val args: Array<String>,
    )

    private data class FunctionMapping(
        val interfaceName: String,
        val functionName: String,
        val obj: Any,
        val fn: KFunction<*>,
    ) {
        fun toNice(): String = "$interfaceName.$functionName"
    }

    override fun addJavascriptInterface(
        obj: Any,
        interfaceName: String,
    ) {
        val cls = obj::class
        mappings.addAll(
            cls.declaredMemberFunctions
                // ZOUKAN -->
                // As on Android, a page can call only the public methods marked
                // @JavascriptInterface, not every method of the object
                .filter { isJavascriptInterface(it.javaMethod) }
                // ZOUKAN <--
                .map {
                // This is ridiculous, but necessary, otherwise "public final" throws
                it.javaMethod?.isAccessible = true
                val map = FunctionMapping(interfaceName, it.name, obj, it)
                Log.v(TAG, "Exposing: ${map.toNice()}")
                map
            },
        )
    }

    // ZOUKAN -->
    private fun isJavascriptInterface(method: java.lang.reflect.Method?): Boolean =
        method != null &&
            java.lang.reflect.Modifier.isPublic(method.modifiers) &&
            method.isAnnotationPresent(android.webkit.JavascriptInterface::class.java)
    // ZOUKAN <--

    override fun removeJavascriptInterface(interfaceName: String) {
        val removed = mappings.removeAll { it.interfaceName == interfaceName }
        Log.v(TAG, if (removed) "Removed interface mappings for $interfaceName" else "No interface mappings for $interfaceName to remove")
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
     * makes). Sites that load content only after a finger's tap, Comix's chapter list for one,
     * ignore mouse events. The display density is 1, so view pixels are CSS pixels.
     */
    fun dispatchTouch(event: MotionEvent): Boolean {
        val target = browser ?: return false
        val type = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "touchStart"
            MotionEvent.ACTION_MOVE -> "touchMove"
            MotionEvent.ACTION_UP -> "touchEnd"
            MotionEvent.ACTION_CANCEL -> "touchCancel"
            else -> return false
        }
        val coords = MotionEvent.PointerCoords().also { event.getPointerCoords(0, it) }
        val point = buildJsonObject {
            put("x", event.x.toDouble())
            put("y", event.y.toDouble())
            put("radiusX", (coords.touchMajor / 2).toDouble().coerceAtLeast(1.0))
            put("radiusY", (coords.touchMinor / 2).toDouble().coerceAtLeast(1.0))
            put("force", coords.pressure.toDouble().takeIf { it > 0 } ?: 0.5)
            put("id", 0)
        }
        val params = buildJsonObject {
            put("type", type)
            // touchEnd and touchCancel list the touches still down: none
            put("touchPoints", if (type == "touchStart" || type == "touchMove") JsonArray(listOf(point)) else JsonArray(emptyList()))
        }
        target.devToolsClient.executeDevToolsMethod("Input.dispatchTouchEvent", params.toString()).whenComplete { _, ex ->
            if (ex != null) Log.e(TAG, "Touch event failed", ex)
        }
        return true
    }

    /** Pages see a touch screen, as in an Android WebView, so they accept the touches above. */
    private fun enableTouch(browser: CefBrowser) {
        browser.devToolsClient.executeDevToolsMethod("Emulation.setTouchEmulationEnabled", """{"enabled": true, "maxTouchPoints": 5}""")
            .whenComplete { _, ex -> if (ex != null) Log.e(TAG, "Failed to turn on touch input", ex) }
    }
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
