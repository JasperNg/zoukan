package zoukan.desktop.ui.reader

import androidx.compose.ui.input.key.Key
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.ReaderKeys.Action

class ReaderKeysTest {
    @Test
    fun `left and right follow flipped taps, up and down follow vertical ones, J and K stay physical`() {
        ReaderKeys.actionFor(Key.DirectionRight) shouldBe Action.NEXT_PAGE
        ReaderKeys.actionFor(Key.DirectionRight, flipped = true) shouldBe Action.PREVIOUS_PAGE
        ReaderKeys.actionFor(Key.DirectionLeft, flipped = true) shouldBe Action.NEXT_PAGE
        ReaderKeys.actionFor(Key.DirectionDown, verticalFlipped = true) shouldBe Action.SCROLL_UP
        ReaderKeys.actionFor(Key.J, verticalFlipped = true) shouldBe Action.SCROLL_DOWN
        ReaderKeys.actionFor(Key.K, verticalFlipped = true) shouldBe Action.SCROLL_UP
    }

    @Test
    fun `keys the app owns go on to its shortcuts`() {
        // Alt + Left and Right are Back and Forward, Ctrl/Cmd + F is Find, Ctrl/Cmd + 1 a tab
        ReaderKeys.actionFor(Key.DirectionLeft, alt = true) shouldBe null
        ReaderKeys.actionFor(Key.DirectionRight, alt = true) shouldBe null
        ReaderKeys.actionFor(Key.F, ctrl = true) shouldBe null
        ReaderKeys.actionFor(Key.One, ctrl = true) shouldBe null
        ReaderKeys.actionFor(Key.F) shouldBe Action.TOGGLE_FULLSCREEN
    }

    @Test
    fun `shift turns back and boosts, and every action has a binding the crawl presses`() {
        ReaderKeys.actionFor(Key.Spacebar, shift = true) shouldBe Action.PREVIOUS_PAGE
        ReaderKeys.actionFor(Key.R, shift = true) shouldBe Action.BOOST_PAGE
        ReaderKeys.actionFor(Key.R) shouldBe Action.RETRY_PAGES
        ReaderKeys.bindings.mapNotNull { ReaderKeys.actionFor(it.key, shift = it.shift) }.toSet() shouldContainExactlyInAnyOrder Action.entries
    }
}
