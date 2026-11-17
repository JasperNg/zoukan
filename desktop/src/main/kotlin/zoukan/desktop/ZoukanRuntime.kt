package zoukan.desktop

import android.os.Looper
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import eu.kanade.tachiyomi.network.migrateRemovedDohProvider
import exh.log.EHLogLevel
import logcat.LogPriority
import logcat.LogcatLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.AndroidCompatModule
import xyz.nulldev.androidcompat.ToastSink
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.androidcompat.network.ExtensionHttp
import xyz.nulldev.androidcompat.pm.ExternalPackages
import xyz.nulldev.androidcompat.webkit.KcefWebSettings
import xyz.nulldev.ts.config.CONFIG_PREFIX
import xyz.nulldev.ts.config.ConfigManagerModule
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.Toasts
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.local.Archives
import zoukan.desktop.network.AppDnsResolverProvider
import zoukan.desktop.network.PersistentCookieStore
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.secrets.SecretStore
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.security.Security
import java.util.concurrent.CountDownLatch

/**
 * Starts the desktop runtime: the Android compatibility layer, the app's Injekt module, cookies,
 * Chromium on demand, and the host pieces extensions expect.
 */
object ZoukanRuntime {
    @Volatile
    private var started = false

    @Volatile
    private var failure: Throwable? = null

    lateinit var dirs: ZoukanDirs
        private set

    /** The OS secret store the runtime opened at start; the file cipher's key is in it. */
    lateinit var secrets: SecretStore
        private set

    /**
     * @param interactiveBrowser whether a Cloudflare challenge that the hidden browser cannot solve
     * opens a browser window for the user. Off for unattended runs such as the harness.
     */
    @Synchronized
    fun start(dirs: ZoukanDirs = ZoukanDirs.default(), interactiveBrowser: Boolean = true) {
        // A start that failed stays failed: some steps (the main looper, the Injekt modules) cannot
        // run twice, so a later call reports the first failure instead of returning a half-built runtime
        failure?.let { throw IllegalStateException("Zoukan's runtime failed to start earlier", it) }
        if (started) return
        started = true
        try {
            boot(dirs, interactiveBrowser)
        } catch (e: Throwable) {
            failure = e
            throw e
        }
    }

