package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.NetworkHelper
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
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.CefHelper
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
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
    private lateinit var dirs: ZoukanDirs

    /** -Dzoukan.cefDebug=true writes a verbose Chromium log to `<data>/browser/chromium.log` */
    private val debugLogging = System.getProperty("zoukan.cefDebug").toBoolean()

    fun install(dirs: ZoukanDirs) {
        this.dirs = dirs
        CefHelper.requestStart = ::requestStart
        DesktopBrowser.userAgent = userAgent()
    }

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

    /** The app, starting Chromium if needed. Blocks until Chromium is ready. */
    fun app(): CefApp? = runCatching {
        runBlocking { CefHelper.waitForInit().first() }
    }.onFailure { logcat(LogPriority.ERROR, it) { "Chromium is not available" } }.getOrNull()

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

    private fun start(): CefApp {
        if (CefApp.getState() != CefApp.CefAppState.NONE) return CefApp.getInstance()
        // Downloads Chromium when it is missing, and installs it only when its checksum is the
        // pinned one; jcefmaven below then finds it installed and downloads nothing itself
        ChromiumNatives.ensureInstalled(dirs.browser) { Injekt.get<NetworkHelper>().client }
        val builder = CefAppBuilder().apply {
            setInstallDir(dirs.browser)
            setProgressHandler { state, percent ->
                if (state == EnumProgress.DOWNLOADING && percent >= 0) {
                    logcat { "Downloading Chromium: ${percent.toInt()}%" }
                } else {
                    logcat { "Chromium: $state" }
                }
            }
            cefSettings.apply {
                // Extensions' WebViews are windowless
                windowless_rendering_enabled = true
                // CEF refuses a cache_path outside root_cache_path
                root_cache_path = dirs.browserCache.absolutePath
                cache_path = dirs.browserCache.absolutePath
                persist_session_cookies = true
                if (debugLogging) log_file = java.io.File(dirs.browser, "chromium.log").absolutePath
                log_severity = if (debugLogging) {
                    CefSettings.LogSeverity.LOGSEVERITY_VERBOSE
                } else {
                    CefSettings.LogSeverity.LOGSEVERITY_WARNING
                }
            }
            // Chromium refuses to run as root with its sandbox (Linux containers used for development)
            if (System.getProperty("os.name").startsWith("Linux") && System.getProperty("user.name") == "root") {
                addJcefArgs("--no-sandbox")
            }
            // Extra Chromium switches for debugging, separated by spaces
            System.getProperty("zoukan.cefArgs")?.split(' ')?.filter { it.isNotBlank() }?.let { addJcefArgs(*it.toTypedArray()) }
            setAppHandler(object : MavenCefAppHandlerAdapter() {})
        }
        val app = builder.build()
        return app
    }
}
