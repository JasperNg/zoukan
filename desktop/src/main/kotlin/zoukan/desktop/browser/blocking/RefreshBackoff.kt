package zoukan.desktop.browser.blocking

import kotlin.math.min

/**
 * When to try the user's block lists again after a refresh in which none of them could be
 * downloaded: after an hour, then two, four and so on, never later than the weekly refresh. It is
 * kept in memory only, so a restart tries once more. Thread-safe.
 */
internal class RefreshBackoff {
    private var failures = 0
    private var failedAtMs = 0L

    /** Whether to download the lists now, [lastCheckMs] being the last time one was downloaded. */
    @Synchronized
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean {
        if (!UserBlockLists.isDue(lastCheckMs, nowMs)) return false
        if (failures == 0) return true
        // A clock that went back is treated as due, as UserBlockLists.isDue does
        return nowMs < failedAtMs || nowMs - failedAtMs >= delayMs(failures)
    }

    /**
     * Records a refresh that downloaded [updated] of [listCount] lists at [nowMs]. Returns whether
     * it counts as done (at least one list came through, or there are none), so the weekly clock
     * restarts; otherwise the next try is backed off.
     */
    @Synchronized
    fun recordRefresh(updated: Int, listCount: Int, nowMs: Long): Boolean {
        val done = updated > 0 || listCount == 0
        if (done) recordSuccess() else recordFailure(nowMs)
        return done
    }

    /** At least one list was downloaded (or there are none): back to the weekly refresh. */
    @Synchronized
    fun recordSuccess() {
        failures = 0
        failedAtMs = 0L
    }

    /** No list could be downloaded at [nowMs]. */
    @Synchronized
    fun recordFailure(nowMs: Long) {
        failures = (failures + 1).coerceAtMost(MAX_DOUBLINGS + 1)
        failedAtMs = nowMs
    }

    companion object {
        /** How long to wait after the first failed refresh. */
        const val FIRST_RETRY_MS = 60L * 60 * 1000

        private const val MAX_DOUBLINGS = 20

        /** The wait after [failures] failed refreshes in a row: doubling from an hour, capped at a week. */
        fun delayMs(failures: Int): Long {
            if (failures <= 0) return 0L
            val doublings = min(failures - 1, MAX_DOUBLINGS)
            return min(FIRST_RETRY_MS shl doublings, UserBlockLists.REFRESH_INTERVAL_MS)
        }
    }
}
