package xyz.nulldev.androidcompat.webkit

import android.util.Log
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefJSDialogCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Coordinates ordered disposal of Chromium browsers and clients for WebView providers:
 * - tracks live providers weakly so GC'd providers still get their native handles disposed;
 * - defers disposal until after queued main-looper work on the AWT thread;
 * - drains all pending disposals idempotently at shutdown.
 */
internal class KcefCleanupRegistry<T : Any>(
    private val scheduleAwt: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    private val pendingDisposals = ConcurrentLinkedQueue<PendingDisposal>()
    private val liveNatives: MutableSet<NativeRefs> = ConcurrentHashMap.newKeySet()
    private val liveOwners: MutableSet<T> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val cleaner by lazy { Cleaner.create() }

    internal val pendingCount: Int
        get() = pendingDisposals.size

    internal val liveNativeCount: Int
        get() = liveNatives.size

    internal val liveOwnerCount: Int
        get() = synchronized(liveOwners) { liveOwners.size }

    fun trackOwner(owner: T) {
        liveOwners += owner
    }

    fun untrackOwner(owner: T) {
        liveOwners.remove(owner)
    }

    fun registerCleaner(
        owner: T,
        native: NativeRefs,
    ): Cleaner.Cleanable = cleaner.register(owner, UnreachableCleanup(native, this))

    internal fun addLiveNative(native: NativeRefs) {
        liveNatives += native
    }

    internal fun removeLiveNative(native: NativeRefs) {
        liveNatives.remove(native)
    }

    internal fun removePending(disposal: PendingDisposal) {
        pendingDisposals.remove(disposal)
    }

    internal fun enqueuePending(disposal: PendingDisposal) {
        pendingDisposals += disposal
    }

    /** Enqueues [disposal] and schedules it on the AWT thread after [postToLooper] runs. */
    fun disposeLater(
        postToLooper: (Runnable) -> Boolean,
        disposal: PendingDisposal?,
    ) {
        disposal ?: return
        pendingDisposals += disposal
        postToLooper(Runnable { scheduleAwt(Runnable { disposal.run() }) })
    }

    fun disposeBrowserLater(
        browser: CefBrowser?,
        postToLooper: (Runnable) -> Boolean,
    ) {
        val disposal = browser?.let { PendingDisposal(this, it, null) }
        disposeLater(postToLooper, disposal)
    }

    internal fun scheduleOnAwt(disposal: PendingDisposal) {
        pendingDisposals += disposal
        scheduleAwt(Runnable { disposal.run() })
    }

    /**
     * Destroys every still-live owner and synchronously disposes all queued or unreachable native
     * handles. Intended to run before Chromium shuts down.
     */
    fun disposePending(destroyOwner: (T) -> Unit) {
        synchronized(liveOwners) { liveOwners.toList() }.forEach { owner ->
            runCatching { destroyOwner(owner) }
        }
        liveNatives.toList().forEach { native ->
            native.take()?.let { pendingDisposals += it }
        }
        while (true) {
            pendingDisposals.poll()?.run() ?: break
        }
    }

    companion object {
        internal const val TAG = KcefWebViewProvider.TAG
    }
}

/**
 * Per-provider lifecycle state machine: tracks `destroyed`, cleaner registration, and native
 * browser/client ownership without exposing the provider to the cleaner action.
 */
internal class KcefLifecycleState<T : Any>(
    private val registry: KcefCleanupRegistry<T>,
) {
    val native = NativeRefs(registry)

    @Volatile
    var destroyed: Boolean = false
        private set

    private var cleanable: Cleaner.Cleanable? = null

    fun markInitialized(owner: T) {
        destroyed = false
        if (cleanable == null) {
            cleanable = registry.registerCleaner(owner, native)
        }
        registry.trackOwner(owner)
    }

    fun markDestroyed(
        owner: T,
        postToLooper: (Runnable) -> Boolean,
        onClear: () -> Unit,
    ) {
        destroyed = true
        val disposal = native.take()
        onClear()
        registry.untrackOwner(owner)
        registry.disposeLater(postToLooper, disposal)
    }

    fun ignoredAfterDestroy(call: String): Boolean {
        if (!destroyed) return false
        Log.w(KcefWebViewProvider.TAG, "WebView.$call after destroy() is ignored")
        return true
    }
}

