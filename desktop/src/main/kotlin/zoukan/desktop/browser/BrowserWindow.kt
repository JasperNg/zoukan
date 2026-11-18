package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefJSDialogCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import xyz.nulldev.androidcompat.webkit.CefDownloadBlocker
import xyz.nulldev.androidcompat.webkit.JsDialogLimiter
import xyz.nulldev.androidcompat.webkit.KcefNavigationPolicy
import xyz.nulldev.androidcompat.webkit.evaluateJavaScriptDecoded
import zoukan.desktop.browser.blocking.BlockPolicy
import zoukan.desktop.browser.blocking.ContentBlocker
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The built-in browser: a window with Chromium, an address bar and Back, Forward, Reload and Done
 * buttons. The user solves a Cloudflare challenge or signs in to a site here; cookies the pages
 * set are copied to the app after each page load and when the window closes.
 *
 * It takes the place of Komikku's WebViewActivity, and of the system browser for tracker logins.
 */
class BrowserWindow(
    private val startUrl: String,
    private val message: String? = null,
    /**
     * Sees every navigation of the main frame before it happens, redirects included. Returning true
     * cancels it: tracker logins catch their app-scheme redirect (komikku://anilist-auth) this way.
     */
    private val interceptNavigation: ((String) -> Boolean)? = null,
    /** What the window is for; decides what its pop-ups and the content blocker do (see [Purpose]). */
    private val purpose: Purpose = Purpose.BROWSE,
) {
    /**
     * What a window is for. A Cloudflare check or a sign-in must never be broken by the content
     * blocker, so it is on only for [BROWSE]; a check's window also drops pop-ups to other sites
     * without asking (they are ads, the check never needs one).
     */
    enum class Purpose(internal val blocksContent: Boolean, internal val offersCrossSitePopups: Boolean) {
        /** A source's or a site's page the user opened */
        BROWSE(blocksContent = true, offersCrossSitePopups = true),

        /** A Cloudflare check the app met (see [installChallengeSolver]) */
        CHALLENGE(blocksContent = false, offersCrossSitePopups = false),

        /** A tracker's or a site's sign-in */
        SIGN_IN(blocksContent = false, offersCrossSitePopups = true),
    }

    private val closed = CountDownLatch(1)
    private var frame: JFrame? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null

    // Tracked from birth, so a quit that happens while the window is opening still finds it
    // (cleanUp takes it out again once it is gone, and a failed open() at once)
    // While the app lock is on, the window hides (see [Companion.followAppLock])
    private val registration = registry.register(onLockChange = { SwingUtilities.invokeLater(::applyLock) }) { disposeForQuit() }

    // Whether the window counted itself as open for Discord presence; read and written on the AWT thread
    private var shownForDiscord = false

    // Says that a pop-up to another site was stopped, and opens it here on request; AWT thread only
    private val popupBar by lazy { PopupBar() }

    // Which cookies the window exchanges with the app; none for a sign-in, which keeps them all
    private val cookieScope: BrowserCookieScope? = if (purpose == Purpose.SIGN_IN) null else BrowserCookieScope(startUrl)

    /** Copies the cookies of the page at [url] to the app, when it is on a site the window trusts. */
    private fun copyCookiesFrom(url: String) {
        if (cookieScope != null && !cookieScope.copiesFromChromium(url)) {
            logcat { "The browser keeps the cookies of a page the user did not choose to open" }
            return
        }
        CefCookieSync.copyFromCef(url) { recordClearanceUserAgent(url, null) }
    }

    /**
     * Opens the window. Returns false when there is no display or Chromium cannot start. On every
     * failure, a thrown exception included, the window leaves the registry and its browser is disposed.
     */
    fun open(): Boolean {
        val opened = try {
            openWindow()
        } catch (e: Throwable) {
            abandon()
            throw e
        }
        if (!opened) abandon()
        return opened
    }

    private fun openWindow(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        // Start addresses come from sources and trackers; a page from the web must not be a local
        // file or another scheme. (What the user types in the address bar is theirs.)
        if (!zoukan.desktop.app.ExternalLinks.isWebUrl(startUrl)) {
            logcat(LogPriority.WARN) { "The browser does not open ${startUrl.take(100)}: not a web address" }
            return false
        }
        val app = JcefRuntime.app() ?: return false
        SwingUtilities.invokeAndWait {
            val client = app.createClient().also { client = it }
            // Chromium starts in the first createClient(), so its cookie store exists only now
            // Only the start site's, outside a sign-in (see BrowserCookieScope)
            CefCookieSync.copyToCef(cookieScope?.let { scope -> scope::copiesToChromium })
            val address = JTextField(startUrl)
            client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                    if (frame.isMain) SwingUtilities.invokeLater { address.text = url }
                }

                override fun onTitleChange(browser: CefBrowser, title: String?) {
                    SwingUtilities.invokeLater { this@BrowserWindow.frame?.title = title ?: "Zoukan" }
                }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) copyCookiesFrom(frame.url)
                }
            })
            client.addRequestHandler(requestHandler())
            client.addJSDialogHandler(jsDialogHandler())
            // Nothing from the web is saved to disk
            client.addDownloadHandler(CefDownloadBlocker)
            if (purpose.blocksContent) ContentBlocker.ensureFresh()
            client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean {
                    val opener = runCatching { browser.url }.getOrNull()
                    val action = BrowserPopups.decide(
                        targetUrl,
                        opener,
                        offerCrossSite = purpose.offersCrossSitePopups,
                        isBlockedHost = { purpose.blocksContent && ContentBlocker.isBlockedPopup(it) },
                    )
                    when (action) {
                        is BrowserPopups.Action.LoadHere -> browser.loadURL(action.url)
                        is BrowserPopups.Action.Offer -> SwingUtilities.invokeLater { popupBar.show(action) }
                        BrowserPopups.Action.Drop -> Unit
                    }
                    // Never a native window: it would escape the app lock and the secure screen
                    return true
                }

                override fun onAfterCreated(browser: CefBrowser) {
                    // Chromium's own user agent, never an override: an override leaves
                    // navigator.userAgent, navigator.userAgentData and the Sec-CH-UA headers
                    // disagreeing (the app's default is an Android one), and Cloudflare's Turnstile
                    // fails such a browser once the user clicks the check. A clearance got here is
                    // recorded with Chromium's user agent, which the app then sends to that site.
                    // That is the one Chromium reports here, on the window's own blank page (never
                    // a site's page), the first time a window opens.
                    if (JcefRuntime.userAgentKnown) {
                        browser.loadURL(startUrl)
                    } else {
                        learnUserAgentThenLoad(browser)
                    }
                }
            })
            val browser = client.createBrowser("about:blank", false, false).also { browser = it }

            val back = JButton("Back").apply { addActionListener { browser.goBack() } }
            val forward = JButton("Forward").apply { addActionListener { browser.goForward() } }
            val reload = JButton("Reload").apply { addActionListener { browser.reload() } }
            val done = JButton("Done").apply { addActionListener { close() } }
            address.addActionListener {
                val typed = address.text.trim()
                cookieScope?.userOpened(typed)
                browser.loadURL(typed)
            }

            val toolbar = JPanel(BorderLayout()).apply {
                add(
                    JPanel().apply {
                        add(back)
                        add(forward)
                        add(reload)
                    },
                    BorderLayout.WEST,
                )
                add(address, BorderLayout.CENTER)
                add(JPanel().apply { add(done) }, BorderLayout.EAST)
            }
            val top = JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                message?.let { add(JLabel("  $it"), BorderLayout.CENTER) }
                add(popupBar.panel, BorderLayout.SOUTH)
            }
            popupBar.onOpen = {
                cookieScope?.userOpened(it)
                browser.loadURL(it)
            }
            discord()?.setWebView(true)
            shownForDiscord = true
            frame = JFrame("Zoukan").apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                contentPane.add(top, BorderLayout.NORTH)
                contentPane.add(browser.uiComponent, BorderLayout.CENTER)
                size = Dimension(1100, 800)
                setLocationRelativeTo(null)
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosed(e: WindowEvent) = cleanUp()
                })
                // Settings > Security and privacy > Secure screen, as the main window
                zoukan.desktop.security.SecureScreen.attach(this)
                // Not over a locked app: it shows once the app is unlocked
                if (!registry.locked) {
                    isVisible = true
                    toFront()
                }
            }
        }
        return true
    }

    /**
     * Sees the window's requests: [interceptNavigation] gets each main-frame navigation, and the
     * content blocker each request for something inside a page (in a [Purpose.BROWSE] window).
     */
    private fun requestHandler() = object : CefRequestHandlerAdapter() {
        private val blocker = object : CefResourceRequestHandlerAdapter() {
            override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): Boolean {
                // The page itself, or a request it cannot read, is never blocked
                val type = runCatching { request.resourceType }.getOrNull() ?: return false
                if (type == CefRequest.ResourceType.RT_MAIN_FRAME) return false
                val page = runCatching { browser?.url }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: runCatching { request.firstPartyForCookies }.getOrNull()
                return ContentBlocker.shouldBlockInBrowser(request.url, page)
            }
        }

        override fun onBeforeBrowse(
            browser: CefBrowser,
            frame: CefFrame,
            request: CefRequest,
            userGesture: Boolean,
            isRedirect: Boolean,
        ): Boolean {
            if (!frame.isMain) return false
            val url = runCatching { request.url }.getOrNull()
            // First, so a sign-in still hears of its app-scheme redirect, which is cancelled either way
            if (url != null && interceptNavigation?.invoke(url) == true) return true
            if (!KcefNavigationPolicy.isAllowedMainFrameUrl(url)) {
                logcat(LogPriority.WARN) { "The browser cancelled a navigation to a ${url?.substringBefore(':')?.take(20)}: address" }
                return true
            }
            if (url != null) cookieScope?.navigating(url, userGesture, isRedirect)
            return false
        }

        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler? = blocker.takeIf { purpose.blocksContent }
    }

    /**
     * A page's alert, confirm and prompt show as Chromium's own dialogs, except one from a frame of
     * another origin than the page (an ad's iframe) and those past [JsDialogLimiter]'s limit, which
     * are suppressed. A page never keeps the window from closing or leaving it: onbeforeunload is
     * always answered "leave".
     */
    private fun jsDialogHandler() = object : CefJSDialogHandlerAdapter() {
        private val limiter = JsDialogLimiter()

        override fun onJSDialog(
            browser: CefBrowser,
            originUrl: String?,
            dialogType: CefJSDialogHandler.JSDialogType?,
            messageText: String?,
            defaultPromptText: String?,
            callback: CefJSDialogCallback,
            suppressMessage: BoolRef,
        ): Boolean {
            val page = runCatching { browser.url }.getOrNull()
            if (!limiter.allow(page, originUrl, System.currentTimeMillis())) {
                logcat { "The browser suppressed a page's dialog" }
                suppressMessage.set(true)
            }
            // Chromium's dialog, or none when suppressed
            return false
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

    /** The bar under the toolbar that says a pop-up was stopped; built and used on the AWT thread. */
    private class PopupBar {
        var onOpen: (String) -> Unit = {}
        private var pending: String? = null
        private val label = JLabel()
        val panel: JPanel = JPanel(BorderLayout()).apply {
            isVisible = false
            add(label, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    add(
                        JButton(localized(DMR.strings.open_popup)).apply {
                            addActionListener {
                                val url = pending
                                hide()
                                url?.let { runCatching { onOpen(it) } }
                            }
                        },
                    )
                    add(JButton(localized(tachiyomi.i18n.MR.strings.action_close)).apply { addActionListener { hide() } })
                },
                BorderLayout.EAST,
            )
        }

        fun show(offer: BrowserPopups.Action.Offer) {
            pending = offer.url
            label.text = "  " + localized(DMR.strings.popup_blocked_from, offer.host)
            panel.isVisible = true
            panel.parent?.revalidate()
        }

        private fun hide() {
            pending = null
            panel.isVisible = false
            panel.parent?.revalidate()
        }
    }

    /**
     * Undoes a failed open: disposes whatever it created, on the AWT thread, and takes the window out
     * of the registry. Never throws.
     */
    private fun abandon() {
        val dispose = Runnable {
            runCatching { frame?.dispose() }
            runCatching { cleanUp() }
        }
        // Nothing to dispose when it failed before Chromium (no display, not a web address)
        if (client != null || frame != null) {
            runCatching {
                if (SwingUtilities.isEventDispatchThread()) dispose.run() else SwingUtilities.invokeAndWait(dispose)
            }.onFailure { logcat(LogPriority.WARN, it) { "Cleaning up a browser window that failed to open failed" } }
        }
        // Also when cleanUp could not run
        closed.countDown()
        registry.unregister(registration)
    }

    /**
     * Hides the window while the app is locked and shows it again at the unlock, as the lock covers
     * the main window. Runs on the AWT thread and reads the registry's current state, so lock
     * changes queued out of order still end in the right one.
     */
    private fun applyLock() {
        val frame = frame ?: return
        if (closed.count == 0L) return
        if (registry.locked) {
            frame.isVisible = false
        } else if (!frame.isVisible) {
            frame.isVisible = true
            frame.toFront()
        }
    }

    private fun discord() = runCatching { uy.kohesive.injekt.Injekt.get<zoukan.desktop.discord.DiscordPresence>() }.getOrNull()

    /** Waits until the window closes, [isDone] returns true (the window then closes) or the timeout. */
    fun await(timeoutMs: Long, isDone: () -> Boolean = { false }): Boolean {
        val now = System.currentTimeMillis()
        // Long.MAX_VALUE means no timeout; adding it to the clock would overflow to the past
        val deadline = if (timeoutMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (closed.await(500, TimeUnit.MILLISECONDS)) return isDone()
            // Chromium shut down on its own (Cmd+Q, quit from the Dock, SIGTERM): the window is gone
            if (CefApp.getState() >= CefApp.CefAppState.SHUTTING_DOWN) {
                closed.countDown()
                return isDone()
            }
            if (isDone()) {
                close()
                return true
            }
        }
        close()
        return isDone()
    }

    /**
     * Closes the window and waits briefly until it is gone, so a caller that exits the app next
     * does not race the window's disposal on the AWT thread (that deadlocks Windows' AWT shutdown).
     */
    fun close() {
        SwingUtilities.invokeLater { frame?.dispose() ?: cleanUp() }
        if (!SwingUtilities.isEventDispatchThread()) closed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun cleanUp() {
        if (closed.count == 0L) return
        if (shownForDiscord) {
            shownForDiscord = false
            discord()?.setWebView(false)
        }
        browser?.url?.let { url -> runCatching { copyCookiesFrom(url) } }
        browser?.run {
            setCloseAllowed()
            close(true)
        }
        client?.dispose()
        closed.countDown()
        registry.unregister(registration)
    }

    /**
     * Closes the window and disposes its browser for an app quit (see
     * [Companion.disposeAllForQuit]). On the AWT event thread the cleanup runs here, not via the
     * windowClosed event (which would only be queued for later on the very thread a quit exits
     * from); off it, [close] runs it there with a bounded wait.
     */
    private fun disposeForQuit() {
        if (SwingUtilities.isEventDispatchThread()) {
            frame?.dispose()
            cleanUp()
        } else {
            close()
        }
    }

    /**
     * Asks Chromium for its user agent on the still blank page, then loads [startUrl] exactly once:
     * after the answer, after [USER_AGENT_TIMEOUT_MS] when none comes, or at once when asking fails.
     * An answer that comes after the page started loading is ignored, as it may be the site's.
     */
    private fun learnUserAgentThenLoad(browser: CefBrowser) {
        val started = AtomicBoolean(false)
        fun load(reported: String?, answered: Boolean) {
            if (!started.compareAndSet(false, true)) return
            if (answered) JcefRuntime.learnUserAgent(reported)
            runCatching { browser.loadURL(startUrl) }
                .onFailure { logcat(LogPriority.WARN, it) { "The browser could not load its page" } }
        }
        runCatching {
            CompletableFuture.delayedExecutor(USER_AGENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute { load(null, answered = false) }
            // Reports on Chromium's threads; a failed evaluation reports null
            browser.evaluateJavaScriptDecoded("navigator.userAgent") { load(it, answered = true) }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Could not ask Chromium for its user agent" }
            load(null, answered = false)
        }
    }

    companion object {
        private const val USER_AGENT_TIMEOUT_MS = 3000L
        private const val CHALLENGE_TIMEOUT_MS = 3 * 60 * 1000L
        private const val CLOSE_WAIT_SECONDS = 10L

        /** Every built-in browser window opened and not cleaned up yet. */
        private val registry = BrowserWindowRegistry()

        /**
         * Disposes every live browser window, for the app's quit paths: a browser still open when
         * the JVM's shutdown hook disposes CefApp at exit crashes Chromium (hs_err noise, and a
         * false "closed unexpectedly" warning at the next start). Safe to call from any thread
         * and more than once, and it never throws; off the AWT event thread the wait for each
         * window is bounded by [CLOSE_WAIT_SECONDS], so a wedged AWT thread cannot hang the quit.
         */
        fun disposeAllForQuit() {
            registry.disposeAll()
        }

        /** How many windows are registered, for tests. */
        internal fun registeredCount(): Int = registry.size()

        /**
         * Hides every built-in browser window while the app lock is on, and keeps a Cloudflare
         * challenge from opening one until the unlock (see [installChallengeSolver]). Call once at start.
         */
        fun followAppLock(locked: StateFlow<Boolean>, scope: CoroutineScope) {
            registry.setLocked(locked.value)
            locked.onEach(registry::setLocked).launchIn(scope)
        }

        /** Lets shared network code show a Cloudflare challenge to the user. */
        fun installChallengeSolver() {
            DesktopBrowser.challengeSolver = DesktopBrowser.ChallengeSolver { url, _, isSolved ->
                val started = System.currentTimeMillis()
                // Background traffic can meet a challenge while the app is locked: the window waits
                // for the unlock, within the challenge's usual time, instead of showing a source's
                // site over the lock
                if (!registry.awaitUnlocked(CHALLENGE_TIMEOUT_MS, isSolved)) {
                    isSolved()
                } else {
                    val left = CHALLENGE_TIMEOUT_MS - (System.currentTimeMillis() - started)
                    isSolved() || openChallenge(url, left, isSolved)
                }
            }
        }

        private fun openChallenge(url: String, timeoutMs: Long, isSolved: () -> Boolean): Boolean {
            if (timeoutMs <= 0) return false
            val window = BrowserWindow(
                url,
                message = "This site wants to check you are human. Solve the check; the window closes by itself.",
                purpose = Purpose.CHALLENGE,
            )
            return window.open() && window.await(timeoutMs, isSolved)
        }
    }
}

/**
 * What the built-in browser does with a page's request for a pop-up window. Kept free of
 * Chromium types, so tests can drive it without JCEF.
 */
object BrowserPopups {
    sealed interface Action {
        /** Load the address in the window's own browser. */
        data class LoadHere(val url: String) : Action

        /** Stop it, and tell the user, who may open it here (see BrowserWindow's pop-up bar). */
        data class Offer(val url: String, val host: String) : Action

        /** Stop it without a word. */
        data object Drop : Action
    }

    /**
     * Decides about a pop-up to [targetUrl] opened by the page at [openerUrl]. A pop-up to the same
     * site loads in place (a sign-in or a reader page); one to another site is stopped and, when
     * [offerCrossSite], offered to the user, unless [isBlockedHost] says it goes to an ad host. Any
     * address that is not a web page is dropped. Never throws.
     */
    fun decide(
        targetUrl: String?,
        openerUrl: String?,
        offerCrossSite: Boolean,
        isBlockedHost: (String) -> Boolean = { false },
    ): Action {
        val target = targetUrl?.trim()
        if (target == null || !zoukan.desktop.app.ExternalLinks.isWebUrl(target)) {
            logcat(LogPriority.WARN) { "The browser does not open a pop-up to ${targetUrl?.take(100)}: not a web address" }
            return Action.Drop
        }
        if (runCatching { BlockPolicy.sameSite(target, openerUrl) }.getOrDefault(false)) return Action.LoadHere(target)
        if (!offerCrossSite || runCatching { isBlockedHost(target) }.getOrDefault(false)) {
            logcat { "The browser stopped a pop-up to another site" }
            return Action.Drop
        }
        val host = zoukan.desktop.app.ExternalLinks.webUri(target)?.host ?: return Action.Drop
        return Action.Offer(target, host)
    }
}

/**
 * The live built-in browser windows, as the quit paths see them (see
 * [BrowserWindow.disposeAllForQuit]). Kept free of Chromium types, so tests can drive it without
 * JCEF on the classpath.
 */
class BrowserWindowRegistry {
    /**
     * One live window: [disposer] closes it, and is safe to run more than once; [onLockChange] hears
     * that the app lock went on or off (it reads [locked] for the current state).
     */
    class Registration internal constructor(private val disposer: () -> Unit, private val onLockChange: () -> Unit) {
        internal fun dispose() = disposer()
        internal fun lockChanged() = onLockChange()
    }

    // In the order the windows opened; guarded by itself
    private val registrations = LinkedHashSet<Registration>()

    // Guards the writes of [locked] and wakes [awaitUnlocked]
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lockSignal = java.lang.Object()

    /** Whether the app lock is on: the windows hide, and new ones open hidden. */
    @Volatile
    var locked: Boolean = false
        private set

    /** Puts a window in, to be disposed by [disposeAll] and told of lock changes by [setLocked]. */
    fun register(onLockChange: () -> Unit = {}, disposer: () -> Unit): Registration {
        val registration = Registration(disposer, onLockChange)
        synchronized(registrations) { registrations += registration }
        return registration
    }

    internal fun size(): Int = synchronized(registrations) { registrations.size }

    /**
     * The app lock went on or off: every window hears it, one failing never stopping the others.
     * A window registered meanwhile reads [locked] when it opens.
     */
    fun setLocked(value: Boolean) {
        synchronized(lockSignal) {
            if (locked == value) return
            locked = value
            lockSignal.notifyAll()
        }
        val snapshot = synchronized(registrations) { registrations.toList() }
        snapshot.forEach { runCatching { it.lockChanged() } }
    }

    /**
     * Waits until the app is unlocked, at most [timeoutMs], checking [giveUp] now and then (the
     * challenge was solved some other way meanwhile). Returns whether it is unlocked.
     */
    fun awaitUnlocked(timeoutMs: Long, giveUp: () -> Boolean = { false }): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(0, MAX_WAIT_MS))
        while (true) {
            synchronized(lockSignal) {
                if (!locked) return true
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return false
                lockSignal.wait(left.coerceIn(1, GIVE_UP_CHECK_MS))
            }
            // Outside the lock: it may read the cookie store
            if (locked && giveUp()) return false
        }
    }

    /** Takes a window out again, because it closed by itself. */
    fun unregister(registration: Registration) {
        synchronized(registrations) { registrations -= registration }
    }

    /**
     * Disposes every window in the order they opened, one failing never stopping the others, and
     * forgets them all. The windows are taken out in one step, so one registered meanwhile is kept
     * for the next call, and they are disposed outside the lock: a disposer waits for the AWT
     * thread, which unregisters the window.
     */
    fun disposeAll() {
        val snapshot = synchronized(registrations) { registrations.toList().also { registrations.clear() } }
        snapshot.forEach { runCatching { it.dispose() } }
    }

    private companion object {
        const val GIVE_UP_CHECK_MS = 500L
        const val MAX_WAIT_MS = 24 * 60 * 60 * 1000L
    }
}
