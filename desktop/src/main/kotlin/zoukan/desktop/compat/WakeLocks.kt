// ZOUKAN -->
package exh.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import zoukan.desktop.ui.reader.KeepAwake

/** How a wake lock keeps the computer awake; tests replace it. */
internal var holdSystemAwake: () -> KeepAwake.Lock = { KeepAwake.acquire(display = false) }

/**
 * A wake lock that keeps the computer awake (not the display) until it is released. It is
 * acquired on creation, because the shared favorites sync only releases it and never calls acquire().
 */
fun Context.createPartialWakeLock(tag: String): PowerManager.WakeLock =
    PowerManager.INSTANCE.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
        setHolder {
            val lock = holdSystemAwake()
            Runnable { lock.release() }
        }
        acquire()
    }

/** A desktop has no Wi-Fi lock: the network does not sleep separately on desktop. */
fun Context.createWifiLock(tag: String): WifiManager.WifiLock =
    throw UnsupportedOperationException("No Wi-Fi locks on desktop ($tag)")
// ZOUKAN <--
