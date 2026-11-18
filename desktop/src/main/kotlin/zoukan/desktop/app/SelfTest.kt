package zoukan.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ch.qos.logback.classic.LoggerContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.network.PersistentCookieStore
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.secrets.MacKeychainStore
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.secrets.WindowsCredentialStore
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.startUi
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.Updates
import zoukan.extension.DesktopExtensionManager
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.nio.file.Files
import java.util.UUID

/**
 * `zoukan selftest`: checks that an installed Zoukan works on this machine, for the release checks
 * (RELEASING.md). It uses a throwaway profile, so the user's library is never touched, and writes
 * a Markdown report, since the installed app has no console on Windows.
 *
 * Checked: the bundled runtime and its modules, the OS secret store, the encrypted database,
 * extension loading, crash logs, and with `--ui` the window (Skia's native code), with
 * `--network` the update check.
 */
object SelfTest {
    data class Options(val report: File? = null, val ui: Boolean = false, val network: Boolean = false, val keep: Boolean = false)

    internal enum class Status(val label: String) { PASS("pass"), SKIPPED("skipped"), FAIL("FAIL") }

    internal data class Outcome(val status: Status, val detail: String)

    private data class Check(val name: String, val status: Status, val detail: String)

    /**
     * A GitHub token for the update check, from this environment variable (the install checks set
     * it from CI's GITHUB_TOKEN): the request then has its own rate limit, so the check is real.
     * It is kept in memory only and never written to the report or the log.
     */
    internal const val TOKEN_ENV = "ZOUKAN_SELFTEST_GITHUB_TOKEN"

    /**
     * JDK modules that extensions or libraries are known to use; the packaged runtime has all.
     * When the bundled runtime moves past JDK 21, check this list: `jdk.crypto.ec` is empty and
     * deprecated for removal from JDK 22 on.
     */
    internal val checkedModules = listOf(
        "java.desktop", "java.sql", "java.naming", "java.net.http", "java.scripting", "java.management",
        "java.xml", "java.logging", "jdk.unsupported", "jdk.zipfs", "jdk.crypto.ec", "jdk.charsets",
        "jdk.localedata", "jdk.accessibility",
    )

    fun run(options: Options): Int {
        val checks = mutableListOf<Check>()
        fun checkOutcome(name: String, block: () -> Outcome) {
            val outcome = block()
            checks += Check(name, outcome.status, outcome.detail)
        }
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += Check(name, if (result.isSuccess) Status.PASS else Status.FAIL, result.getOrElse { "${it::class.simpleName}: ${it.message}" })
        }

        val profile = Files.createTempDirectory("zoukan-selftest").toFile()
        // The throwaway profile keeps its database key in a file; the OS store is checked apart
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        System.setProperty("zoukan.noCrashDialog", "true")
        val dirs = ZoukanDirs(profile)

        check("Runtime") {
            val home = File(System.getProperty("java.home"))
            "${System.getProperty("java.vm.vendor")} ${System.getProperty("java.runtime.version")}, ${System.getProperty("os.arch")}, at $home" +
                if (System.getProperty("jpackage.app-version") != null) " (bundled, app ${System.getProperty("jpackage.app-version")})" else ""
        }
        check("JDK modules") {
            val missing = checkedModules.filter { ModuleLayer.boot().findModule(it).isEmpty }
            require(missing.isEmpty()) { "missing ${missing.joinToString()}" }
            "${ModuleLayer.boot().modules().size} modules, including ${checkedModules.size} checked"
        }
        check("Version") { "Zoukan ${AppVersion.name}, releases from ${AppVersion.releaseRepo}" }
        check("OS secret store") {
            val store: SecretStore = when (Platform.current) {
                Platform.WINDOWS -> WindowsCredentialStore()
                Platform.MACOS -> MacKeychainStore()
                Platform.LINUX -> FileSecretStore(File(profile, "os-secrets"))
            }
            val key = "selftest-${UUID.randomUUID()}"
            val value = UUID.randomUUID().toString()
            store.set(key, value)
            try {
                require(store.get(key) == value) { "read back a different value" }
            } finally {
                store.delete(key)
            }
            require(store.get(key) == null) { "the test entry was not deleted" }
            "${store.name}: write, read and delete work"
        }
        check("Start the runtime") {
            ZoukanRuntime.start(dirs, interactiveBrowser = false)
            "profile $profile"
        }
        check("Encrypted database") {
            val db = Injekt.get<Database>()
            val stores = runBlocking { db.extension_storeQueries.getAll().executeAsList() }
            val header = dirs.database.inputStream().use { it.readNBytes(16) }.toString(Charsets.ISO_8859_1)
            require(!header.startsWith("SQLite format 3")) { "the database file is not encrypted" }
            "opened ${dirs.database.name}, ${stores.size} extension stores, file encrypted"
        }
        check("Extensions") {
            val manager = Injekt.get<DesktopExtensionManager>()
            runBlocking { manager.init() }
            "${manager.installedExtensionsFlow.value.size} installed, ${Injekt.get<SourceManager>().getAll().size} sources"
        }
        check("Crash logs") {
            val file = CrashReporter.write(Thread.currentThread(), RuntimeException("self-test, not a real crash"), fatal = false)
                ?: error("crash logs are not set up")
            require(file.readText().contains("self-test")) { "the report is empty" }
            file.delete()
            require(File(dirs.logs, "zoukan.log").isFile) { "no log file" }
            "reports in ${dirs.crashes}, log in ${dirs.logs}"
        }
        if (options.network) {
            checkOutcome("Update check") {
                runCatching {
                    val token = System.getenv(TOKEN_ENV)?.takeIf { it.isNotBlank() }
                    val checker = token
                        ?.let { UpdateChecker(Injekt.get<NetworkHelper>().client, MemorySecretStore(UpdateChecker.TOKEN_KEY, it)) }
                        ?: Updates.checker
                    updateCheckOutcome(runBlocking { checker.check() }, withToken = token != null)
                }.getOrElse { Outcome(Status.FAIL, "${it::class.simpleName}: ${it.message}") }
            }
        }
        if (options.ui) {
            check("Window") {
                startUi(dirs)
                var shown = false
                application(exitProcessOnExit = false) {
                    Window(onCloseRequest = ::exitApplication, title = "Zoukan self-test", state = rememberWindowState(size = DpSize(1000.dp, 700.dp))) {
                        ZoukanContent(Injekt.get<DesktopPreferences>())
                        LaunchedEffect(Unit) {
                            delay(6000)
                            shown = true
                            exitApplication()
                        }
                    }
                }
                require(shown) { "the window closed early" }
                "opened and drew the main window for 6 seconds"
            }
        }

