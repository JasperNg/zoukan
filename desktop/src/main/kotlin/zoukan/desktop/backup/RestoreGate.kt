package zoukan.desktop.backup

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether a backup restore is on, from Settings or from data sync. The background jobs stand down
 * while it goes: the scheduled library update, the data sync and the automatic backup must not
 * read, write or snapshot a library that a restore is still writing into.
 */
object RestoreGate {

    @PublishedApi
    internal val restoring = AtomicBoolean(false)

    /** True from just before a restore starts to just after it ends, failed or not. */
    val isRestoring: Boolean get() = restoring.get()

    /**
     * Runs [block] with [isRestoring] up, clearing it when [block] ends, also when it throws or is
     * cancelled.
     */
    inline fun <T> whileRestoring(block: () -> T): T {
        restoring.set(true)
        try {
            return block()
        } finally {
            restoring.set(false)
        }
    }
}