/** Holds the browser and client handles that [UnreachableCleanup] can dispose without referencing the provider. */
internal class NativeRefs(
    private val registry: KcefCleanupRegistry<*>,
) {
    @Volatile
    var browser: CefBrowser? = null
        @Synchronized set(value) {
            field = value
            if (value != null) registry.addLiveNative(this)
        }

    @Volatile
    var client: CefClient? = null
        @Synchronized set(value) {
            field = value
            if (value != null) registry.addLiveNative(this)
        }

    /** Atomically takes both handles out for disposal; returns null when nothing needs disposal. */
    @Synchronized
    fun take(): PendingDisposal? {
        val oldBrowser = browser
        val oldClient = client
        browser = null
        client = null
        registry.removeLiveNative(this)
        return if (oldBrowser == null && oldClient == null) {
            null
        } else {
            PendingDisposal(registry, oldBrowser, oldClient)
        }
    }
}

/**
 * Idempotent one-shot disposal of a browser (first) and its client (second).
 */
internal class PendingDisposal(
    private val registry: KcefCleanupRegistry<*>,
    private val disposeBrowser: (() -> Unit)?,
    private val disposeClient: (() -> Unit)?,
) {
    constructor(
        registry: KcefCleanupRegistry<*>,
        browser: CefBrowser?,
        client: CefClient?,
    ) : this(
        registry = registry,
        disposeBrowser = browser?.let { b -> { b.dispose() } },
        disposeClient = client?.let { c -> { c.dispose() } },
    )

    private val done = AtomicBoolean(false)

    val isDisposed: Boolean
        get() = done.get()

    fun run() {
        if (!done.compareAndSet(false, true)) return
        registry.removePending(this)
        disposeBrowser?.let {
            runCatching(it).onFailure { err -> Log.w(KcefWebViewProvider.TAG, "Disposing a browser failed", err) }
        }
        disposeClient?.let {
            runCatching(it).onFailure { err -> Log.w(KcefWebViewProvider.TAG, "Disposing a browser client failed", err) }
        }
    }
}

/**
 * Cleaner action for a WebView provider dropped without `destroy()`. Must hold no reference to the provider.
 */
internal class UnreachableCleanup(
    private val native: NativeRefs,
    private val registry: KcefCleanupRegistry<*>,
) : Runnable {
    override fun run() {
        val disposal = native.take() ?: return
        Log.w(KcefWebViewProvider.TAG, "A WebView was dropped without destroy(); disposing its browser")
        registry.scheduleOnAwt(disposal)
    }
}

internal class WeakLifeSpanHandler internal constructor(
    private val ref: WeakReference<CefLifeSpanHandlerAdapter>,
) : CefLifeSpanHandlerAdapter() {
    constructor(target: CefLifeSpanHandlerAdapter) : this(WeakReference(target))

    override fun onAfterCreated(browser: CefBrowser) {
        ref.get()?.onAfterCreated(browser)
    }

    // Also once the WebView is gone: no native window either way
    override fun onBeforePopup(
        browser: CefBrowser,
        frame: CefFrame?,
        targetUrl: String?,
        targetFrameName: String?,
    ): Boolean = ref.get()?.onBeforePopup(browser, frame, targetUrl, targetFrameName) ?: true
}

internal class WeakDisplayHandler internal constructor(
    private val ref: WeakReference<CefDisplayHandlerAdapter>,
) : CefDisplayHandlerAdapter() {
    constructor(target: CefDisplayHandlerAdapter) : this(WeakReference(target))

    override fun onConsoleMessage(
        browser: CefBrowser,
        level: CefSettings.LogSeverity,
        message: String,
        source: String,
        line: Int,
    ): Boolean = ref.get()?.onConsoleMessage(browser, level, message, source, line) ?: false

    override fun onAddressChange(
        browser: CefBrowser,
        frame: CefFrame,
        url: String,
    ) {
        ref.get()?.onAddressChange(browser, frame, url)
    }

    override fun onStatusMessage(
        browser: CefBrowser,
        value: String,
    ) {
        ref.get()?.onStatusMessage(browser, value)
    }
}

