// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

import kotlin.time.TimeSource

private val origin = TimeSource.Monotonic.markNow()

/**
 * Milliseconds since an arbitrary fixed point, which never goes backwards. Used for rate
 * limiting, where wall-clock changes must not matter. Time the computer spends asleep may not
 * count (it does not on desktop), which only makes the rate limiter wait a little longer after
 * a sleep, never less.
 */
internal fun elapsedRealtime(): Long = origin.elapsedNow().inWholeMilliseconds
// ZOUKAN <--
