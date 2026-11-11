// ZOUKAN -->
package eu.kanade.tachiyomi.network.interceptor

import android.os.SystemClock

internal actual fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
// ZOUKAN <--
