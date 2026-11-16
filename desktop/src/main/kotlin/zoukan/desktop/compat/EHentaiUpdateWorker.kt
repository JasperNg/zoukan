// ZOUKAN -->
package exh.eh

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.exh.EhGalleryUpdater
import kotlin.time.Duration.Companion.seconds

/**
 * The Android EHentaiUpdateWorker's scheduling calls, for the shared favorites sync. On desktop
 * the gallery update runs from the app's background jobs (EhGalleryUpdater).
 */
object EHentaiUpdateWorker {
    fun scheduleBackground(context: Context) = Injekt.get<EhGalleryUpdater>().setPaused(false)

    /**
     * Stops the gallery update, as Android's cancelAllWorkByTag does: a running round ends after
     * the gallery in flight, and this waits (at most a minute) for that before it returns, so the
     * caller does not edit the same entries as the round. The sync calls it off the main thread.
     */
    fun cancelBackground(context: Context) {
        val updater = Injekt.get<EhGalleryUpdater>()
        updater.setPaused(true)
        runBlocking { withTimeoutOrNull(60.seconds) { updater.awaitIdle() } }
    }
}
// ZOUKAN <--
