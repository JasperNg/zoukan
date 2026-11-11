package zoukan.desktop.ui

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
}
