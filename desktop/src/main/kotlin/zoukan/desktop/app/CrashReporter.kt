package zoukan.desktop.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import eu.kanade.tachiyomi.AppInfo
import logcat.LogPriority
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.browser.BrowserWindow
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The log file and crash logs. The packaged app has no console, so everything the app logs also
 * goes to `<data>/logs/zoukan.log` (three older files are kept), and a crash writes a report with
 * the stack trace, the versions and the end of the log to `<data>/crashes/`.
 *
 * - A crash on the UI thread (or while starting) is fatal: the report is written, a dialog says
 *   where it is, and the app closes.
 * - An uncaught exception on another thread goes to the log and an `error-*.txt` report; the
 *   app goes on.
 * - When the app did not shut down cleanly (killed, out of memory, a JVM crash), the next start
 *   says so and copies any JVM crash file (`hs_err_pid*.log`) into the crash folder.
 */
object CrashReporter {
    private const val MAX_REPORTS = 30
    private const val LOG_TAIL_BYTES = 64 * 1024L

    @Volatile
    private var dirs: ZoukanDirs? = null

    val logDir: File? get() = dirs?.logs
    val crashDir: File? get() = dirs?.crashes

    /** Set when the previous run ended without a clean shutdown, with what was found. */
    @Volatile
    var previousRunCrashed: File? = null
        private set

    /** One marker per running process (the app, or a command sharing the data folder). */
    private fun ZoukanDirs.marker(pid: Long = ProcessHandle.current().pid()) = logs.resolve(".running-$pid")

