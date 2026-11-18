package zoukan.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * The short-lived process that changes Spotlight's Search Privacy list for [SpotlightExclusion],
 * so a crash in the private macOS calls ([SpotlightNative]) ends only this process, never Zoukan.
 *
 * Arguments: an action ([ADD], [REMOVE] or [CHECK]) and one or more folder paths. For each path,
 * in order, one line `<index> ok` or `<index> failed` goes to standard output; problems go to
 * standard error. The exit code is 0 when every path was handled, [USAGE] for bad arguments.
 *
 * It touches nothing else: no runtime start, data folder, logs, preferences, single-instance lock
 * or secret store. Only this file, [SpotlightNative], JNA and the Kotlin standard library load.
 */
internal object SpotlightExclusionHelper {
    const val ADD = "add"
    const val REMOVE = "remove"
    const val CHECK = "check"
    const val USAGE = 2

    @JvmStatic
    fun main(args: Array<String>) {
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
}
