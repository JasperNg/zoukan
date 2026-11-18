package zoukan.desktop

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps folders out of Spotlight on macOS through its Search Privacy list (System Settings >
 * Spotlight > Search Privacy). The folders keep their names and contents; Spotlight stops indexing
 * them and drops what it had indexed, and indexes them again once they leave the list. A no-op on
 * other systems.
 *
 * The list is changed through private macOS calls ([SpotlightNative]), which are made only in a
 * short-lived helper process ([SpotlightExclusionHelper]) started with Zoukan's own Java runtime:
 * if a later macOS changes them and the call crashes, only the helper ends. Every failure (no Java
 * launcher, a crash, a non-zero exit, a timeout, output that does not parse) is passed to the
 * caller's note and never thrown.
 *
 * A helper that crashes, fails or hangs is not started again for the rest of the run: the next
 * calls return nothing without starting one. A crash leaves no files behind: the helper writes no
 * core dump, performance data or crash report, and a crash report the JVM writes anyway
 * (`hs_err_pid<pid>.log`, which holds environment values and paths) is deleted from its working
 * folder. A helper still running when Zoukan quits is killed, and one left behind by a Zoukan that
 * was killed ends itself ([SpotlightExclusionHelper.WATCHDOG_MILLIS]).
 */
internal class SpotlightExclusion(
    /** The command that runs the helper with the given arguments; tests put another one in. */
    private val command: (List<String>) -> List<String>? = HELPER,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val enabled: Boolean = Platform.current == Platform.MACOS,
    /** The helper's working folder, where the JVM would write its crash report. */
    private val workDir: File = File(System.getProperty("java.io.tmpdir")),
    /**
     * Set once a helper crashed, failed or hung; no helper is started after that. Shared by every
     * instance for the real helper, so it holds for the rest of the run; another command gets its own.
     */
    private val stopped: AtomicBoolean = if (command === HELPER) stoppedForRun else AtomicBoolean(false),
) {
    /**
     * Adds [dirs] to the list ([exclude] true) or takes them off it, in one helper run. Returns the
     * folders now in the wanted state; empty on other systems or when the helper failed. A folder
     * that is not there is not added.
     */
    fun set(dirs: List<File>, exclude: Boolean, note: (String) -> Unit): Set<File> {
        if (!enabled) return emptySet()
        val targets = if (exclude) dirs.filter { it.isDirectory } else dirs
        if (targets.isEmpty()) return emptySet()
        val action = if (exclude) SpotlightExclusionHelper.ADD else SpotlightExclusionHelper.REMOVE
        val output = run(action, targets, note) ?: return emptySet()
        val ok = parse(output, targets.size)
        if (ok.size < targets.size) {
            val failed = targets.filterIndexed { i, _ -> i !in ok }.joinToString { it.path }
            note("Spotlight's Search Privacy list did not change for $failed")
        }
        return ok.map { targets[it] }.toSet()
    }

    /**
     * The folders of [dirs] in the list; empty on other systems. Null when the list could not be
     * read for every folder (the helper failed or is stopped, or did not answer for one of them).
     */
    fun excluded(dirs: List<File>, note: (String) -> Unit): Set<File>? {
        if (!enabled || dirs.isEmpty()) return emptySet()
        val output = run(SpotlightExclusionHelper.CHECK, dirs, note) ?: return null
        val answers = parseAnswers(output, dirs.size)
        if (answers.size < dirs.size) {
            val unread = dirs.filterIndexed { i, _ -> i !in answers }.joinToString { it.path }
            note("Could not read Spotlight's Search Privacy list for $unread")
            return null
        }
        return dirs.filterIndexedTo(mutableSetOf()) { i, _ -> answers[i] == true }
    }

    /** Runs the helper with [action] on [dirs]; its standard output, or null (noted, unless stopped) when it did not run. */
    private fun run(action: String, dirs: List<File>, note: (String) -> Unit): String? {
        if (stopped.get()) return null
        return try {
            val paths = dirs.map { it.canonicalPath }
            val argv = command(listOf(action) + paths)
            if (argv == null) {
                note("Could not change Spotlight's Search Privacy list: no Java launcher for the helper")
                return null
            }
            runProcess(argv, note)
        } catch (e: Exception) {
            note("Could not change Spotlight's Search Privacy list: $e")
            null
        }
    }

    /**
     * Runs [argv]; its standard output, or null (noted) when it failed, crashed or timed out. After
     * a crash, failure or timeout no helper is started again, and its crash files are deleted.
     */
    private fun runProcess(argv: List<String>, note: (String) -> Unit): String? {
        registerKillOnExit()
        val process = ProcessBuilder(argv)
            .directory(workDir)
            .apply {
                // No agents or options from the environment in the helper's JVM
                environment().keys.removeAll(setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"))
            }
            .start()
        running.add(process)
        try {
            return waitFor(process, note)
        } finally {
            running.remove(process)
            // Never left running, whatever went wrong (killing does not wait)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun waitFor(process: Process, note: (String) -> Unit): String? {
        process.outputStream.close()
        val out = Drain(process.inputStream).also { it.start() }
        val err = Drain(process.errorStream).also { it.start() }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                deleteCrashFiles(process)
                note("The Spotlight helper did not finish within ${timeoutMillis / 1000.0} s and was stopped${stop()}")
                return null
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            note("The Spotlight helper was interrupted")
            return null
        }
        out.join(KILL_WAIT_MILLIS)
        err.join(KILL_WAIT_MILLIS)
        val code = process.exitValue()
        if (code != 0) {
            // 128 + n: ended by signal n. A JVM crash without a core dump ends with 1
            deleteCrashFiles(process)
            note("The Spotlight helper failed (exit code $code): ${err.text().takeLast(ERROR_TAIL).trim()}${stop()}")
            return null
        }
        err.text().trim().takeIf { it.isNotEmpty() }?.let { note("The Spotlight helper reported: ${it.takeLast(ERROR_TAIL)}") }
        return out.text()
    }

    /** Stops later runs; the text to add to the note the first time, else nothing. */
    private fun stop(): String =
        if (stopped.compareAndSet(false, true)) "; it will not be started again until Zoukan restarts" else ""

    /**
     * Deletes the crash report and compiler replay file the helper's JVM may have written into
     * [workDir], named by its process id. Only regular files are deleted, never a link. Best-effort.
     */
    private fun deleteCrashFiles(process: Process) {
        val pid = try {
            process.pid()
        } catch (e: Exception) {
            return
        }
        for (name in listOf("hs_err_pid$pid.log", "replay_pid$pid.log")) {
            try {
                val file = File(workDir, name).toPath()
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file)
            } catch (_: Exception) {
                // Left for the system to clear from the temporary folder
            }
        }
    }

    /** Reads a stream to its end on a daemon thread, keeping at most [MAX_OUTPUT] characters. */
    private class Drain(private val stream: InputStream) : Thread("spotlight-helper-output") {
        private val text = StringBuilder()

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(BUFFER)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        synchronized(text) { if (text.length < MAX_OUTPUT) text.append(buffer, 0, minOf(n, MAX_OUTPUT - text.length)) }
                    }
                }
            } catch (_: Exception) {
                // The process was stopped
            }
        }

        fun text(): String = synchronized(text) { text.toString() }
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
        private const val KILL_WAIT_MILLIS = 2_000L
        private const val ERROR_TAIL = 500
        private const val MAX_OUTPUT = 64 * 1024
        private const val BUFFER = 4096
        private val LINE = Regex("""(\d+) (ok|failed)""")

        /** The real helper's command; [stoppedForRun] applies to it. */
        private val HELPER: (List<String>) -> List<String>? = ::helperCommand

        /** Set once the real helper crashed, failed or hung in this run. */
        private val stoppedForRun = AtomicBoolean(false)

        /** Helpers running now; killed if Zoukan quits while one runs. */
        private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()

        private val hookRegistered = AtomicBoolean(false)

        /**
         * Registers, once, a shutdown hook that kills the running helpers. Killing does not wait, so
         * the hook cannot hold up quitting. Not registered when Zoukan is already quitting.
         */
        private fun registerKillOnExit() {
            if (!hookRegistered.compareAndSet(false, true)) return
            try {
                Runtime.getRuntime().addShutdownHook(
                    Thread({ running.forEach { runCatching { it.destroyForcibly() } } }, "spotlight-helper-cleanup"),
                )
            } catch (_: Exception) {
                // Zoukan is already quitting, or hooks are not allowed
            }
        }

        /** Lets the real helper run again, as in a new run (for tests). */
        internal fun resetForTests() = stoppedForRun.set(false)

        /** Whether the real helper was stopped for the rest of this run (for tests). */
        internal val stoppedForThisRun: Boolean get() = stoppedForRun.get()

        /** The indexes reported `ok` in the helper's [output], for [count] paths. */
        internal fun parse(output: String, count: Int): Set<Int> = output.lineSequence()
            .mapNotNull { LINE.matchEntire(it.trim()) }
            .filter { it.groupValues[2] == "ok" }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0 until count }
            .toSet()

        /**
         * The answers in the helper's [output], for [count] paths: `ok` true, `failed` false. An index
         * answered `unknown`, answered twice differently, or not at all is left out.
         */
        internal fun parseAnswers(output: String, count: Int): Map<Int, Boolean> {
            val answers = output.lineSequence()
                .mapNotNull { LINE.matchEntire(it.trim()) }
                .mapNotNull { match -> match.groupValues[1].toIntOrNull()?.let { it to (match.groupValues[2] == "ok") } }
                .filter { it.first in 0 until count }
                .toList()
            return answers.groupBy({ it.first }, { it.second })
                .filterValues { it.distinct().size == 1 }
                .mapValues { it.value.first() }
        }

        /**
         * The helper's command line: the Java launcher of the runtime Zoukan runs on (`bin/java` in
         * the bundled runtime, which jlink includes), a small heap, headless, and a class path of
         * just the jars the helper needs (Zoukan's, JNA's and Kotlin's). It leaves no files from a
         * crash: no core dump, no crash report (sent to /dev/null; [SpotlightExclusion] deletes one
         * written anyway) and no performance data file in the temporary folder. Null when the
         * launcher is not there.
         */
        fun helperCommand(args: List<String>): List<String>? {
            val java = File(System.getProperty("java.home") ?: return null, "bin/java")
            if (!java.canExecute()) return null
            val classPath = listOf(SpotlightExclusionHelper::class.java, com.sun.jna.Native::class.java, Unit::class.java)
                .map { File(it.protectionDomain.codeSource?.location?.toURI() ?: return null).path }
                .distinct()
                .joinToString(File.pathSeparator)
            return listOf(
                java.path,
                "-Xmx32m",
                "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1",
                "-XX:-CreateCoredumpOnCrash",
                "-XX:ErrorFile=/dev/null",
                "-XX:-UsePerfData",
                "-Djava.awt.headless=true",
                "-cp", classPath,
                SpotlightExclusionHelper::class.java.name,
            ) + args
        }
    }
}