        val passed = checks.none { it.status == Status.FAIL }
        val markdown = buildString {
            appendLine("# Zoukan self-test: ${if (passed) "passed" else "FAILED"}")
            appendLine()
            appendLine("${Platform.id}, ${java.time.LocalDateTime.now()}")
            appendLine()
            appendLine("| Check | Result | Detail |")
            appendLine("|---|---|---|")
            checks.forEach { appendLine("| ${it.name} | ${it.status.label} | ${it.detail.replace("|", "\\|").replace("\n", " ")} |") }
        }
        println(markdown)
        options.report?.let {
            it.absoluteFile.parentFile?.mkdirs()
            it.writeText(markdown)
        }
        if (!options.keep) deleteProfile(profile)
        return if (passed) 0 else 1
    }

    /**
     * How the update check went. A rate limit says nothing about this installation, so it is
     * skipped, not passed. Without a token a private repository answers "not found", which still
     * shows HTTPS to GitHub works from the bundled runtime; with one, any failure fails.
     */
    internal fun updateCheckOutcome(result: UpdateChecker.Result, withToken: Boolean): Outcome {
        val how = if (withToken) " (with a token)" else ""
        return when (result) {
            UpdateChecker.Result.UpToDate -> Outcome(Status.PASS, "GitHub reached$how, up to date")
            is UpdateChecker.Result.Available ->
                Outcome(Status.PASS, "GitHub reached$how, ${result.release.name} available (${result.release.asset?.name ?: "no installer"})")
            is UpdateChecker.Result.Failed -> when {
                result.rateLimited -> Outcome(Status.SKIPPED, "skipped (rate limited)$how: ${result.message}")
                withToken || result.message.startsWith("Could not reach") -> Outcome(Status.FAIL, result.message + how)
                else -> Outcome(Status.PASS, "GitHub reached: ${result.message}")
            }
        }
    }

    /** Holds one secret in memory, so a token given for the check never reaches a file. */
    private class MemorySecretStore(private val key: String, private val value: String) : SecretStore {
        override val name = "memory"

        override fun get(key: String): String? = value.takeIf { key == this.key }

        override fun set(key: String, value: String) = Unit

        override fun delete(key: String) = Unit
    }

    /**
     * Deletes the throwaway profile. What would write into it later is stopped first: the cookie
     * store's shutdown save and the settings files' shutdown flush would create the folder again,
     * and on Windows the open log file can't be deleted.
     */
    internal fun deleteProfile(profile: File): Boolean {
        runCatching { ((CookieHandler.getDefault() as? CookieManager)?.cookieStore as? PersistentCookieStore)?.close() }
        runCatching { JavaSharedPreferences.flushPendingSaves() }
        runCatching {
            val root = (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger(Logger.ROOT_LOGGER_NAME)
            root?.getAppender("FILE")?.let {
                root.detachAppender(it)
                it.stop()
            }
        }
        // Never through a link (Chromium's profile has some): only the profile's own files go
        zoukan.desktop.download.SafeDelete.deleteTreeNoFollow(profile.toPath())
        return !Files.exists(profile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    }
}
