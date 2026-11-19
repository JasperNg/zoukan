package zoukan.desktop

import android.app.Application
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.AppShutdown
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.SelfTest
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.library.LibraryCommand
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.runZoukanApp
import zoukan.extension.ApkConverter
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
  backup create [file]    Back up the library and settings (default: <data>/backups/zoukan_<date>.tachibk)
      --private             Include private settings (tracker logins and the like)
  backup restore <file>   Restore a .tachibk backup from Komikku, Mihon, TachiyomiSY or Zoukan
      --private             Restore security settings and logins from the backup too
  library update [options] Check the library for new chapters, as the app's library update does
      --all-entries         Ignore the skip rules (unread, not started, completed, release period)
      --download <n>        Then download the newest <n> chapters of every updated entry
      --report <file.md>    Also write the result as a Markdown report
  browser <url>           Open the built-in browser (for Cloudflare challenges and site logins)
  convert <apk> <jar>     Convert an extension APK to a jar with dex2jar, for debugging

Only one Zoukan can use a data folder at a time: the commands that start the runtime on it
(status, backup create and restore, library update, browser) refuse while the app is running.

Data folder: -Dzoukan.dataDir=<path> or ZOUKAN_DATA_DIR overrides the default.

Exit codes: 0 success, 1 the command failed, 2 bad usage.
"""

fun main(args: Array<String>) = runCliMain(args, ::runCommand)

internal fun runCliMain(args: Array<String>, run: (Array<String>) -> Int) {
    // kotlinx-coroutines picks the Android main dispatcher whenever android.os.Build exists, which
    // AndroidCompat provides; the service loader instead finds Swing's, so Dispatchers.Main is the
    // AWT event thread the UI runs on. Set before anything touches Dispatchers.Main.
    System.setProperty("kotlinx.coroutines.fast.service.loader", "false")
    val code = run(args)
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
    // The built-in browser's history, caches and log (see BrowserProfileCleanup)
    JcefRuntime.cleanProfileAtQuit()
    exitProcess(code)
}

internal class CliUsageError(message: String) : Exception(message)

internal fun Iterator<String>.optionValue(option: String): String =
    if (hasNext()) next() else throw CliUsageError("$option needs a value")

private fun Iterator<String>.intValue(option: String): Int =
    optionValue(option).toIntOrNull() ?: throw CliUsageError("$option needs a number")

internal fun describeError(e: Throwable): String {
    val root = generateSequence(e) { it.cause }.last()
    val msg = (root.message ?: "").lines().first().take(160)
    return root.javaClass.simpleName + if (msg.isNotEmpty()) ": $msg" else ""
}

internal fun runCliCommand(
    args: Array<String>,
    usage: String,
    loggerName: String,
    defaultCommand: String? = null,
    dispatch: (command: String, rest: List<String>) -> Int,
): Int {
    // ImageIO's streams over an InputStream or OutputStream otherwise copy the image to a temporary
    // file in the system's temporary folder (a decrypted page or cover); off, they cache in memory.
    // The app's own reads already use memory-cached streams (MemoryImageIo); this covers the rest.
    // Images read from or written to a File are opened directly either way
    javax.imageio.ImageIO.setUseCache(false)
    val command = args.firstOrNull() ?: defaultCommand ?: run {
        System.err.println(usage)
        return 2
    }
    val rest = args.drop(1)
    return try {
        dispatch(command, rest)
    } catch (e: CliUsageError) {
        System.err.println("${e.message}\n\n$usage")
        2
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("zoukan $command failed: ${describeError(e)}")
        LoggerFactory.getLogger(loggerName).error("zoukan {} failed", command, e)
        1
    }
}

/**
 * Runs one command line and returns its exit code: 0 success, 1 the command failed, 2 bad usage.
 * It does not throw for a failing command, so [main] always reaches its shutdown path.
 */
internal fun runCommand(args: Array<String>): Int =
    runCliCommand(args, USAGE, loggerName = "zoukan.cli", defaultCommand = "ui", dispatch = ::dispatch)

private fun dispatch(command: String, rest: List<String>): Int {
    return when (command) {
        "ui" -> {
            try {
                runZoukanApp()
            } catch (e: Throwable) {
                // Starting failed (for example the database would not open): say so and where the
                // crash log is, since the installed app has no console
                CrashReporter.fatal(e)
            }
            // The windows are closed: stop the background work and let the reader's progress and
            // history, and the other writes already started, finish (a few seconds at most)
            AppShutdown.runForApp(AppScope)
            0
        }
        "version", "--version" -> {
            println("Zoukan ${AppVersion.name}")
            0
        }
        "selftest" -> selftest(rest)
        "status" -> status()
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
}

private fun selftest(args: List<String>): Int {
    var options = SelfTest.Options()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--report" -> options = options.copy(report = File(it.optionValue("--report")))
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
    ApkConverter.convert(File(args[0]).readBytes(), File(args[1]))
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
        val handler = Injekt.get<DatabaseHandler>()
        val stores = runBlocking { handler.awaitList { extension_storeQueries.getAll() } }
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
        val window = BrowserWindow(url)
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
    val positional = rest.filterNot { it.startsWith("--") }
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
                val (errors, skippedStores) = runBlocking {
                    RestoreGate.whileRestoring {
                        // Extension stores decide whose code runs, and the command line asks no
                        // one: it never adds a store or changes a signing key, as Settings would ask
                        val (restorable, skipped) = backups.withoutNewExtensionStores(backup)
                        backups.restore(restorable, allowSensitive = "--private" in flags) { step, progress, total ->
                            val now = System.currentTimeMillis()
                            if (progress == total || now - last > 1000) {
                                last = now
                                val label = when (step) {
                                    is BackupContentRestorer.Step.Entry -> step.title
                                    is BackupContentRestorer.Step.Section -> Injekt.get<Application>().stringResource(step.label)
                                }
                                println("[$progress/$total] $label")
                            }
                        } to skipped
                    }
                }
                println("Restored ${backup.backupManga.size} entries from $file")
                if (skippedStores.isNotEmpty()) {
                    println("Not restored: ${skippedStores.size} extension stores that are new or have a new signing key:")
                    skippedStores.forEach { println("  ${it.name}: ${it.indexUrl}") }
                    println("To add them, restore this backup from Settings in the app, which asks first, or add them in Browse > Extensions > Stores.")
                }
                if (errors.isNotEmpty()) {
                    println("${errors.size} errors:")
                    errors.forEach { (_, message) -> println("  $message") }
                }
                if (errors.isEmpty()) 0 else 1
            } finally {
                lock.close()
            }
        }
        else -> usage("backup create|restore ...")
    }
}

private fun library(args: List<String>): Int {
    if (args.firstOrNull() != "update") return usage("library update [--all-entries] [--download <n>] [--report <file.md>]")
    var options = LibraryCommand.Options()
    val it = args.drop(1).iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--all-entries" -> options = options.copy(ignoreRestrictions = true)
            "--download" -> options = options.copy(download = it.intValue("--download"))
            "--report" -> options = options.copy(report = File(it.optionValue("--report")))
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
    val instance = SingleInstance.acquireForCommand(ZoukanDirs.default().root, command)
    if (instance == null) {
        System.err.println("Zoukan is already running; \"zoukan $command\" cannot use the same data folder beside it. $hint")
    }
    return instance
}

private fun usage(text: String): Int {
    System.err.println("Usage: zoukan $text")
    return 2
}
