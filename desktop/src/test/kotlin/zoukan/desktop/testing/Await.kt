package zoukan.desktop.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Waits on a condition instead of sleeping for a guessed time. Tests that wait for a state, a
 * file, a thread or a callback use these (or a latch, a `CompletableDeferred`, `StateFlow.first`
 * under `withTimeout`, Compose's `waitUntil`, or virtual time in `runTest`); `ThreadSleepScanTest`
 * refuses a new `Thread.sleep` in test code.
 */
object Await {
    /** How long a wait lasts before it fails, unless the caller gives its own. */
    const val TIMEOUT_MILLIS = 10_000L

    /** How often a condition is checked. */
    const val POLL_MILLIS = 10L

    /**
     * Returns as soon as [condition] holds; fails naming [what] (and [state], when given) after
     * [timeoutMillis]. Blocks the calling thread, so it is for code outside a coroutine.
     */
    fun until(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        state: () -> Any? = { null },
        condition: () -> Boolean,
    ) {
        check(within(timeoutMillis, condition)) {
            "Timed out after $timeoutMillis ms waiting for $what" + (state()?.let { "; state: $it" } ?: "")
        }
    }

    /**
     * Whether [condition] held within [timeoutMillis]; returns as soon as it does. For a harness
     * that goes on either way instead of failing.
     */
    fun within(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_MILLIS)
        }
        return true
    }

    /**
     * [until] for a coroutine on a real dispatcher: suspends instead of blocking. Under `runTest`
     * the timeout and the polling run in virtual time, so use a latch or `advanceUntilIdle` there.
     */
    suspend fun untilSuspending(
        what: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
        condition: suspend () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(POLL_MILLIS) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("Timed out after $timeoutMillis ms waiting for $what", e)
        }
    }
}
