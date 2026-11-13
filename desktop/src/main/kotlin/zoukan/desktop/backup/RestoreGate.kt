package zoukan.desktop.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps backup restores, from Settings, data sync or the command line, apart from each other and
 * from the background work that reads the library: the scheduled library update and the automatic
 * backup must not read, write or snapshot a library that a restore is still writing into.
 *
 * A read/write gate: a restore ([whileRestoring]) runs alone, after any other restore and after the
 * work in [unlessRestoring] blocks that already started; an [unlessRestoring] block does not start
 * while a restore runs or waits.
 */
object RestoreGate {

    /** More than the work that can hold the gate at once (the update's sources and the backup). */
    @PublishedApi
    internal const val READERS = 256

    /** Restores waiting or running. */
    @PublishedApi
    internal val pending = AtomicInteger(0)

    /** One restore at a time. */
    @PublishedApi
    internal val restores = Mutex()

    /** One permit per [unlessRestoring] block; a restore takes them all. */
    @PublishedApi
    internal val readers = Semaphore(READERS)

    /** True from when a restore is asked for (it may still wait its turn) to just after it ends. */
    val isRestoring: Boolean get() = pending.get() > 0

    /**
     * Runs [block] as the only restore, once the restores and the [unlessRestoring] blocks before it
     * ended. [isRestoring] is up from the call until [block] ends, also when it throws or is
     * cancelled.
     */
    suspend inline fun <T> whileRestoring(block: () -> T): T {
        pending.incrementAndGet()
        try {
            return restores.withLock {
                var held = 0
                try {
                    repeat(READERS) {
                        readers.acquire()
                        held++
                    }
                    block()
                } finally {
                    repeat(held) { readers.release() }
                }
            }
        } finally {
            pending.decrementAndGet()
        }
    }

    /**
     * Runs [block] unless a restore is running or waiting, in which case it returns null without
     * running it. A restore asked for while [block] runs waits for it to end.
     */
    inline fun <T : Any> unlessRestoring(block: () -> T): T? {
        if (isRestoring || !readers.tryAcquire()) return null
        try {
            // A restore asked for between the check and the permit is still waiting for this permit
            // and the library is still whole, so running is safe
            return block()
        } finally {
            readers.release()
        }
    }
}
