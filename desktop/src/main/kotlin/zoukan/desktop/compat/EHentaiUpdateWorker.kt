// ZOUKAN -->
package exh.eh

import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.exh.EhGalleryUpdater

/**
 * The Android EHentaiUpdateWorker's scheduling calls, for the shared favorites sync. On desktop
 * the gallery update runs from the app's background jobs (EhGalleryUpdater).
 */
object EHentaiUpdateWorker {
    fun scheduleBackground(context: Context) = Injekt.get<EhGalleryUpdater>().setPaused(false)

    fun cancelBackground(context: Context) = Injekt.get<EhGalleryUpdater>().setPaused(true)
}
// ZOUKAN <--
