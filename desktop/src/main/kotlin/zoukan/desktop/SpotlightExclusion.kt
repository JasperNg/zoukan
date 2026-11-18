package zoukan.desktop

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

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
 */
internal class SpotlightExclusion(
    /** The command that runs the helper with the given arguments; tests put another one in. */
    private val command: (List<String>) -> List<String>? = ::helperCommand,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val enabled: Boolean = Platform.current == Platform.MACOS,
) {
    /**
     * Adds [dirs] to the list ([exclude] true) or takes them off it, in one helper run. Returns the
     * folders now in the wanted state; empty on other systems or when the helper failed. A folder
     * that is not there is not added.
     */
    fun set(dirs: List<File>, exclude: Boolean, note: (String) -> Unit): Set<File> {
        if (!enabled) return emptySet()
        val targets = if (exclude) dirs.filter { it.isDirectory } else dirs
        val action = if (exclude) SpotlightExclusionHelper.ADD else SpotlightExclusionHelper.REMOVE
        return run(action, targets, note, reportFailures = true)
    }

    /** The folders of [dirs] in the list; empty on other systems or when the helper failed. */
    fun excluded(dirs: List<File>, note: (String) -> Unit): Set<File> {
        if (!enabled) return emptySet()
        return run(SpotlightExclusionHelper.CHECK, dirs, note, reportFailures = false)
    }

    private fun run(action: String, dirs: List<File>, note: (String) -> Unit, reportFailures: Boolean): Set<File> {
        if (dirs.isEmpty()) return emptySet()
        return try {
            val paths = dirs.map { it.canonicalPath }
            val argv = command(listOf(action) + paths)
            if (argv == null) {
                note("Could not change Spotlight's Search Privacy list: no Java launcher for the helper")
                return emptySet()
            }
            val result = runProcess(argv, note) ?: return emptySet()
            val ok = parse(result, dirs.size)
            if (reportFailures && ok.size < dirs.size) {
                val failed = dirs.filterIndexed { i, _ -> i !in ok }.joinToString { it.path }
                note("Spotlight's Search Privacy list did not change for $failed")
            }
            ok.map { dirs[it] }.toSet()
        } catch (e: Exception) {
            note("Could not change Spotlight's Search Privacy list: $e")
            emptySet()
        }
    }

    /** Runs [argv]; its standard output, or null (noted) when it failed, crashed or timed out. */
    private fun runProcess(argv: List<String>, note: (String) -> Unit): String? {
        val process = ProcessBuilder(argv)
            .directory(File(System.getProperty("java.io.tmpdir")))
            .apply {
                // No agents or options from the environment in the helper's JVM
                environment().keys.removeAll(setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"))
            }
            .start()
        process.outputStream.close()
        val out = Drain(process.inputStream).also { it.start() }
        val err = Drain(process.errorStream).also { it.start() }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                note("The Spotlight helper did not finish within ${timeoutMillis / 1000.0} s and was stopped")
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
            // 128 + n: ended by signal n (a crash)
            note("The Spotlight helper failed (exit code $code): ${err.text().takeLast(ERROR_TAIL).trim()}")
            return null
        }
        err.text().trim().takeIf { it.isNotEmpty() }?.let { note("The Spotlight helper reported: ${it.takeLast(ERROR_TAIL)}") }
        return out.text()
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

        /** The indexes reported `ok` in the helper's [output], for [count] paths. */
        internal fun parse(output: String, count: Int): Set<Int> = output.lineSequence()
            .mapNotNull { LINE.matchEntire(it.trim()) }
            .filter { it.groupValues[2] == "ok" }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0 until count }
            .toSet()

        /**
         * The helper's command line: the Java launcher of the runtime Zoukan runs on (`bin/java` in
         * the bundled runtime, which jlink includes), a small heap, headless, and a class path of
         * just the jars the helper needs (Zoukan's, JNA's and Kotlin's). Null when the launcher is
         * not there.
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
                "-Djava.awt.headless=true",
                "-cp", classPath,
                SpotlightExclusionHelper::class.java.name,
            ) + args
        }
    }
}
