package zoukan.desktop.ui

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_update_library
import zoukan.desktop.app.DesktopPreferences
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.open_zoukan
import zoukan.i18n.desktop.pause_downloads
import zoukan.i18n.desktop.quit
import zoukan.i18n.desktop.start_downloads

/**
 * The window's X: with "Close to tray" on and a tray to go to, the window hides and the app goes
 * on; the first time, [notifyStillRunning] says so. Otherwise the app quits.
 */
internal fun onWindowClose(
    trayAvailable: Boolean,
    preferences: DesktopPreferences,
    hide: () -> Unit,
    notifyStillRunning: () -> Unit,
    quit: () -> Unit,
) {
    if (trayAvailable && preferences.closeToTray().get()) {
        hide()
        if (!preferences.toldAboutTray().get()) {
            preferences.toldAboutTray().set(true)
            notifyStillRunning()
        }
    } else {
        quit()
    }
}

/** An entry of the tray icon's menu. */
internal sealed interface TrayEntry {
    data class Item(val label: StringResource, val onClick: () -> Unit) : TrayEntry
    data object Separator : TrayEntry
}

/** The tray icon's menu: open, update the library, pause or start the downloads, quit. */
internal fun trayMenu(
    downloading: Boolean,
    open: () -> Unit,
    updateLibrary: () -> Unit,
    pauseDownloads: () -> Unit,
    startDownloads: () -> Unit,
    quit: () -> Unit,
): List<TrayEntry> = listOf(
    TrayEntry.Item(DMR.strings.open_zoukan, open),
    TrayEntry.Item(MR.strings.action_update_library, updateLibrary),
    if (downloading) TrayEntry.Item(DMR.strings.pause_downloads, pauseDownloads) else TrayEntry.Item(DMR.strings.start_downloads, startDownloads),
    TrayEntry.Separator,
    TrayEntry.Item(DMR.strings.quit, quit),
)
