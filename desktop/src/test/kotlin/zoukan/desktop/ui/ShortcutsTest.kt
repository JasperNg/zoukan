package zoukan.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
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
