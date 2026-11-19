package zoukan.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ShortcutsTest {

    private fun action(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        Shortcuts.actionFor(key, ctrl, shift, alt)

    @Test
    fun `ctrl and a digit open the tabs`() {
        action(Key.One, ctrl = true) shouldBe Shortcuts.Action.OpenTab(HomeTab.Library)
        action(Key.Four, ctrl = true) shouldBe Shortcuts.Action.OpenTab(HomeTab.Browse)
        action(Key.Five, ctrl = true) shouldBe Shortcuts.Action.OpenTab(HomeTab.More)
        action(Key.Comma, ctrl = true) shouldBe Shortcuts.Action.OpenSettings
        action(Key.One) shouldBe null
        action(Key.One, ctrl = true, alt = true) shouldBe null
    }

    @Test
    fun `update and back`() {
        action(Key.U, ctrl = true, shift = true) shouldBe Shortcuts.Action.UpdateLibrary
        action(Key.U, ctrl = true) shouldBe null
        action(Key.Escape) shouldBe Shortcuts.Action.Back
        action(Key.DirectionLeft, alt = true) shouldBe Shortcuts.Action.Back
        action(Key.DirectionLeft) shouldBe null
    }

    @Test
    fun `find and select all`() {
        action(Key.F, ctrl = true) shouldBe Shortcuts.Action.Find
        action(Key.F) shouldBe null
        action(Key.F, ctrl = true, shift = true) shouldBe null
        action(Key.A, ctrl = true) shouldBe Shortcuts.Action.SelectAll
        action(Key.A) shouldBe null
    }

    @Test
    fun `separate ShortcutControllers isolate tab selection and screen handlers`() {
        var tabOne: HomeTab? = null
        var tabTwo: HomeTab? = null
        val controllerOne = ShortcutController(selectTab = { tabOne = it })
        val controllerTwo = ShortcutController(selectTab = { tabTwo = it })

        val callsOne = mutableListOf<Shortcuts.Action>()
        val callsTwo = mutableListOf<Shortcuts.Action>()
        controllerOne.addScreenHandler {
            callsOne += it
            it == Shortcuts.Action.Find
        }
        controllerTwo.addScreenHandler {
            callsTwo += it
            it == Shortcuts.Action.SelectAll
        }

        controllerOne.perform(Shortcuts.Action.Find, navigator = null, escape = false) shouldBe true
        callsOne shouldBe listOf(Shortcuts.Action.Find)
        callsTwo shouldBe emptyList()

        controllerOne.perform(Shortcuts.Action.OpenTab(HomeTab.Browse), navigator = null, escape = false) shouldBe true
        tabOne shouldBe HomeTab.Browse
        tabTwo shouldBe null
    }

    @Test
    fun `OnShortcut registers only on the LocalShortcuts controller of its window`() = runDesktopComposeUiTest {
        val windowOne = ShortcutController()
        val windowTwo = ShortcutController()
        var showOne by mutableStateOf(true)
        val handledOne = mutableListOf<Shortcuts.Action>()
        val handledTwo = mutableListOf<Shortcuts.Action>()

        setContent {
            if (showOne) {
                CompositionLocalProvider(LocalShortcuts provides windowOne) {
                    OnShortcut { action ->
                        handledOne += action
                        action == Shortcuts.Action.Find
                    }
                }
            }
            CompositionLocalProvider(LocalShortcuts provides windowTwo) {
                OnShortcut { action ->
                    handledTwo += action
                    action == Shortcuts.Action.Find
                }
            }
        }
        waitForIdle()

        runOnUiThread {
            windowOne.perform(Shortcuts.Action.Find, navigator = null, escape = false) shouldBe true
        }
        handledOne shouldBe listOf(Shortcuts.Action.Find)
        handledTwo shouldBe emptyList()

        // Disposing window one removes only window one's handler
        runOnUiThread { showOne = false }
        waitForIdle()
        runOnUiThread {
            windowOne.perform(Shortcuts.Action.Find, navigator = null, escape = false) shouldBe false
            windowTwo.perform(Shortcuts.Action.Find, navigator = null, escape = false) shouldBe true
        }
        handledTwo shouldBe listOf(Shortcuts.Action.Find)
    }
}

private data class Plain(val name: String) : Screen {
    override val key: ScreenKey = "back-guard-plain-$name/"

    @Composable
    override fun Content() {
        Text(name)
    }
}

private class Guarded(val asked: MutableList<Shortcuts.Action>) : Screen {
    override val key: ScreenKey = "back-guard-guarded/"

    @Composable
    override fun Content() {
        // Stays for anything but Find and Select all, as a screen with unsaved work would
        OnShortcut { action ->
            asked += action
            action != Shortcuts.Action.Find && action != Shortcuts.Action.SelectAll
        }
        Text("guarded")
    }
}

/**
 * Escape, Alt+Left, the mouse back button and the tab shortcuts all leave the shown screen, so
 * each must ask it first: a screen with unsaved work answers by staying.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsBackGuardTest {

    @Test
    fun `a screen with its own back guard is asked before every global way out`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val asked = mutableListOf<Shortcuts.Action>()
        setContent {
            Navigator(Plain("root")) {
                navigator = it
                CurrentScreen()
            }
        }
        runOnUiThread { navigator!!.push(Guarded(asked)) }
        waitForIdle()
        val ways = listOf(
            "Escape" to { Shortcuts.perform(Shortcuts.Action.Back, navigator, escape = true) },
            "Alt+Left" to { Shortcuts.perform(Shortcuts.Action.Back, navigator, escape = false) },
            "mouse back" to { Shortcuts.goBack(navigator) },
            "Ctrl+1" to { Shortcuts.perform(Shortcuts.Action.OpenTab(HomeTab.Library), navigator, escape = false) },
        )
        val left = mutableListOf<String>()
        ways.forEach { (name, run) ->
            runOnUiThread { run() }
            waitForIdle()
            if (navigator!!.lastItem !is Guarded) {
                left += name
                // Put the guarded screen back for the next way out
                runOnUiThread { navigator!!.push(Guarded(asked)) }
                waitForIdle()
            }
        }
        left.shouldBeEmpty()
    }

    @Test
    fun `the mouse back button pops the stack, other buttons and the app lock do not`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        setContent {
            Navigator(Plain("root")) {
                navigator = it
                CurrentScreen()
            }
        }
        runOnUiThread { navigator!!.push(Plain("second")) }
        waitForIdle()
        runOnUiThread {
            Shortcuts.mouseBack(PointerButton.Primary, locked = false, navigator) shouldBe false
            Shortcuts.mouseBack(PointerButton.Back, locked = true, navigator) shouldBe false
        }
        waitForIdle()
        navigator!!.size shouldBe 2
        runOnUiThread { Shortcuts.mouseBack(PointerButton.Back, locked = false, navigator) shouldBe true }
        waitForIdle()
        navigator!!.size shouldBe 1
    }
}
