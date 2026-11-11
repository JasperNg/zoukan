package eu.kanade.tachiyomi.data.sync

import android.content.Context
import zoukan.desktop.tray.Notifications

/** The Android SyncNotifier's messages as desktop notifications. */
@Suppress("UNUSED_PARAMETER")
class SyncNotifier(context: Context) {
    fun showSyncError(error: String?) {
        Notifications.post("Sync failed", error.orEmpty(), Notifications.Kind.ERROR)
    }

    fun showSyncSuccess(message: String?) {
        Notifications.post("Sync complete", message.orEmpty())
    }
}