    @Synchronized
    fun install(dirs: ZoukanDirs) {
        if (this.dirs != null) return
        this.dirs = dirs
        dirs.logs.mkdirs()
        dirs.crashes.mkdirs()
        installLogFile(dirs.logs)
        previousRunCrashed = checkPreviousRun(dirs)?.also {
            logcat(LogPriority.WARN) { "The previous run did not shut down cleanly; see $it" }
        }
        val marker = dirs.marker()
        marker.writeText(ProcessHandle.current().pid().toString())
        Runtime.getRuntime().addShutdownHook(Thread({ marker.delete() }, "zoukan-clean-exit"))
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Uncaught exceptions (for example on the AWT thread under Chromium) go to the log
            // with their stack trace, not only a bare "Exception in thread" line
            logcat(LogPriority.ERROR, e) { "Uncaught exception in thread ${thread.name}" }
            runCatching { write(thread, e, fatal = false) }
        }
    }

    /** Writes a report for a crash that ends the app, shows where it is, and exits. */
    fun fatal(e: Throwable, thread: Thread = Thread.currentThread()): Nothing {
        logcat(LogPriority.ERROR, e) { "Zoukan crashed" }
        val report = runCatching { write(thread, e, fatal = true) }.getOrNull()
        showCrashDialog(e, report)
        // The report is written and the dialog is gone: dispose the built-in browser windows like
        // the quit paths do (a browser still open when the JVM's shutdown hook disposes Chromium
        // at exit crashes), and never let that keep the app from exiting or come back here
        runCatching { BrowserWindow.disposeAllForQuit() }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not dispose the browser windows while crashing" } }
        exitProcess(1)
    }

    /** Writes a crash report and returns it, or null when crash logs are not set up. */
    fun write(thread: Thread, e: Throwable, fatal: Boolean): File? {
        val dir = crashDir ?: return null
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val file = File(dir, "${if (fatal) "crash" else "error"}-$stamp.txt")
        file.writeText(report(thread, e, fatal))
        prune(dir)
        return file
    }

    fun report(thread: Thread, e: Throwable, fatal: Boolean): String = buildString {
        appendLine(if (fatal) "Zoukan crash report" else "Zoukan error report (uncaught exception, the app went on)")
        appendLine()
        environment().forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Thread: ${thread.name}")
        appendLine()
        appendLine("Stack trace:")
        appendLine(stackTrace(e))
        val tail = logTail()
        if (tail.isNotBlank()) {
            appendLine("End of the log (${dirs?.logs?.resolve("zoukan.log")}):")
            appendLine(tail)
        }
    }

    fun environment(): List<Pair<String, String>> {
        val runtime = Runtime.getRuntime()
        return listOf(
            "Time" to LocalDateTime.now().toString(),
            "Zoukan" to AppVersion.name,
            "Komikku (extensions see)" to "${AppInfo.getVersionName()} (${AppInfo.getVersionCode()})",
            "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            "Java" to "${System.getProperty("java.vm.vendor")} ${System.getProperty("java.runtime.version")}",
            "Java home" to System.getProperty("java.home"),
            "Data folder" to (dirs?.root?.absolutePath ?: "?"),
            "Memory" to "${(runtime.totalMemory() - runtime.freeMemory()) shr 20} MB used of ${runtime.maxMemory() shr 20} MB",
        )
    }

    /** Crash and error reports, newest first. */
    fun reports(): List<File> =
        crashDir?.listFiles { f -> f.isFile && (f.name.endsWith(".txt") || f.name.endsWith(".log")) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun logTail(dirs: ZoukanDirs? = this.dirs): String {
        val log = dirs?.logs?.resolve("zoukan.log")?.takeIf { it.isFile } ?: return ""
        return runCatching {
            RandomAccessFile(log, "r").use { raf ->
                val start = (raf.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                String(bytes).let { if (start > 0) it.substringAfter('\n') else it }
            }
        }.getOrDefault("")
    }

    /** Keeps the newest reports; background errors are pruned apart, so they never push out a crash. */
    private fun prune(dir: File) {
        val (errors, crashes) = dir.listFiles()?.filter { it.isFile }.orEmpty().partition { it.name.startsWith("error-") }
        listOf(errors, crashes).forEach { files ->
            files.sortedByDescending { it.lastModified() }.drop(MAX_REPORTS).forEach { it.delete() }
        }
    }

    /**
     * A marker left by a process that is gone means that process did not reach its shutdown hook.
     * Markers of processes still running (another Zoukan command using the same data folder) are
     * left alone. Returns the newest crash report found: the JVM's crash file copied over, the
     * fatal report the process wrote, or a note.
     */
    internal fun checkPreviousRun(dirs: ZoukanDirs): File? {
        val markers = dirs.logs.listFiles { f -> f.name.startsWith(".running-") }.orEmpty()
        return markers
            .mapNotNull { marker ->
                val pid = marker.name.removePrefix(".running-").toLongOrNull() ?: return@mapNotNull null
                if (isRunning(pid, since = marker.lastModified())) return@mapNotNull null
                reportFor(dirs, marker, pid)
            }
            .maxByOrNull { it.lastModified() }
    }

    /** Whether [pid] is a live process that started before its marker was written (not a reused pid). */
    internal fun isRunning(pid: Long, since: Long): Boolean {
        val process = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!process.isAlive) return false
        val started = process.info().startInstant().orElse(null) ?: return true
        // The marker is written after the process starts; a later start is another process
        return started.toEpochMilli() <= since + 1_000
    }

    private fun reportFor(dirs: ZoukanDirs, marker: File, pid: Long): File {
        val since = marker.lastModified()
        marker.delete()
        dirs.crashes.mkdirs()
        // The JVM writes hs_err_pid<pid>.log to the working folder, or the temp folder when that
        // is not writable (the installed app's folder)
        val jvmCrash = listOfNotNull(System.getProperty("user.dir"), System.getProperty("java.io.tmpdir"), dirs.root.path)
            .map(::File)
            .flatMap { it.listFiles { f -> f.name == "hs_err_pid$pid.log" }.orEmpty().toList() }
            .firstOrNull { it.lastModified() >= since }
        jvmCrash?.let { src ->
            return File(dirs.crashes, "jvm-crash-${src.name}").also { src.copyTo(it, overwrite = true) }
        }
        // A fatal report from that run is already there; otherwise note the unclean end
        dirs.crashes.listFiles { f -> f.name.startsWith("crash-") && f.lastModified() >= since }
            ?.maxByOrNull { it.lastModified() }
            ?.let { return it }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(dirs.crashes, "unclean-exit-$stamp-$pid.txt").apply {
            writeText(
                "Zoukan did not shut down cleanly (process $pid, started ${java.time.Instant.ofEpochMilli(since)}).\n" +
                    "It may have been killed, run out of memory or lost power. No stack trace was recorded.\n\n" +
                    "End of the log:\n" + logTail(dirs),
            )
        }
    }

    private fun installLogFile(dir: File) {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender("FILE") != null) return
        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{24} - %msg%n"
            start()
        }
        val appender = RollingFileAppender<ILoggingEvent>()
        appender.context = context
        appender.name = "FILE"
        appender.file = File(dir, "zoukan.log").absolutePath
        val rolling = FixedWindowRollingPolicy().apply {
            this.context = context
            fileNamePattern = File(dir, "zoukan.%i.log").absolutePath
            minIndex = 1
            maxIndex = 3
            setParent(appender)
            start()
        }
        val trigger = SizeBasedTriggeringPolicy<ILoggingEvent>().apply {
            this.context = context
            maxFileSize = FileSize.valueOf("5MB")
            start()
        }
        appender.encoder = encoder
        appender.rollingPolicy = rolling
        appender.triggeringPolicy = trigger
        appender.start()
        root.addAppender(appender)
    }

    private fun showCrashDialog(e: Throwable, report: File?) {
        if (GraphicsEnvironment.isHeadless() || System.getProperty("zoukan.noCrashDialog") == "true") {
            System.err.println("Zoukan crashed: $e" + (report?.let { "\nCrash log: $it" } ?: ""))
            return
        }
        val show = {
            val message = buildString {
                appendLine("Zoukan ran into a problem and has to close.")
                appendLine()
                appendLine("${e::class.simpleName}: ${e.message.orEmpty().take(300)}")
                if (report != null) {
                    appendLine()
                    appendLine("The crash log is saved at")
                    append(report.absolutePath)
                }
            }
            val options = if (report != null) arrayOf("Show the crash log", "Close") else arrayOf("Close")
            val choice = JOptionPane.showOptionDialog(
                null,
                message,
                "Zoukan",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null,
                options,
                options.last(),
            )
            if (report != null && choice == 0) runCatching { Desktop.getDesktop().open(report.parentFile) }
        }
        runCatching {
            if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeAndWait(show)
        }
    }
}
