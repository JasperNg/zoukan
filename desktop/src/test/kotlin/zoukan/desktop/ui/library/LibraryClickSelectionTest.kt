package zoukan.desktop.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Selecting in the library as in a file manager: Ctrl/Cmd + click toggles, Shift + click selects a range. */
class LibraryClickSelectionTest {

    @Test
    fun `modifier clicks toggle one entry and select a range from the last toggled one`() {
        clickKind(shift = false, ctrl = false, meta = false) shouldBe ClickKind.Plain
        clickKind(shift = false, ctrl = true, meta = false) shouldBe ClickKind.Toggle
        clickKind(shift = false, ctrl = false, meta = true) shouldBe ClickKind.Toggle
        clickKind(shift = true, ctrl = true, meta = false) shouldBe ClickKind.Range

        val shown = (1L..10L).toList()
        // Ctrl + click 3, then Shift + click 7: entries 3 to 7
        var selection = toggledSelection(emptySet(), 3L)
        selection = selection + rangeSelection(shown, anchor = 3L, id = 7L)!!
        selection.size shouldBe 5
        // Ctrl + click 5 takes it out again
        toggledSelection(selection, 5L).size shouldBe 4
        // Shift + click backwards from the anchor, and with no anchor shown: only the clicked entry
        rangeSelection(shown, anchor = 7L, id = 2L) shouldBe listOf(2L, 3L, 4L, 5L, 6L, 7L)
        rangeSelection(shown, anchor = 42L, id = 4L) shouldBe listOf(4L)
        rangeSelection(shown, anchor = 3L, id = 42L) shouldBe null
    }
}
