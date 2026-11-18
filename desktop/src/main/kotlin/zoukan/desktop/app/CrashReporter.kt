package zoukan.desktop.app

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
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
import zoukan.desktop.secrets.SecretStoreUnavailableException
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
 * - While incognito mode is on nothing is logged ([IncognitoLog]), and a report leaves out the log
 *   and the exception messages (class names and stack frames only).
 * - Zoukan's reports older than 30 days, in the crash folder and the reports folder, are deleted at
 *   start and when a report is written.
 */
object CrashReporter {
    private const val MAX_REPORTS = 30
    private const val LOG_TAIL_BYTES = 64 * 1024L
    private const val MAX_REPORT_AGE_DAYS = 30L

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
        pruneOldReports(dirs)
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
        runCatching { prune(dir) }
        return file
    }

    fun report(thread: Thread, e: Throwable, fatal: Boolean): String = buildString {
        appendLine(if (fatal) "Zoukan crash report" else "Zoukan error report (uncaught exception, the app went on)")
        appendLine()
        environment().forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("Thread: ${thread.name}")
        appendLine()
        appendLine("Stack trace:")
        // Incognito mode logs nothing, and its report shows neither the exception messages (they
        // often carry a chapter address or a title) nor the log from before it
        if (IncognitoLog.isOn) {
            appendLine("The exception messages are left out because incognito mode is on.")
            appendLine(stackTraceWithoutMessages(e))
            appendLine("The end of the log is left out because incognito mode is on.")
            return@buildString
        }
        // Exception messages can carry a sign-in address or a header, like the log
        appendLine(redact(stackTrace(e)))
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

    /**
     * [e]'s stack trace laid out like [Throwable.printStackTrace] (causes, suppressed exceptions,
     * "... n more" and circular references), but with only the class name of each exception: no
     * message and no [Throwable.toString], which a class may override to show its message.
     * Never throws; what cannot be read is replaced by a note.
     */
    internal fun stackTraceWithoutMessages(e: Throwable): String = try {
        buildString {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            appendThrowable(e, enclosing = emptyArray(), caption = "", prefix = "", seen = seen)
        }
    } catch (t: Exception) {
        "<the stack trace could not be written: ${t.javaClass.name}>"
    } catch (t: StackOverflowError) {
        "<the stack trace could not be written: ${t.javaClass.name}>"
    }

    private fun StringBuilder.appendThrowable(
        e: Throwable,
        enclosing: Array<StackTraceElement>,
        caption: String,
        prefix: String,
        seen: MutableSet<Throwable>,
    ) {
        if (!seen.add(e)) {
            append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(e.javaClass.name).appendLine("]")
            return
        }
        val trace = e.stackTrace
        // Frames in common with the enclosing trace are counted, not repeated
        var m = trace.size - 1
        var n = enclosing.size - 1
        while (m >= 0 && n >= 0 && trace[m] == enclosing[n]) {
            m--
            n--
        }
        val inCommon = trace.size - 1 - m
        append(prefix).append(caption).appendLine(e.javaClass.name)
        for (i in 0..m) append(prefix).append("\tat ").appendLine(trace[i])
        if (inCommon != 0) append(prefix).append("\t... ").append(inCommon).appendLine(" more")
        e.suppressed.forEach { appendThrowable(it, trace, "Suppressed: ", prefix + "\t", seen) }
        e.cause?.let { appendThrowable(it, trace, "Caused by: ", prefix, seen) }
    }

    private fun logTail(dirs: ZoukanDirs? = this.dirs): String {
        val log = dirs?.logs?.resolve("zoukan.log")?.takeIf { it.isFile } ?: return ""
        return runCatching {
            RandomAccessFile(log, "r").use { raf ->
                val start = (raf.length() - LOG_TAIL_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                redact(String(bytes).let { if (start > 0) it.substringAfter('\n') else it })
            }
        }.getOrDefault("")
    }

    // Same names as the network logger's redacted query parameters where they overlap (that list is
    // internal to the network module), plus the sign-in ones
    private val SECRET_PARAMS = listOf(
        "code", "access_token", "refresh_token", "id_token", "token", "client_secret", "password",
        "api_key", "apikey", "sid", "sessionid", "signature",
    )
    private val SECRET_PAIR = Regex("""(?<![A-Za-z0-9_])(${SECRET_PARAMS.joinToString("|")})=[^&\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("""(Bearer\s+)[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    /** Removes the values of sign-in codes, tokens and passwords, and Bearer tokens, from log text. */
    internal fun redact(text: String): String = text
        .replace(SECRET_PAIR) { "${it.groupValues[1]}=<redacted>" }
        .replace(BEARER) { "${it.groupValues[1]}<redacted>" }

    /** A copy of the JVM crash file without the values of environment variables, and with secrets redacted. */
    private fun copyRedacted(src: File, dest: File) {
        var inEnvironment = false
        dest.bufferedWriter().use { out ->
            src.useLines { lines ->
                lines.forEach { line ->
                    val outLine = when {
                        line.trim() == "Environment Variables:" -> {
                            inEnvironment = true
                            line
                        }
                        inEnvironment && line.isBlank() -> {
                            inEnvironment = false
                            line
                        }
                        inEnvironment -> line.substringBefore('=') + if ('=' in line) "=<redacted>" else ""
                        else -> redact(line)
                    }
                    out.write(outLine)
                    out.newLine()
                }
            }
        }
    }

    /** The crash folder's files Zoukan writes: crash and error reports, unclean-exit notes, JVM crash file copies. */
    private val CRASH_FOLDER_FILE = Regex(
        """(crash|error)-\d{8}-\d{6}-\d{3}\.txt|unclean-exit-\d{8}-\d{6}-\d+\.txt|jvm-crash-hs_err_pid\d+\.log""",
    )

    /** The reports folder's files Zoukan writes: the library update's errors, the extension checks' reports. */
    private val REPORTS_FOLDER_FILE = Regex("""library-update-errors\.txt|extensions-[A-Za-z0-9._-]+\.(md|txt)""")

    /**
     * Keeps the newest [MAX_REPORTS] crash reports and as many error reports (pruned apart, so
     * background errors never push out a crash), and drops any older than [MAX_REPORT_AGE_DAYS].
     * Only Zoukan's own files are touched.
     */
    internal fun prune(dir: File, now: Long = System.currentTimeMillis()) {
        val (errors, crashes) = ownFiles(dir, CRASH_FOLDER_FILE).partition { it.name.startsWith("error-") }
        listOf(errors, crashes).forEach { files ->
            files.sortedByDescending { it.lastModified() }.drop(MAX_REPORTS).forEach(::deleteQuietly)
        }
        pruneByAge(dir, CRASH_FOLDER_FILE, now)
    }

    /** At start: the crash and reports folders lose Zoukan's files older than [MAX_REPORT_AGE_DAYS]. Never throws. */
    internal fun pruneOldReports(dirs: ZoukanDirs, now: Long = System.currentTimeMillis()) {
        runCatching { prune(dirs.crashes, now) }
        runCatching { pruneByAge(dirs.reports, REPORTS_FOLDER_FILE, now) }
    }

    private fun pruneByAge(dir: File, pattern: Regex, now: Long) {
        val cutoff = now - java.util.concurrent.TimeUnit.DAYS.toMillis(MAX_REPORT_AGE_DAYS)
        ownFiles(dir, pattern).filter { it.lastModified() in 1 until cutoff }.forEach(::deleteQuietly)
    }

    /** Regular files (not links or folders) directly in [dir] whose names match [pattern]. */
    private fun ownFiles(dir: File, pattern: Regex): List<File> =
        runCatching {
            dir.listFiles().orEmpty().filter { f ->
                pattern.matches(f.name) &&
                    java.nio.file.Files.isRegularFile(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }
        }.getOrDefault(emptyList())

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    /**
     * A marker left by a process that is gone means that process did not reach its shutdown hook.
     * Markers of processes still running (another Zoukan command using the same data folder) are
     * left alone. Returns the newest crash report found: the JVM's crash file copied over, the
     * fatal report the process wrote, or a note.
     *
     * [jvmCrashFolders] are where the JVM writes its crash file (the working folder, or the temp
     * folder when that is not writable); a copied crash file is deleted only from one of them.
     */
    internal fun checkPreviousRun(dirs: ZoukanDirs, jvmCrashFolders: List<File> = defaultJvmCrashFolders()): File? {
        val markers = dirs.logs.listFiles { f -> f.name.startsWith(".running-") }.orEmpty()
        return markers
            .mapNotNull { marker ->
                val pid = marker.name.removePrefix(".running-").toLongOrNull() ?: return@mapNotNull null
                if (isRunning(pid, since = marker.lastModified())) return@mapNotNull null
                reportFor(dirs, marker, pid, jvmCrashFolders)
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

    private fun reportFor(dirs: ZoukanDirs, marker: File, pid: Long, jvmCrashFolders: List<File>): File {
        val since = marker.lastModified()
        marker.delete()
        dirs.crashes.mkdirs()
        // The JVM writes hs_err_pid<pid>.log to the working folder, or the temp folder when that
        // is not writable (the installed app's folder). The pid may since have been reused by
        // another Java program, so a file that does not name Zoukan's main class is left alone.
        val jvmCrash = (jvmCrashFolders + dirs.root)
            .flatMap { runCatching { it.listFiles { f -> f.name == "hs_err_pid$pid.log" }.orEmpty().toList() }.getOrDefault(emptyList()) }
            .firstOrNull { it.lastModified() >= since && isZoukanJvmCrash(it) }
        jvmCrash?.let { src ->
            val dest = File(dirs.crashes, "jvm-crash-${src.name}")
            val copied = runCatching { copyRedacted(src, dest) }
                .onFailure {
                    logcat(LogPriority.WARN, it) { "Could not copy the JVM crash file ${src.name}" }
                    dest.delete()
                }
                .isSuccess
            if (copied) {
                deleteJvmCrashOriginal(src, jvmCrashFolders)
                return dest
            }
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

    private val HS_ERR_NAME = Regex("""hs_err_pid\d+\.log""")

    /** The app's main class, as the JVM names it in its crash file (the Gradle run and the installed launcher). */
    private const val MAIN_CLASS = "zoukan.desktop.MainKt"

    /** How much of a JVM crash file is read to find its command line (it is in the summary near the top). */
    private const val CRASH_FILE_HEAD_BYTES = 64 * 1024

    /** The working and temp folders, where the JVM writes its crash file. */
    private fun defaultJvmCrashFolders(): List<File> =
        listOfNotNull(System.getProperty("user.dir"), System.getProperty("java.io.tmpdir")).map(::File)

    /**
     * Whether [file] is a crash file of Zoukan's JVM: its `Command Line:` or `java_command:` line
     * names Zoukan's main class. Only the first [CRASH_FILE_HEAD_BYTES] are read; a file that
     * cannot be read, or another program's, is not Zoukan's. Never throws.
     */
    internal fun isZoukanJvmCrash(file: File): Boolean = runCatching {
        val head = file.inputStream().use { it.readNBytes(CRASH_FILE_HEAD_BYTES) }
        // Latin-1 decodes any bytes; the line looked for is ASCII
        String(head, Charsets.ISO_8859_1).lineSequence().any { raw ->
            val line = raw.trim()
            val value = when {
                line.startsWith("Command Line:") -> line.removePrefix("Command Line:")
                line.startsWith("java_command:") -> line.removePrefix("java_command:")
                else -> return@any false
            }
            value.split(' ', '\t').any { it == MAIN_CLASS }
        }
    }.getOrDefault(false)

    /**
     * Deletes the JVM's crash file once its redacted copy is in the crash folder: the original keeps
     * the values of environment variables. Only a `hs_err_pid*.log` directly in one of [allowedFolders]
     * (the working and temp folders, never the data folder) that names Zoukan's main class is deleted;
     * a failure is logged and the file stays.
     */
    internal fun deleteJvmCrashOriginal(src: File, allowedFolders: List<File> = defaultJvmCrashFolders()) {
        runCatching {
            if (!HS_ERR_NAME.matches(src.name) || !src.isFile) return
            val parent = src.absoluteFile.parentFile?.canonicalFile ?: return
            val allowed = allowedFolders.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            if (parent !in allowed) return
            if (!isZoukanJvmCrash(src)) return
            if (!src.delete()) logcat(LogPriority.WARN) { "Could not delete the JVM crash file ${src.name} after copying it" }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not delete the JVM crash file ${src.name} after copying it" } }
    }

    /**
     * The main log's layout: every line, with its stack trace, goes through [redact] before it is
     * written. A line that cannot be formatted or redacted is replaced by a note, never written as
     * it was, and never throws into the code that logged it.
     */
    internal class RedactingPatternLayout : PatternLayout() {
        override fun doLayout(event: ILoggingEvent): String = try {
            redact(super.doLayout(event))
        } catch (e: Exception) {
            UNREDACTABLE_LINE
        } catch (e: StackOverflowError) {
            UNREDACTABLE_LINE
        }
    }

    private const val UNREDACTABLE_LINE = "<a log line could not be written>\n"

    private fun installLogFile(dir: File) {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        // Before the appender, so no line of an incognito session reaches the file or the console
        IncognitoLog.installFilter(context)
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        if (root.getAppender("FILE") != null) return
        val layout = RedactingPatternLayout().apply {
            this.context = context
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{24} - %msg%n"
            start()
        }
        val encoder = LayoutWrappingEncoder<ILoggingEvent>().apply {
            this.context = context
            this.layout = layout
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

    /**
     * The crash dialog's text. While incognito mode is on, the exception's message (which often
     * carries a chapter address or a title) is not shown, only its class name. The message of a
     * [SecretStoreUnavailableException] is the app's own text and is always shown.
     */
    internal fun crashDialogMessage(e: Throwable, report: File?, incognito: Boolean = IncognitoLog.isOn): String = buildString {
        val unavailable = generateSequence(e) { it.cause }.take(10).filterIsInstance<SecretStoreUnavailableException>().firstOrNull()
        if (unavailable != null) {
            appendLine(unavailable.message.orEmpty())
        } else {
            appendLine("Zoukan ran into a problem and has to close.")
            appendLine()
            if (incognito) {
                appendLine("${e::class.simpleName} (the message is left out because incognito mode is on)")
            } else {
                appendLine("${e::class.simpleName}: ${redact(e.message.orEmpty()).take(300)}")
            }
        }
        if (report != null) {
            appendLine()
            appendLine("The log can include titles you read and file paths. Check it before you share it.")
            appendLine()
            appendLine("The crash log is saved at")
            append(report.absolutePath)
        }
    }

    private fun showCrashDialog(e: Throwable, report: File?) {
        if (GraphicsEnvironment.isHeadless() || System.getProperty("zoukan.noCrashDialog") == "true") {
            System.err.println("Zoukan crashed: $e" + (report?.let { "\nCrash log: $it" } ?: ""))
            return
        }
        val show = {
            val message = crashDialogMessage(e, report)
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
