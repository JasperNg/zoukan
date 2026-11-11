package zoukan.desktop.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.secrets.MacKeychainStore
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.secrets.WindowsCredentialStore
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.startUi
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.Updates
import zoukan.extension.DesktopExtensionManager
import java.io.File
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

    private data class Check(val name: String, val passed: Boolean, val detail: String)

    /** JDK modules that extensions or libraries are known to use; the packaged runtime has all. */
    private val modules = listOf(
        "java.desktop", "java.sql", "java.naming", "java.net.http", "java.scripting", "java.management",
        "java.xml", "java.logging", "jdk.unsupported", "jdk.zipfs", "jdk.crypto.ec", "jdk.charsets",
        "jdk.localedata", "jdk.accessibility",
    )

    fun run(options: Options): Int {
        val checks = mutableListOf<Check>()
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            checks += Check(name, result.isSuccess, result.getOrElse { "${it::class.simpleName}: ${it.message}" })
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
            val missing = modules.filter { ModuleLayer.boot().findModule(it).isEmpty }
            require(missing.isEmpty()) { "missing ${missing.joinToString()}" }
            "${ModuleLayer.boot().modules().size} modules, including ${modules.size} checked"
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
            check("Update check") {
                // The profile has no GitHub token, so a private repository answers "not found":
                // that still shows HTTPS to GitHub works from the bundled runtime
                when (val result = runBlocking { Updates.checker.check() }) {
                    UpdateChecker.Result.UpToDate -> "GitHub reached, up to date"
                    is UpdateChecker.Result.Available -> "GitHub reached, ${result.release.name} available (${result.release.asset?.name ?: "no installer"})"
                    is UpdateChecker.Result.Failed -> {
                        require(!result.message.startsWith("Could not reach")) { result.message }
                        "GitHub reached: ${result.message}"
                    }
                }
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

        val passed = checks.all { it.passed }
        val markdown = buildString {
            appendLine("# Zoukan self-test: ${if (passed) "passed" else "FAILED"}")
            appendLine()
            appendLine("${Platform.id}, ${java.time.LocalDateTime.now()}")
            appendLine()
            appendLine("| Check | Result | Detail |")
            appendLine("|---|---|---|")
            checks.forEach { appendLine("| ${it.name} | ${if (it.passed) "pass" else "FAIL"} | ${it.detail.replace("|", "\\|").replace("\n", " ")} |") }
        }
        println(markdown)
        options.report?.let {
            it.absoluteFile.parentFile?.mkdirs()
            it.writeText(markdown)
        }
        if (!options.keep) profile.deleteRecursively()
        return if (passed) 0 else 1
    }
}
