// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

/**
 * Milliseconds since an arbitrary fixed point, which never goes backwards.
 * Used for rate limiting, where wall-clock changes must not matter.
 */
internal expect fun elapsedRealtime(): Long
// ZOUKAN <--
