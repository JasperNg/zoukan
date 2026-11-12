package zoukan.desktop

import android.app.Application
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import mihon.data.extension.service.ExtensionStoreService
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.Database
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.SelfTest
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.backup.BackupCheck
import zoukan.desktop.backup.BackupComparison
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.NoStubSources
import zoukan.desktop.backup.SyntheticBackup
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.harness.CompatibilityHarness
import zoukan.desktop.library.LibraryCommand
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.runZoukanApp
import zoukan.extension.ApkConverter
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.DesktopExtensionManager
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Zoukan desktop

Usage: zoukan [command] [options]

Commands:
  ui                      Open the app (the default when no command is given)
  version                 Print Zoukan's version
  selftest [options]      Check that this installation works, in a throwaway profile
      --report <file.md>    Also write the result as a Markdown report (the installed app has no console on Windows)
      --ui                  Also open the main window for a few seconds
      --network             Also check that GitHub (the update check) can be reached
  status                  Start the runtime, open the encrypted database, load installed extensions
  harness [options]       Run the extension compatibility harness and write a report
      --store <url>         Extension store (default: Keiyoushi)
      --packages <a,b,...>  Extensions to test, for example en.mangapill,all.mangadex
      --all                 Test every extension in the store
      --lang <code>         Source language to test in multi-language extensions (default: en)
      --offline             Install and load only, no calls to manga sites
      --apk                 Install from APKs with dex2jar even when the store has jars
      --skip-nsfw           With --all, skip NSFW extensions
      --interactive         Open a browser window for Cloudflare challenges the hidden browser cannot solve
  backup create [file]    Back up the library and settings (default: <data>/backups/zoukan_<date>.tachibk)
      --private             Include private settings (tracker logins and the like)
  backup restore <file>   Restore a .tachibk backup from Komikku, Mihon, TachiyomiSY or Zoukan
  backup check <file>     Restore a backup into an empty profile, back it up again, and compare the two
      --report <file.md>    Also write the result as a Markdown report
      --keep                Keep the throwaway profile
  backup compare <a> <b>  Compare two backups field by field (what b lost or changed from a)
  backup synthetic <file> Write a backup with every field set, for testing
      --legacy              Use older forms of some fields
  library update [options] Check the library for new chapters, as the app's library update does
      --all-entries         Ignore the skip rules (unread, not started, completed, release period)
      --download <n>        Then download the newest <n> chapters of every updated entry
      --report <file.md>    Also write the result as a Markdown report
  browser <url>           Open the built-in browser (for Cloudflare challenges and site logins)
  convert <apk> <jar>     Convert an extension APK to a jar with dex2jar, for debugging

Only one Zoukan can use a data folder at a time: the commands that start the runtime on it
(status, backup create and restore, library update, browser) refuse while the app is running.