internal class WeakLoadHandler internal constructor(
    private val ref: WeakReference<CefLoadHandlerAdapter>,
) : CefLoadHandlerAdapter() {
    constructor(target: CefLoadHandlerAdapter) : this(WeakReference(target))

    override fun onLoadEnd(
        browser: CefBrowser,
        frame: CefFrame,
        httpStatusCode: Int,
    ) {
        ref.get()?.onLoadEnd(browser, frame, httpStatusCode)
    }

    override fun onLoadError(
        browser: CefBrowser,
        frame: CefFrame,
        errorCode: CefLoadHandler.ErrorCode,
        errorText: String,
        failedUrl: String,
    ) {
        ref.get()?.onLoadError(browser, frame, errorCode, errorText, failedUrl)
    }

    override fun onLoadStart(
        browser: CefBrowser,
        frame: CefFrame,
        transitionType: CefRequest.TransitionType,
    ) {
        ref.get()?.onLoadStart(browser, frame, transitionType)
    }
}

internal class WeakRequestHandler internal constructor(
    private val ref: WeakReference<CefRequestHandlerAdapter>,
) : CefRequestHandlerAdapter() {
    constructor(target: CefRequestHandlerAdapter) : this(WeakReference(target))

    override fun getResourceRequestHandler(
        browser: CefBrowser,
        frame: CefFrame,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String,
        disableDefaultHandling: BoolRef,
    ): CefResourceRequestHandler? =
        ref.get()?.getResourceRequestHandler(
            browser,
            frame,
            request,
            isNavigation,
            isDownload,
            requestInitiator,
            disableDefaultHandling,
        )

    override fun onRenderProcessTerminated(
        browser: CefBrowser,
        status: CefRequestHandler.TerminationStatus,
        errorCode: Int,
        errorString: String,
    ) {
        ref.get()?.onRenderProcessTerminated(browser, status, errorCode, errorString)
    }

    // Once the WebView is gone, the top frame still goes to web pages only
    override fun onBeforeBrowse(
        browser: CefBrowser,
        frame: CefFrame,
        request: CefRequest,
        userGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean =
        ref.get()?.onBeforeBrowse(browser, frame, request, userGesture, isRedirect)
            ?: (frame.isMain && !KcefNavigationPolicy.isAllowedMainFrameUrl(runCatching { request.url }.getOrNull()))
}

// Once the WebView is gone, a dialog is suppressed and onbeforeunload answered "leave"
internal class WeakJsDialogHandler internal constructor(
    private val ref: WeakReference<CefJSDialogHandlerAdapter>,
) : CefJSDialogHandlerAdapter() {
    constructor(target: CefJSDialogHandlerAdapter) : this(WeakReference(target))

    override fun onJSDialog(
        browser: CefBrowser,
        originUrl: String?,
        dialogType: CefJSDialogHandler.JSDialogType?,
        messageText: String?,
        defaultPromptText: String?,
        callback: CefJSDialogCallback,
        suppressMessage: BoolRef,
    ): Boolean {
        val target = ref.get()
        if (target == null) {
            suppressMessage.set(true)
            return false
        }
        return target.onJSDialog(browser, originUrl, dialogType, messageText, defaultPromptText, callback, suppressMessage)
    }

    override fun onBeforeUnloadDialog(
        browser: CefBrowser,
        messageText: String?,
        isReload: Boolean,
        callback: CefJSDialogCallback,
    ): Boolean {
        ref.get()?.let { return it.onBeforeUnloadDialog(browser, messageText, isReload, callback) }
        runCatching { callback.Continue(true, "") }
        return true
    }
}

internal class WeakMessageRouterHandler internal constructor(
    private val ref: WeakReference<CefMessageRouterHandlerAdapter>,
) : CefMessageRouterHandlerAdapter() {
    constructor(target: CefMessageRouterHandlerAdapter) : this(WeakReference(target))

    override fun onQuery(
        browser: CefBrowser,
        frame: CefFrame,
        queryId: Long,
        request: String,
        persistent: Boolean,
        callback: CefQueryCallback,
    ): Boolean = ref.get()?.onQuery(browser, frame, queryId, request, persistent, callback) ?: false
}
