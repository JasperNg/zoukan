package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val userAgent: String?,
    private val message: String? = null,
    /**
     * Sees every navigation of the main frame before it happens, redirects included. Returning true
     * cancels it: tracker logins catch their app-scheme redirect (komikku://anilist-auth) this way.
     */
    private val interceptNavigation: ((String) -> Boolean)? = null,
) {
    private val closed = CountDownLatch(1)
    private var frame: JFrame? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null

    // Tracked from birth, so a quit that happens while the window is opening still finds it
    // (cleanUp takes it out again once it is gone, and a failed open() at once)
    private val registration = registry.register { disposeForQuit() }

    // Whether the window counted itself as open for Discord presence; read and written on the AWT thread
    private var shownForDiscord = false

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
            CefCookieSync.copyToCef()
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
                    if (frame.isMain) CefCookieSync.copyFromCef(frame.url)
                }
            })
            if (interceptNavigation != null) {
                client.addRequestHandler(object : CefRequestHandlerAdapter() {
                    override fun onBeforeBrowse(
                        browser: CefBrowser,
                        frame: CefFrame,
                        request: CefRequest,
                        userGesture: Boolean,
                        isRedirect: Boolean,
                    ): Boolean = frame.isMain && interceptNavigation.invoke(request.url)
                })
            }
            client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) {
                    // Cloudflare ties cf_clearance to the user agent, so the page must see the app's.
                    // The browser starts blank and loads the page once the user agent is set.
                    if (userAgent == null) {
                        browser.loadURL(startUrl)
                    } else {
                        setUserAgent(browser, userAgent).whenComplete { _, _ -> browser.loadURL(startUrl) }
                    }
                }
            })
            val browser = client.createBrowser("about:blank", false, false).also { browser = it }

            val back = JButton("Back").apply { addActionListener { browser.goBack() } }
            val forward = JButton("Forward").apply { addActionListener { browser.goForward() } }
            val reload = JButton("Reload").apply { addActionListener { browser.reload() } }
            val done = JButton("Done").apply { addActionListener { close() } }
            address.addActionListener { browser.loadURL(address.text.trim()) }

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
                add(toolbar, BorderLayout.CENTER)
                message?.let { add(JLabel("  $it"), BorderLayout.SOUTH) }
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
                isVisible = true
                toFront()
            }
        }
        return true
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

    private fun discord() = runCatching { uy.kohesive.injekt.Injekt.getInstance<zoukan.desktop.discord.DiscordPresence>(zoukan.desktop.discord.DiscordPresence::class.java) }.getOrNull()

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
        browser?.url?.let { runCatching { CefCookieSync.copyFromCef(it) } }
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

    private fun setUserAgent(browser: CefBrowser, userAgent: String): CompletableFuture<*> {
        val params = buildJsonObject { put("userAgent", userAgent) }
        return runCatching {
            browser.devToolsClient.executeDevToolsMethod("Emulation.setUserAgentOverride", Json.encodeToString(params))
        }.getOrElse { CompletableFuture.failedFuture<String>(it) }
            .whenComplete { _, error ->
                if (error != null) logcat(LogPriority.WARN, error) { "Could not set the browser's user agent" }
            }
    }

    companion object {
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

        /** Lets shared network code show a Cloudflare challenge to the user. */
        fun installChallengeSolver() {
            DesktopBrowser.challengeSolver = DesktopBrowser.ChallengeSolver { url, userAgent, isSolved ->
                val window = BrowserWindow(
                    url,
                    userAgent,
                    message = "This site wants to check you are human. Solve the check; the window closes by itself.",
                )
                window.open() && window.await(CHALLENGE_TIMEOUT_MS, isSolved)
            }
        }
    }
}

/**
 * The live built-in browser windows, as the quit paths see them (see
 * [BrowserWindow.disposeAllForQuit]). Kept free of Chromium types, so tests can drive it without
 * JCEF on the classpath.
 */
class BrowserWindowRegistry {
    /** One live window: [disposer] closes it, and is safe to run more than once. */
    class Registration internal constructor(private val disposer: () -> Unit) {
        internal fun dispose() = disposer()
    }

    // In the order the windows opened; guarded by itself
    private val registrations = LinkedHashSet<Registration>()

    /** Puts a window in, to be disposed by [disposeAll]. */
    fun register(disposer: () -> Unit): Registration {
        val registration = Registration(disposer)
        synchronized(registrations) { registrations += registration }
        return registration
    }

    internal fun size(): Int = synchronized(registrations) { registrations.size }

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
}
