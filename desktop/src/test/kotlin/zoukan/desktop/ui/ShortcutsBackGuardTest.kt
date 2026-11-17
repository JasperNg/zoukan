package zoukan.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