    private fun boot(requested: ZoukanDirs, interactiveBrowser: Boolean) {
        // Before the log file and the caches open anything: the disposable folders take
        // their `.noindex` names and leave Time Machine on macOS, and the data folder may become 0700
        // (see DataFolderPrivacy.protect for when)
        requested.createRoot()
        DataFolderPrivacy.protect(requested) { note -> requested.recordFolderNote(note) }

        if (!LogcatLogger.isInstalled) {
            val debug = System.getProperty("zoukan.debug") == "true"
            LogcatLogger.install(Slf4jLogcatLogger(if (debug) LogPriority.DEBUG else LogPriority.INFO))
        }
        // The log file, crash reports and the uncaught exception handler (Phase 7), in the data folder
        CrashReporter.install(requested)
        logcat(LogPriority.INFO) {
            "Zoukan ${AppVersion.name} on ${Platform.id}, Java ${System.getProperty("java.runtime.version")}, data in ${requested.root}" +
                if (requested.hasLocalRoot) ", caches in ${requested.localRoot}" else ""
        }
        // On Windows the caches move out of the roaming profile (an older version kept them in the
        // data folder), before anything opens them; one that cannot move is used where it is
        val dirs = CacheFolders.migrate(requested).create()
        this.dirs = dirs
        // The folders this run uses, when the move kept some caches in the data folder (only where
        // the caches have a folder of their own, so never on macOS; protect is safe to repeat)
        if (dirs !== requested) DataFolderPrivacy.protect(dirs) { note -> dirs.recordFolderNote(note) }
        (requested.takeFolderNotes() + dirs.takeFolderNotes()).forEach { note -> logcat(LogPriority.WARN) { note } }

        // FFmpeg's natives (AVIF and HEIF images) unpack into Zoukan's own cache, not ~/.javacpp
        if (System.getProperty("org.bytedeco.javacpp.cachedir") == null) {
            System.setProperty("org.bytedeco.javacpp.cachedir", dirs.javacppCache.absolutePath)
        }

        // AndroidCompat keeps the emulated app's files (SharedPreferences, files, cache) here
        System.setProperty("$CONFIG_PREFIX.server.rootDir", dirs.android.absolutePath)
        dirs.android.resolve("server.conf").writeText(
            """
            |# Written by Zoukan at every start
            |android.app.packageName = "${DesktopAppModule.PACKAGE_NAME}"
            |android.app.debug = false
            |android.system.isDebuggable = false
            |${androidCacheSetting(dirs)}
            """.trimMargin(),
        )

        // Cookies and the preference files (tracker tokens, sync passwords, source logins) are
        // encrypted with a key from the OS secret store. Set before any preference file is opened.
        secrets = SecretStore.forPlatform(dirs.secrets)
        val fileCipher = FileCipher.fromStore(secrets)
        SettingsFileCodec.current = fileCipher

        // One cookie store for OkHttp, android.webkit.CookieManager and (copied) Chromium
        CookieHandler.setDefault(CookieManager(PersistentCookieStore(dirs.cookies, fileCipher), CookiePolicy.ACCEPT_ALL))

        startMainLooper()

        // An extension's android.widget.Toast shows in the window, as the app's own messages
        ToastSink.set(Toasts::show)

        // Android ships Bouncy Castle's ciphers, which some extensions need
        // (Mangago: AES/CBC/ZeroBytePadding)
        Security.addProvider(BouncyCastleProvider())

        val app = ZoukanApplication()
        // The app's PackageInfo, as extensions read it from the package manager: Komikku's version,
        // the one AppInfo reports, so that an extension sees the Android app and not Zoukan
        ExternalPackages.appVersionName = AppInfo.getVersionName()
        ExternalPackages.appVersionCode = AppInfo.getVersionCode().toLong()
        Archives.cacheRoot = dirs.archives
        // ZOUKAN: decrypted temporary files go beside the chapter cache, emptied at every start; the
        // chapter cache from before encryption is cleared (in the background, it may be large)
        AppTempFiles.init(dirs.archives.resolveSibling("tmp"))
        kotlin.concurrent.thread(isDaemon = true, name = "chapter-cache-prepare") {
            runCatching { Archives.prepareCache() }.onFailure { logcat(LogPriority.WARN, it) { "Could not prepare the chapter cache" } }
        }
        // Clients extensions make for themselves (OkHttpClient(), rewritten to ExtensionHttp) resolve
        // names with the app's DNS, over HTTPS when it is on; asked at each lookup, so never too early
        ExtensionHttp.setDnsProvider { Injekt.get<NetworkHelper>().client.dns }
        Injekt.importModule(ConfigManagerModule())
        Injekt.importModule(AndroidCompatModule())
        Injekt.importModule(DesktopAppModule(app, dirs, secrets))
        Injekt.importModule(DesktopDomainModule())

        // Config modules, the Chromium WebView provider and http.agent
        AndroidCompatInitializer().init()
        AndroidCompat().startApp(app)
        EHLogLevel.init(app)
        // Quad101 and Shecan are no longer offered: a stored choice of one moves to Cloudflare (after
        // the config modules above, which the preference store needs; before any client is built)
        Injekt.get<NetworkPreferences>().migrateRemovedDohProvider()
        // The JDK's own lookups (URL.openConnection, java.net.http, InetAddress) go to the app's DNS
        // too (AppDnsResolverProvider); set once the network module is in place
        AppDnsResolverProvider.setDnsProvider { Injekt.get<NetworkHelper>().client.dns }

        JcefRuntime.install(dirs)
        useChromiumUserAgent()
        if (interactiveBrowser) {
            BrowserWindow.installChallengeSolver()
            // ZOUKAN -->
            // The browser windows hide while the app is locked, and a challenge waits for the unlock
            BrowserWindow.followAppLock(
                Injekt.get<zoukan.desktop.security.AppLock>().locked,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
            )
            // ZOUKAN <--
        }
    }

    /**
     * Extensions' WebViews and http.agent (which extensions and the JDK's HttpURLConnection send)
     * use the app's Chromium user agent, so they match the browser that runs the pages and this
     * OS. The WebViews follow it once Chromium reports its own; http.agent keeps the one known now,
     * as the JDK reads it once. Before or without Chromium, [JcefRuntime.install] has already set
     * the one assumed from Chromium's version; never throws.
     */
    private fun useChromiumUserAgent() {
        runCatching {
            KcefWebSettings.userAgentProvider = { DesktopBrowser.userAgent?.takeIf { it.isNotBlank() } }
            DesktopBrowser.userAgent?.takeIf { it.isNotBlank() }?.let { System.setProperty("http.agent", it) }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not set the user agent" } }
    }

    /**
     * The emulated app's cache folder when it is not AndroidCompat's default (`.noindex` on macOS),
     * as a server.conf line; empty otherwise, which leaves server.conf as before.
     */
    internal fun androidCacheSetting(dirs: ZoukanDirs): String {
        if (!dirs.hidesFromSpotlight) return ""
        val path = dirs.androidCache.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        return "android.files.cacheDir = \"$path\""
    }

    /** Like Suwayomi's LooperThread: extensions and the WebView post to Android's main looper. */
    private fun startMainLooper() {
        val ready = CountDownLatch(1)
        Thread({
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            ready.countDown()
            Looper.loop()
        }, "android-main-looper").apply { isDaemon = true }.start()
        ready.await()
    }
}
