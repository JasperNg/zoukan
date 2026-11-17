package zoukan.desktop.app

import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions

/**
 * What the app window does when it starts, goes away and comes back, as Android's App does on the
 * process lifecycle (onCreate, onStart, onStop). The Window composable reports the events here, so
 * the decisions can be tested without a window.
 *
 * - The app lock follows the window: away (minimized, in the tray, or another app in front) is
 *   Android's background, back is its foreground; "Lock when idle" counts from leaving the app.
 * - Data sync runs on start, and again when a window that was hidden (minimized or in the tray) is
 *   shown, as Android's "sync on app start" and "sync on app resume". Switching to another app and
 *   back is no resume to sync on. [syncOn] checks that a sync service is set up and the trigger is
 *   on, and does nothing while a sync runs (SyncManager.syncOn), as Android's SyncDataJob.startNow.
 *
 * Quitting and closing the window are not here (see onWindowClose).
 */
internal class AppLifecycle(
    private val syncOn: ((SyncTriggerOptions) -> Boolean) -> Unit,
    private val onBackground: () -> Unit,
    private val onForeground: () -> Unit,
) {
    private var wasHidden = false

    /** The window opened. */
    fun onStart() = syncOn { it.syncOnAppStart }

    /** [away]: the window is hidden or another app is in front. */
    fun onAwayChanged(away: Boolean) = if (away) onBackground() else onForeground()

    /** [hidden]: the window is minimized or in the tray. */
    fun onHiddenChanged(hidden: Boolean) {
        if (hidden) {
            wasHidden = true
        } else if (wasHidden) {
            syncOn { it.syncOnAppResume }
        }
    }
}
