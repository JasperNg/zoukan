package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.dohProviderHostResolverRule
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.browser.CefRequestContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.CefHelper
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Starts Chromium (JCEF) the first time something needs it: an extension's WebView, a Cloudflare
 * challenge or the browser window.
 *
 * The Chromium build for this platform comes from jcefmaven: about 150 MB, downloaded once from
 * Maven Central (or jcefmaven's GitHub releases) into `<data>/browser`, after its checksum is
 * checked (see [ChromiumNatives]).
 */
object JcefRuntime {
    private val requested = AtomicBoolean(false)

    // Whether this process started Chromium on the data folder (see cleanProfileAtQuit)
    private val started = AtomicBoolean(false)
    private lateinit var dirs: ZoukanDirs

    /** -Dzoukan.cefDebug=true writes a verbose Chromium log to `<data>/browser/chromium.log` */
    private val debugLogging = System.getProperty("zoukan.cefDebug").toBoolean()

    /**
     * The app's own quit (the window and tray's Quit), which macOS's Cmd+Q must run once Chromium
     * has taken it over. It is null only before the windowed app starts and in the command-line
     * tools, where JCEF's own shutdown ends the run; once set it is never set back to null (the
     * app's exit leaves [QUIT_UNDERWAY]).
     */
    @Volatile
    var quitRequest: (() -> Unit)? = null

    /**
     * The [quitRequest] while the app is already quitting. A further Cmd+Q must still answer true,
     * so that JCEF does not shut Chromium down under the exit path; the quit already under way
     * finishes the job.
     */
    internal val QUIT_UNDERWAY: () -> Unit = {}

    // Whether Chromium has reported its user agent (see learnUserAgent)
    private val userAgentLearned = AtomicBoolean(false)

    /**
     * Whether [DesktopBrowser.userAgent] is the one Chromium reported. Until then it is the one
     * [install] assumed from Chromium's version, and a browser window asks Chromium first.
     */
    val userAgentKnown: Boolean get() = userAgentLearned.get()

    /**
     * JCEF's question before it shuts Chromium down for a Cmd+Q: true when the app quits itself,
     * which stops JCEF disposing Chromium under a still running app (a second Cmd+Q then crashed
     * in the shut-down framework).
     */
    internal fun onBeforeTerminate(quit: (() -> Unit)?): Boolean {
        quit ?: return false
        quit()
        return true
    }

    fun install(dirs: ZoukanDirs) {
        this.dirs = dirs
        CefHelper.requestStart = ::requestStart
        DesktopBrowser.userAgent = userAgent()
    }

    /**
     * Takes the user agent Chromium [reported] (`navigator.userAgent` on a blank page of the
     * browser window) as [DesktopBrowser.userAgent], when it is a plausible one; else keeps the
     * assumed one. Never throws: it runs on Chromium's threads.
     */
    fun learnUserAgent(reported: String?) {
        runCatching {
            val assumed = DesktopBrowser.userAgent
            val userAgent = chromiumUserAgent(reported, assumed)
            if (userAgent != assumed) {
                logcat(LogPriority.WARN) { "Chromium's user agent is not the expected one; using Chromium's" }
                DesktopBrowser.userAgent = userAgent
            }
            if (chromiumUserAgent(reported, assumed = null) != null) userAgentLearned.set(true)
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not take Chromium's user agent" } }
    }

    /**
     * [reported] when it looks like a browser's user agent (starts with "Mozilla/", one line),
     * else [assumed]. A failed evaluation reports null, or "null" when it was not decoded.
     */
    internal fun chromiumUserAgent(reported: String?, assumed: String?): String? = reported
        ?.takeIf { it.isNotBlank() && it != "null" && it.startsWith("Mozilla/") && '\n' !in it && '\r' !in it }
        ?: assumed

    /** The user agent Chromium sends by default (Chrome's reduced user agent string). */
    private fun userAgent(): String {
        val major = runCatching { CefBuildInfo.fromClasspath().releaseTag }.getOrNull()
            ?.let { Regex("chromium-(\\d+)").find(it)?.groupValues?.get(1) }
            ?: DEFAULT_CHROME_MAJOR
        val platform = when (Platform.current) {
            Platform.WINDOWS -> "Windows NT 10.0; Win64; x64"
            Platform.MACOS -> "Macintosh; Intel Mac OS X 10_15_7"
            Platform.LINUX -> "X11; Linux x86_64"
        }
        return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private const val DEFAULT_CHROME_MAJOR = "152"

    /**
     * The app, starting Chromium if needed. Blocks until Chromium is ready, so never call it on the
     * AWT thread: there it returns null at once (Chromium's start needs that thread).
     */
    fun app(): CefApp? {
        if (EventQueue.isDispatchThread()) {
            logcat(LogPriority.ERROR, IllegalStateException("Chromium was asked for on the UI thread")) {
                "Chromium is not available on the UI thread; call it from a background thread"
            }
            return null
        }
        return runCatching {
            runBlocking { CefHelper.waitForInit().first() }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Chromium is not available" } }.getOrNull()
    }

    private fun requestStart() {
        if (!requested.compareAndSet(false, true)) return
        // Clears an earlier failure, so callers wait for this attempt instead of failing at once
        CefHelper.cefApp.value = Result.success(null)
        thread(name = "jcef-start", isDaemon = true) {
            val result = runCatching { start() }
                .onFailure { logcat(LogPriority.ERROR, it) { "Starting Chromium failed" } }
            // A failed start (offline while downloading Chromium, say) is tried again by the next
            // caller, instead of leaving the browser unavailable until the app restarts
            if (result.isFailure) requested.set(false)
            CefHelper.cefApp.value = result
        }
    }

    internal fun applySettings(settings: CefSettings, browserCache: java.io.File, browserDir: java.io.File, debugLogging: Boolean) {
        settings.apply {
            // Extensions' WebViews are windowless
            windowless_rendering_enabled = true
            // CEF refuses a cache_path outside root_cache_path
            root_cache_path = browserCache.absolutePath
            cache_path = browserCache.absolutePath
            // Session cookies stay in memory: Chromium's own cookie database is outside FileCipher
            persist_session_cookies = false
            if (debugLogging) log_file = java.io.File(browserDir, "chromium.log").absolutePath
            // No log unless debugging: Chromium's log (chrome_debug.log) is plain text in the
            // profile and holds pages' console messages with their addresses
            log_severity = if (debugLogging) {
                CefSettings.LogSeverity.LOGSEVERITY_VERBOSE
            } else {
                CefSettings.LogSeverity.LOGSEVERITY_DISABLE
            }
        }
    }

    private fun start(): CefApp {
        if (CefApp.getState() != CefApp.CefAppState.NONE) return CefApp.getInstance()
        // Downloads Chromium when it is missing, and installs it only when its checksum is the
        // pinned one; jcefmaven below then finds it installed and downloads nothing itself
        ChromiumNatives.ensureInstalled(dirs.browser) { Injekt.get<NetworkHelper>().client }
        // What an earlier run left of the browser's history and caches (a crash, or files Chromium
        // still held at quit) goes before Chromium opens the profile
        BrowserProfileCleanup.clean(dirs.browserCache)
        started.set(true)
        // Chromium resolves names itself; the DNS over HTTPS preference reaches it through its Local State
        val dohProvider = Injekt.get<NetworkPreferences>().dohProvider().get()
        ChromiumDns.apply(dirs.browserCache, dohProvider)
        val builder = CefAppBuilder().apply {
            setInstallDir(dirs.browser)
            setProgressHandler { state, percent ->
                if (state == EnumProgress.DOWNLOADING && percent >= 0) {
                    logcat { "Downloading Chromium: ${percent.toInt()}%" }
                } else {
                    logcat { "Chromium: $state" }
                }
            }
            applySettings(cefSettings, dirs.browserCache, dirs.browser, debugLogging)
            // Chromium refuses to run as root with its sandbox (Linux containers used for development)
            if (System.getProperty("os.name").startsWith("Linux") && System.getProperty("user.name") == "root") {
                addJcefArgs("--no-sandbox")
            }
            // Chromium looks the DoH server's own name up with the system's DNS; this connects to
            // it at its known address instead, so no name leaves through the system's DNS
            dohProviderHostResolverRule(dohProvider)?.let { addJcefArgs("--host-resolver-rules=$it") }
            addJcefArgs(*PRIVACY_SWITCHES.toTypedArray())
            // Extra Chromium switches for debugging, separated by spaces
            System.getProperty("zoukan.cefArgs")?.split(' ')?.filter { it.isNotBlank() }?.let { addJcefArgs(*it.toTypedArray()) }
            setAppHandler(
                object : MavenCefAppHandlerAdapter() {
                    // On Chromium's UI thread, where its preferences can be set
                    override fun onContextInitialized() = applyPrivacyPreferences()

                    // Once Chromium runs, it takes over macOS's Cmd+Q and calls this on the AWT
                    // thread; false would let JCEF shut Chromium down under a running app (see quitRequest)
                    override fun onBeforeTerminate(): Boolean = onBeforeTerminate(quitRequest)
                },
            )
        }
        val app = builder.build()
        return app
    }

    /**
     * Deletes the browser's history, caches and log at quit (see [BrowserProfileCleanup]), when
     * this process started Chromium. Chromium still runs then, so a file it holds may stay until
     * the next start cleans it. Never throws.
     */
    fun cleanProfileAtQuit() {
        runCatching {
            if (!started.get()) return
            BrowserProfileCleanup.clean(dirs.browserCache)
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not clean the browser's profile at quit" } }
    }

    /**
     * Switches that keep the Chrome runtime's background services from contacting Google (field
     * trial Variations, component and model downloads, Optimization Hints, Domain Reliability
     * reports, hyperlink auditing pings, media router discovery, autofill form signatures, network
     * time queries), and that keep pages' error and policy reports (Reporting, Network Error
     * Logging) from being stored or sent. None of them changes how a page loads or runs: the
     * browser window, WebViews and Cloudflare challenges work as before.
     *
     * The HTTP and media caches are kept as small as Chromium allows, so pages' contents and
     * addresses are not kept on disk in plain text. Chromium takes only one --disable-features,
     * so every feature is in that one switch.
     */
    internal val PRIVACY_SWITCHES = listOf(
        "--disable-background-networking",
        "--disable-component-update",
        "--disable-domain-reliability",
        "--disable-client-side-phishing-detection",
        "--no-pings",
        "--metrics-recording-only",
        "--disable-features=" + listOf(
            "OptimizationHints",
            "OptimizationHintsFetching",
            "OptimizationGuideModelDownloading",
            "MediaRouter",
            "DialMediaRouteProvider",
            "AutofillServerCommunication",
            "Reporting",
            "NetworkErrorLogging",
            "NetworkTimeServiceQuerying",
        ).joinToString(","),
        "--disk-cache-size=1",
        "--media-cache-size=1",
    )

    /**
     * Profile preferences for the same. Safe Browsing would send visited addresses' hashes to
     * Google; history, network prediction (preconnects and prefetches to links not clicked),
     * spellcheck (its dictionary download), password saving and autofill would record or send what
     * the user visits or types; WebRTC would expose the local and public address outside a proxy.
     * A name Chromium does not know is skipped (see [applyPrivacyPreferences]).
     */
    internal val PRIVACY_PREFERENCES: Map<String, Any> = mapOf(
        "safebrowsing.enabled" to false,
        "history.saving_disabled" to true,
        "net.network_prediction_options" to 2,
        "webrtc.ip_handling_policy" to "disable_non_proxied_udp",
        "browser.enable_spellchecking" to false,
        "credentials_enable_service" to false,
        "autofill.profile_enabled" to false,
        "autofill.credit_card_enabled" to false,
    )

    /** Sets [PRIVACY_PREFERENCES] on the global request context. Call on Chromium's UI thread. */
    internal fun applyPrivacyPreferences() {
        runCatching {
            val context = CefRequestContext.getGlobalContext()
            for ((name, value) in PRIVACY_PREFERENCES) {
                if (!context.canSetPreference(name)) {
                    logcat(LogPriority.WARN) { "Chromium has no preference $name" }
                    continue
                }
                context.setPreference(name, value)?.let { error ->
                    logcat(LogPriority.WARN) { "Chromium preference $name: $error" }
                }
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not set Chromium's privacy preferences" } }
    }
}
