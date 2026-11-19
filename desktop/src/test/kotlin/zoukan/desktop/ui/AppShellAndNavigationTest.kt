package zoukan.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.window.Notification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR

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

@OptIn(ExperimentalTestApi::class)
class HomeTabSelectionTest {
    private val withoutUpdates = HomeTab.entries - HomeTab.Updates

    @Test
    fun `a tab the rail hides is not selected by its shortcut`() {
        val tabs = HomeTabState(HomeTab.Browse).apply { showOnly(withoutUpdates) }
        tabs.select(HomeTab.Updates)
        tabs.selected shouldBe HomeTab.Browse
    }

    @Test
    fun `turning off the shown tab goes back to the library`() {
        val tabs = HomeTabState(HomeTab.Updates)
        tabs.showOnly(withoutUpdates)
        tabs.selected shouldBe HomeTab.Library
    }

    @Test
    fun `a tab chosen before the home screen is shown is applied when it is`() {
        HomeScreen.selectTab(HomeTab.Browse)
        val tabs = HomeTabState()
        HomeScreen.register(tabs)
        try {
            tabs.selected shouldBe HomeTab.Browse
        } finally {
            HomeScreen.unregister(tabs)
        }
    }

    @Test
    fun `two window-scoped HomeControllers isolate tab selection and library search`() {
        val windowOne = HomeController()
        val windowTwo = HomeController()
        val searchesOne = mutableListOf<String>()
        val searchesTwo = mutableListOf<String>()
        val unbindOne = windowOne.bindLibrarySearch { searchesOne += it }
        val unbindTwo = windowTwo.bindLibrarySearch { searchesTwo += it }
        try {
            windowOne.selectTab(HomeTab.Browse)
            windowTwo.selectTab(HomeTab.Updates)
            windowOne.tabs.selected shouldBe HomeTab.Browse
            windowTwo.tabs.selected shouldBe HomeTab.Updates

            windowOne.searchLibrary("alpha")
            windowOne.tabs.selected shouldBe HomeTab.Library
            windowTwo.tabs.selected shouldBe HomeTab.Updates
            searchesOne shouldBe listOf("alpha")
            searchesTwo shouldBe emptyList()
        } finally {
            unbindOne()
            unbindTwo()
        }
    }

    @Test
    fun `LocalHomeController scopes tab updates to each window composition`() = runDesktopComposeUiTest {
        val windowOne = HomeController()
        val windowTwo = HomeController()

        setContent {
            Column {
                CompositionLocalProvider(LocalHomeController provides windowOne) {
                    val home = LocalHomeController.current!!
                    Button(
                        onClick = { home.selectTab(HomeTab.Browse) },
                        modifier = Modifier.testTag("select-browse-one"),
                    ) { Text("One") }
                }
                CompositionLocalProvider(LocalHomeController provides windowTwo) {
                    val home = LocalHomeController.current!!
                    Button(
                        onClick = { home.selectTab(HomeTab.More) },
                        modifier = Modifier.testTag("select-more-two"),
                    ) { Text("Two") }
                }
            }
        }
        waitForIdle()

        onNodeWithTag("select-browse-one").performClick()
        waitForIdle()
        windowOne.tabs.selected shouldBe HomeTab.Browse
        windowTwo.tabs.selected shouldBe HomeTab.Library

        onNodeWithTag("select-more-two").performClick()
        waitForIdle()
        windowOne.tabs.selected shouldBe HomeTab.Browse
        windowTwo.tabs.selected shouldBe HomeTab.More
    }
}