Data folder: -Dzoukan.dataDir=<path> or ZOUKAN_DATA_DIR overrides the default.
"""

fun main(args: Array<String>) {
    // kotlinx-coroutines picks the Android main dispatcher whenever android.os.Build exists, which
    // AndroidCompat provides; the service loader instead finds Swing's, so Dispatchers.Main is the
    // AWT event thread the UI runs on. Set before anything touches Dispatchers.Main.
    System.setProperty("kotlinx.coroutines.fast.service.loader", "false")
    val command = args.firstOrNull() ?: "ui"
    val rest = args.drop(1)
    val code = when (command) {
        "ui" -> {
            try {
                runZoukanApp()
            } catch (e: Throwable) {
                // Starting failed (for example the database would not open): say so and where the
                // crash log is, since the installed app has no console
                CrashReporter.fatal(e)
            }
            0
        }
        "version", "--version" -> {
            println("Zoukan ${AppVersion.name}")
            0
        }
        "selftest" -> selftest(rest)
        "status" -> status()
        "harness" -> harness(rest)
        "backup" -> backup(rest)
        "library" -> library(rest)
        "browser" -> browser(rest)
        "convert" -> convert(rest)
        "help", "--help", "-h" -> {
            println(USAGE)
            0
        }
        else -> {
            System.err.println("Unknown command $command\n\n$USAGE")
            2
        }
    }
    // Chromium crashes when it shuts down (at exit) with a browser still open: the quit paths
    // have disposed the built-in browser windows, and this catches anything left over (a CLI
    // command's window). disposePending then does the same for the extension WebView browsers.
    BrowserWindow.disposeAllForQuit()
    runCatching {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            KcefWebViewProvider.disposePending()
        } else {
            javax.swing.SwingUtilities.invokeAndWait { KcefWebViewProvider.disposePending() }
        }
    }
    exitProcess(code)
}

private fun selftest(args: List<String>): Int {
    var options = SelfTest.Options()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--report" -> options = options.copy(report = File(it.next()))
            "--ui" -> options = options.copy(ui = true)
            "--network" -> options = options.copy(network = true)
            "--keep" -> options = options.copy(keep = true)
            else -> {
                System.err.println("Unknown option $arg\n\n$USAGE")
                return 2
            }
        }
    }
    return SelfTest.run(options)
}

private fun convert(args: List<String>): Int {
    if (args.size != 2) {
        System.err.println("Usage: zoukan convert <apk> <jar>")
        return 2
    }
    ApkConverter.convert(File(args[0]), File(args[1]))
    println("Wrote ${args[1]}")
    return 0
}

private fun status(): Int {
    val lock = lockForDataFolder("status") ?: return 1
    try {
        ZoukanRuntime.start()
        val dirs = ZoukanRuntime.dirs
        println("Data folder: ${dirs.root}")
        println("Secret store: ${Injekt.get<SecretStore>().name}")
        val db = Injekt.get<Database>()
        val stores = runBlocking { db.extension_storeQueries.getAll().executeAsList() }
        println("Encrypted database: open, key \"${DesktopDatabase.KEY_NAME}\" in the secret store, ${stores.size} extension stores")
        val manager = Injekt.get<DesktopExtensionManager>()
        runBlocking { manager.init() }
        println("Extensions: ${manager.installedExtensionsFlow.value.size} loaded, ${manager.untrustedExtensionsFlow.value.size} untrusted, ${manager.loadErrorsFlow.value.size} failed")
        println("Sources: ${Injekt.get<SourceManager>().getAll().size}")
        return 0
    } finally {
        lock.close()
    }
}

private fun harness(args: List<String>): Int {
    var options = CompatibilityHarness.Options()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--store" -> options = options.copy(storeUrl = it.next())
            "--packages" -> options = options.copy(packages = it.next().split(',').map(String::trim).filter(String::isNotEmpty))
            "--all" -> options = options.copy(all = true)
            "--lang" -> options = options.copy(lang = it.next())
            "--offline" -> options = options.copy(offline = true)
            "--apk" -> options = options.copy(forceApk = true)
            "--skip-nsfw" -> options = options.copy(skipNsfw = true)
            "--interactive" -> options = options.copy(interactive = true)
            else -> {
                System.err.println("Unknown option $arg\n\n$USAGE")
                return 2
            }
        }
    }
    // A separate profile, so the harness never touches the user's library or trusted extensions
    val dirs = ZoukanDirs(File(ZoukanDirs.default().root, "harness"))
    ZoukanRuntime.start(dirs, interactiveBrowser = options.interactive)
    val harness = CompatibilityHarness(
        dirs = dirs,
        storeService = Injekt.get<ExtensionStoreService>(),
        installer = Injekt.get<DesktopExtensionInstaller>(),
        loader = Injekt.get<DesktopExtensionLoader>(),
        options = options,
    )
    // NetworkHelper first, so the cookie store and Chromium hooks are in place
    Injekt.get<NetworkHelper>()
    val (report, _) = harness.run()
    val name = buildString {
        append("extensions")
        if (options.forceApk) append("-apk")
        append("-").append(Platform.id)
    }
    val file = File(dirs.reports, "$name.md")
    report.save(file)
    println("\nReport saved to $file")
    return 0
}

private fun browser(args: List<String>): Int {
    val url = args.firstOrNull() ?: run {
        System.err.println("Usage: zoukan browser <url>")
        return 2
    }
    val lock = lockForDataFolder(
        "browser",
        "Use the browser inside the running app, or quit Zoukan and try again.",
    ) ?: return 1
    try {
        ZoukanRuntime.start()
        val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
        val window = BrowserWindow(url, userAgent)
        if (!window.open()) {
            System.err.println("The browser could not open (no display, or Chromium failed to start; see the log)")
            return 1
        }
        window.await(Long.MAX_VALUE)
        return 0
    } finally {
        lock.close()
    }
}

private fun backup(args: List<String>): Int {
    val sub = args.firstOrNull()
    val rest = args.drop(1)
    val flags = rest.filter { it.startsWith("--") }.toSet()
    val positional = rest.filterIndexed { i, a -> !a.startsWith("--") && rest.getOrNull(i - 1) != "--report" }
    return when (sub) {
        "create" -> {
            val lock = lockForDataFolder("backup create") ?: return 1
            try {
                ZoukanRuntime.start()
                val file = positional.firstOrNull()?.let(::File)
                    ?: File(ZoukanRuntime.dirs.backups, DesktopBackups.defaultFileName())
                val backups = DesktopBackups(ZoukanRuntime.dirs.android)
                val backup = runBlocking {
                    backups.create(file, BackupOptions(privateSettings = "--private" in flags))
                }
                println("Backed up ${backup.backupManga.size} entries to $file (${file.length()} bytes)")
                0
            } finally {
                lock.close()
            }
        }
        "restore" -> {
            val file = positional.firstOrNull()?.let(::File) ?: return usage("backup restore <file>")
            val lock = lockForDataFolder("backup restore") ?: return 1
            try {
                ZoukanRuntime.start()
                val backups = DesktopBackups(ZoukanRuntime.dirs.android)
                val backup = backups.read(file)
                var last = 0L
                val errors = runBlocking {
                    backups.restore(backup) { step, progress, total ->
                        val now = System.currentTimeMillis()
                        if (progress == total || now - last > 1000) {
                            last = now
                            val label = when (step) {
                                is BackupContentRestorer.Step.Entry -> step.title
                                is BackupContentRestorer.Step.Section -> Injekt.get<Application>().stringResource(step.label)
                            }
                            println("[$progress/$total] $label")
                        }
                    }
                }
                println("Restored ${backup.backupManga.size} entries from $file")
                if (errors.isNotEmpty()) {
                    println("${errors.size} errors:")
                    errors.forEach { (_, message) -> println("  $message") }
                }
                if (errors.isEmpty()) 0 else 1
            } finally {
                lock.close()
            }
        }
        "check" -> {
            val file = positional.firstOrNull()?.let(::File) ?: return usage("backup check <file>")
            val result = BackupCheck.run(file, keepProfile = "--keep" in flags)
            val markdown = BackupCheck.markdown(result)
            println(markdown)
            rest.indexOf("--report").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }?.let { path ->
                File(path).apply { absoluteFile.parentFile?.mkdirs() }.writeText(markdown)
                println("Report saved to $path")
            }
            if (result.passed) 0 else 1
        }
        "compare" -> {
            if (positional.size != 2) return usage("backup compare <a> <b>")
            val backups = DesktopBackups(File("."), stubSourceRepository = NoStubSources)
            val report = BackupComparison(backups.read(File(positional[0])), backups.read(File(positional[1]))).compare()
            println("Compared ${report.compared}")
            report.differences.forEach { println("- $it") }
            println(if (report.passed) "No differences" else "${report.differences.size} differences")
            if (report.passed) 0 else 1
        }
        "synthetic" -> {
            val file = positional.firstOrNull()?.let(::File) ?: return usage("backup synthetic <file>")
            file.absoluteFile.parentFile?.mkdirs()
            file.sink().use { BackupCodec.write(SyntheticBackup.create(legacy = "--legacy" in flags), it) }
            println("Wrote $file (${file.length()} bytes)")
            0
        }
        else -> usage("backup create|restore|check|synthetic ...")
    }
}

private fun library(args: List<String>): Int {
    if (args.firstOrNull() != "update") return usage("library update [--all-entries] [--download <n>] [--report <file.md>]")
    var options = LibraryCommand.Options()
    val it = args.drop(1).iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--all-entries" -> options = options.copy(ignoreRestrictions = true)
            "--download" -> options = options.copy(download = it.next().toInt())
            "--report" -> options = options.copy(report = File(it.next()))
            else -> {
                System.err.println("Unknown option $arg\n\n$USAGE")
                return 2
            }
        }
    }
    val lock = lockForDataFolder(
        "library update",
        "The running app updates the library on its schedule; quit Zoukan to update from the command line.",
    ) ?: return 1
    try {
        ZoukanRuntime.start(interactiveBrowser = false)
        // NetworkHelper first, so the cookie store and Chromium hooks are in place
        Injekt.get<NetworkHelper>()
        return LibraryCommand.run(options)
    } finally {
        lock.close()
    }
}

/**
 * Takes the app's one-instance lock for [command], which starts the runtime on the user's data
 * folder: a second Chromium on the same cache corrupts it, and both cookie stores would write
 * cookies.json, the last one out overwriting the app's newer cookies. Returns null, having said
 * why on stderr, when Zoukan already runs; the command closes the instance when it is done, so
 * using the commands alone (no app running) still works and frees the folder again.
 */
private fun lockForDataFolder(command: String, hint: String = "Quit Zoukan and try again."): SingleInstance? {
    val instance = SingleInstance.acquireForCommand(ZoukanDirs.default().root)
    if (instance == null) {
        System.err.println("Zoukan is already running; \"zoukan $command\" cannot use the same data folder beside it. $hint")
    }
    return instance
}

private fun usage(text: String): Int {
    System.err.println("Usage: zoukan $text")
    return 2
}
