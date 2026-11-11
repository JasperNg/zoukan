// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

internal actual fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000
// ZOUKAN <--
