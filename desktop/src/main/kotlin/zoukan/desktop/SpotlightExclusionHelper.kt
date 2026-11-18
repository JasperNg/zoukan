package zoukan.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * The short-lived process that changes Spotlight's Search Privacy list for [SpotlightExclusion],
 * so a crash in the private macOS calls ([SpotlightNative]) ends only this process, never Zoukan.
 *
 * Arguments: an action ([ADD], [REMOVE] or [CHECK]) and one or more folder paths. For each path,
 * in order, one line `<index> ok` or `<index> failed` goes to standard output; problems go to
 * standard error. The exit code is 0 when every path was handled, [USAGE] for bad arguments and
 * [WATCHDOG] when the run took longer than [WATCHDOG_MILLIS] (a native call that hangs).
 *
 * It opens no data folder, log, preferences, single-instance lock or secret store. Only this file,
 * [SpotlightNative], JNA and the Kotlin standard library load; JNA may unpack its native library
 * into its own cache folder.
 */
internal object SpotlightExclusionHelper {
    const val ADD = "add"
    const val REMOVE = "remove"
    const val CHECK = "check"
    const val USAGE = 2

    /** The exit code when the watchdog ended a run that hung. */
    const val WATCHDOG = 3

    /**
     * How long a run may take before it ends itself: below [SpotlightExclusion.TIMEOUT_MILLIS], so
     * a helper whose parent quit or was killed while a native call hung does not stay behind.
     */
    const val WATCHDOG_MILLIS = 12_000L

    @JvmStatic
    fun main(args: Array<String>) {
        // First, before anything that could hang
        startWatchdog(WATCHDOG_MILLIS) { Runtime.getRuntime().halt(it) }
        val action = args.firstOrNull()
        val paths = args.drop(1)
        if (action !in setOf(ADD, REMOVE, CHECK) || paths.isEmpty()) {
            System.err.println("Usage: SpotlightExclusionHelper add|remove|check <folder>...")
            exitProcess(USAGE)
        }
        val note: (String) -> Unit = { System.err.println(it) }
        paths.forEachIndexed { index, path ->
            val dir = File(path)
            val ok = when (action) {
                ADD -> SpotlightNative.set(dir, true, note)
                REMOVE -> SpotlightNative.set(dir, false, note)
                else -> SpotlightNative.isExcluded(dir)
            }
            println("$index ${if (ok) "ok" else "failed"}")
        }
        System.out.flush()
        exitProcess(0)
    }

    /**
     * Starts a daemon thread that calls [halt] with [WATCHDOG] after [millis]. `halt` skips shutdown
     * hooks and finalization, so it ends the process even while another thread is stuck in a
     * native call. The thread does not keep the process alive.
     */
    internal fun startWatchdog(millis: Long, halt: (Int) -> Unit): Thread = Thread({
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            return@Thread
        }
        System.err.println("The run took longer than $millis ms and was stopped")
        System.err.flush()
        halt(WATCHDOG)
    }, "spotlight-helper-watchdog").apply {
        isDaemon = true
        start()
    }
}
