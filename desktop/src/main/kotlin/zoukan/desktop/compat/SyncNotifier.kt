package eu.kanade.tachiyomi.data.sync

import android.content.Context
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.tray.Notifications

/** The Android SyncNotifier's messages as desktop notifications. */
@Suppress("UNUSED_PARAMETER")
class SyncNotifier(context: Context) {
    fun showSyncError(error: String?) {
        Notifications.post(localized(SYMR.strings.sync_error), error.orEmpty(), Notifications.Kind.ERROR)
    }

    fun showSyncSuccess(message: String?) {
        Notifications.post(localized(SYMR.strings.sync_complete), message.orEmpty())
    }
}
