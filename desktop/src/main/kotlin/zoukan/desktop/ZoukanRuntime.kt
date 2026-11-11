package zoukan.desktop

import android.os.Looper
import exh.log.EHLogLevel
import logcat.LogPriority
import logcat.LogcatLogger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.androidcompat.AndroidCompatModule
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import xyz.nulldev.ts.config.CONFIG_PREFIX
import xyz.nulldev.ts.config.ConfigManagerModule
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.browser.JcefRuntime
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

    lateinit var dirs: ZoukanDirs
        private set

    /**
     * @param interactiveBrowser whether a Cloudflare challenge that the hidden browser cannot solve
     * opens a browser window for the user. Off for unattended runs such as the harness.
     */
    @Synchronized
    fun start(dirs: ZoukanDirs = ZoukanDirs.default(), interactiveBrowser: Boolean = true) {
        if (started) return
        started = true
        this.dirs = dirs.create()

        if (!LogcatLogger.isInstalled) {
            val debug = System.getProperty("zoukan.debug") == "true"
            LogcatLogger.install(Slf4jLogcatLogger(if (debug) LogPriority.DEBUG else LogPriority.INFO))
        }
        // The log file, crash reports and the uncaught exception handler (Phase 7)
        CrashReporter.install(dirs)
        logcat { "Zoukan ${AppVersion.name} on ${Platform.id}, Java ${System.getProperty("java.runtime.version")}, data in ${dirs.root}" }

        // AndroidCompat keeps the emulated app's files (SharedPreferences, files, cache) here
        System.setProperty("$CONFIG_PREFIX.server.rootDir", dirs.android.absolutePath)
        dirs.android.resolve("server.conf").writeText(
            """
            |# Written by Zoukan at every start
            |android.app.packageName = "${DesktopAppModule.PACKAGE_NAME}"
            |android.app.debug = false
            |android.system.isDebuggable = false
            |
            """.trimMargin(),
        )

        // Cookies and the preference files (tracker tokens, sync passwords, source logins) are
        // encrypted with a key from the OS secret store. Set before any preference file is opened.
        val fileCipher = FileCipher.fromStore(SecretStore.forPlatform(dirs.secrets))
        SettingsFileCodec.current = fileCipher

        // One cookie store for OkHttp, android.webkit.CookieManager and (copied) Chromium
        CookieHandler.setDefault(CookieManager(PersistentCookieStore(dirs.cookies, fileCipher), CookiePolicy.ACCEPT_ALL))

        startMainLooper()

        // Android ships Bouncy Castle's ciphers, which some extensions need
        // (Mangago: AES/CBC/ZeroBytePadding)
        Security.addProvider(BouncyCastleProvider())

        val app = ZoukanApplication()
        Injekt.importModule(ConfigManagerModule())
        Injekt.importModule(AndroidCompatModule())
        Injekt.importModule(DesktopAppModule(app, dirs))
        Injekt.importModule(DesktopDomainModule())

        // Config modules, the Chromium WebView provider and http.agent
        AndroidCompatInitializer().init()
        AndroidCompat().startApp(app)
        EHLogLevel.init(app)

        JcefRuntime.install(dirs)
        if (interactiveBrowser) BrowserWindow.installChallengeSolver()
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
