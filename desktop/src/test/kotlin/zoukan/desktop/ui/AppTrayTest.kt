package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.i18n.desktop.DMR

/** Closing the window to the tray, and the tray icon's menu. */
class AppTrayTest {

    private fun preferences() = DesktopPreferences(DesktopPreferenceStore(JavaSharedPreferences("app_tray_test_${System.nanoTime()}")))

    @Test
    fun `close to tray hides the window and says so once, otherwise the app quits`() {
        val calls = mutableListOf<String>()
        fun close(trayAvailable: Boolean, preferences: DesktopPreferences) = onWindowClose(
            trayAvailable = trayAvailable,
            preferences = preferences,
            hide = { calls += "hide" },
            notifyStillRunning = { calls += "notify" },
            quit = { calls += "quit" },
        )

        val preferences = preferences()
        preferences.closeToTray().get() shouldBe true
        close(trayAvailable = true, preferences)
        preferences.toldAboutTray().get() shouldBe true
        close(trayAvailable = true, preferences)
        calls shouldBe listOf("hide", "notify", "hide")

        calls.clear()
        close(trayAvailable = false, preferences)
        preferences.closeToTray().set(false)
        close(trayAvailable = true, preferences)
        calls shouldBe listOf("quit", "quit")
    }

    @Test
    fun `the tray menu updates the library and pauses or starts the downloads`() {
        val calls = mutableListOf<String>()
        fun menu(downloading: Boolean) = trayMenu(
            downloading = downloading,
            open = { calls += "open" },
            updateLibrary = { calls += "update" },
            pauseDownloads = { calls += "pause" },
            startDownloads = { calls += "start" },
            quit = { calls += "quit" },
        ).filterIsInstance<TrayEntry.Item>().associate { it.label to it.onClick }

        menu(downloading = false).getValue(MR.strings.action_update_library)()
        menu(downloading = true).getValue(DMR.strings.pause_downloads)()
        menu(downloading = false).getValue(DMR.strings.start_downloads)()
        menu(downloading = true).containsKey(DMR.strings.start_downloads) shouldBe false
        calls shouldBe listOf("update", "pause", "start")
    }
}
