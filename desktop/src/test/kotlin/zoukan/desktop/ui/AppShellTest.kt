package zoukan.desktop.ui

import androidx.compose.ui.window.Notification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.tray.Notifications

/**
 * The main window's decisions in ZoukanApp.kt ([AppShell]): quitting and Cmd+Q, when the app is
 * away, the root's focus for the shortcuts, tray notifications, and the rail's tabs and badges.
 * The window's X and the tray menu are in AppTrayTest, the keys in ShortcutsTest.
 */
class AppShellTest {

    @Test
    fun `quitting closes the browser windows before the app exits`() {
        val calls = mutableListOf<String>()
        AppShell.quit(disposeBrowsers = { calls += "browsers" }, exit = { calls += "exit" })
        calls shouldBe listOf("browsers", "exit")
    }

    @Test
    fun `Cmd+Q runs the app's quit while the window is open, and is held off once it has closed`() {
        val before = JcefRuntime.quitRequest
        try {
            var quits = 0
            val quit: () -> Unit = { quits++ }
            val leave = AppShell.installQuitRequest(quit)
            JcefRuntime.quitRequest shouldBeSameInstanceAs quit
            JcefRuntime.onBeforeTerminate(JcefRuntime.quitRequest) shouldBe true
            quits shouldBe 1

            leave()
            JcefRuntime.quitRequest shouldBeSameInstanceAs JcefRuntime.QUIT_UNDERWAY
            // Chromium still may not shut itself down, and the quit does not run twice
            JcefRuntime.onBeforeTerminate(JcefRuntime.quitRequest) shouldBe true
            quits shouldBe 1
        } finally {
            JcefRuntime.quitRequest = before
        }
    }

    @Test
    fun `the window is hidden in the tray or minimized, and away also when another app is in front`() {
        AppShell.hidden(visible = true, minimized = false) shouldBe false
        AppShell.hidden(visible = false, minimized = false) shouldBe true
        AppShell.hidden(visible = true, minimized = true) shouldBe true
        AppShell.hidden(visible = false, minimized = true) shouldBe true

        AppShell.away(hidden = false, appActive = true) shouldBe false
        AppShell.away(hidden = false, appActive = false) shouldBe true
        AppShell.away(hidden = true, appActive = true) shouldBe true
    }

    @Test
    fun `the root takes the focus back only when nothing has it and the app is unlocked`() {
        AppShell.rootTakesFocus(locked = false, rootHasFocus = false) shouldBe true
        AppShell.rootTakesFocus(locked = false, rootHasFocus = true) shouldBe false
        AppShell.rootTakesFocus(locked = true, rootHasFocus = false) shouldBe false
        AppShell.rootTakesFocus(locked = true, rootHasFocus = true) shouldBe false
    }

    @Test
    fun `each notification kind has its tray type`() {
        Notifications.Kind.entries.associateWith { AppShell.notificationType(it) } shouldBe mapOf(
            Notifications.Kind.INFO to Notification.Type.Info,
            Notifications.Kind.WARNING to Notification.Type.Warning,
            Notifications.Kind.ERROR to Notification.Type.Error,
        )
    }

    @Test
    fun `the rail hides Updates and History when their settings are off`() {
        AppShell.shownTabs(showUpdates = true, showHistory = true) shouldBe HomeTab.entries
        AppShell.shownTabs(showUpdates = false, showHistory = true) shouldBe listOf(HomeTab.Library, HomeTab.History, HomeTab.Browse, HomeTab.More)
        AppShell.shownTabs(showUpdates = true, showHistory = false) shouldBe listOf(HomeTab.Library, HomeTab.Updates, HomeTab.Browse, HomeTab.More)
        AppShell.shownTabs(showUpdates = false, showHistory = false) shouldBe listOf(HomeTab.Library, HomeTab.Browse, HomeTab.More)
    }

    @Test
    fun `badges count new chapters on Updates and extension updates on Browse`() {
        AppShell.railBadge(HomeTab.Updates, showBadge = true, newUpdates = 3, extensionUpdates = 2) shouldBe 3
        AppShell.railBadge(HomeTab.Updates, showBadge = false, newUpdates = 3, extensionUpdates = 2) shouldBe 0
        AppShell.railBadge(HomeTab.Updates, showBadge = true, newUpdates = 0, extensionUpdates = 2) shouldBe 0
        // The extension badge has no setting of its own
        AppShell.railBadge(HomeTab.Browse, showBadge = false, newUpdates = 3, extensionUpdates = 2) shouldBe 2
        AppShell.railBadge(HomeTab.Browse, showBadge = true, newUpdates = 3, extensionUpdates = 0) shouldBe 0
        for (tab in listOf(HomeTab.Library, HomeTab.History, HomeTab.More)) {
            AppShell.railBadge(tab, showBadge = true, newUpdates = 3, extensionUpdates = 2) shouldBe 0
        }
    }

    @Test
    fun `opening Updates clears its count, and no other tab does`() {
        AppShell.clearsUpdatesBadge(HomeTab.Updates, newUpdates = 4) shouldBe true
        AppShell.clearsUpdatesBadge(HomeTab.Updates, newUpdates = 0) shouldBe false
        AppShell.clearsUpdatesBadge(HomeTab.Library, newUpdates = 4) shouldBe false
        // As it is: a negative count (never written by the app) shows no badge but is still cleared
        AppShell.railBadge(HomeTab.Updates, showBadge = true, newUpdates = -1, extensionUpdates = 0) shouldBe 0
        AppShell.clearsUpdatesBadge(HomeTab.Updates, newUpdates = -1) shouldBe true
    }
}
