// ZOUKAN -->
package exh.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Desktops do not sleep mid-task the way phones do; callers wrap these in ignore {} and go on. */
fun Context.createPartialWakeLock(tag: String): PowerManager.WakeLock =
    throw UnsupportedOperationException("No wake locks on desktop ($tag)")

fun Context.createWifiLock(tag: String): WifiManager.WifiLock =
    throw UnsupportedOperationException("No Wi-Fi locks on desktop ($tag)")
// ZOUKAN <--
