package zoukan.desktop.ui.reader

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/**
 * The reader's keyboard map, independent of Compose, so it can be tested: which key does what.
 * [ReaderScreen] runs the action; a key with no action here goes on to the app's shortcuts.
 */
object ReaderKeys {
    enum class Action {
        NEXT_PAGE,
        PREVIOUS_PAGE,
        SCROLL_DOWN,
        SCROLL_UP,
        FIRST_PAGE,
        LAST_PAGE,
        NEXT_CHAPTER,
        PREVIOUS_CHAPTER,
        ZOOM_IN,
        ZOOM_OUT,
        ZOOM_RESET,
        TOGGLE_MENU,
        TOGGLE_FULLSCREEN,
        TOGGLE_HELP,
        // ZOUKAN -->
        TOGGLE_AUTO_SCROLL,
        TOGGLE_SETTINGS,
        TOGGLE_BOOKMARK,
        TOGGLE_CHAPTERS,
        RETRY_PAGES,
        BOOST_PAGE,
        SHIFT_DOUBLE_PAGES,
        // ZOUKAN <--

        /** Closes the help, then the menu, then fullscreen, then the reader. */
        ESCAPE,
    }

    /**
     * The action of [key]; null when the reader leaves it to the app. [flipped] is whether Left and
     * Right follow flipped taps (right to left, or inverted tap zones), [verticalFlipped] the same
     * for Up and Down; J and K stay physical.
     */
    fun actionFor(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        flipped: Boolean = false,
        verticalFlipped: Boolean = false,
    ): Action? = when (key) {
        // Alt + Left and Right are the app's Back and Forward
        Key.DirectionRight -> if (alt) {
            null
        } else if (flipped) {
            Action.PREVIOUS_PAGE
        } else {
            Action.NEXT_PAGE
        }
        Key.DirectionLeft -> if (alt) {
            null
        } else if (flipped) {
            Action.NEXT_PAGE
        } else {
            Action.PREVIOUS_PAGE
        }
        Key.DirectionDown -> if (verticalFlipped) Action.SCROLL_UP else Action.SCROLL_DOWN
        Key.DirectionUp -> if (verticalFlipped) Action.SCROLL_DOWN else Action.SCROLL_UP
        Key.J -> Action.SCROLL_DOWN
        Key.K -> Action.SCROLL_UP
        Key.Spacebar -> if (shift) Action.PREVIOUS_PAGE else Action.NEXT_PAGE
        Key.PageDown -> Action.NEXT_PAGE
        Key.PageUp -> Action.PREVIOUS_PAGE
        Key.MoveHome -> Action.FIRST_PAGE
        Key.MoveEnd -> Action.LAST_PAGE
        Key.N, Key.RightBracket -> Action.NEXT_CHAPTER
        Key.P, Key.LeftBracket -> Action.PREVIOUS_CHAPTER
        Key.Equals, Key.Plus, Key.NumPadAdd -> Action.ZOOM_IN
        Key.Minus, Key.NumPadSubtract -> Action.ZOOM_OUT
        Key.Zero, Key.NumPad0 -> Action.ZOOM_RESET
        Key.M, Key.Enter -> Action.TOGGLE_MENU
        // Ctrl/Cmd + F is the app's Find
        Key.F, Key.F11 -> if (ctrl) null else Action.TOGGLE_FULLSCREEN
        Key.Slash -> Action.TOGGLE_HELP
        // ZOUKAN -->
        Key.A -> Action.TOGGLE_AUTO_SCROLL
        Key.S -> Action.TOGGLE_SETTINGS
        Key.B -> Action.TOGGLE_BOOKMARK
        Key.C -> Action.TOGGLE_CHAPTERS
        Key.R -> if (shift) Action.BOOST_PAGE else Action.RETRY_PAGES
        Key.D -> Action.SHIFT_DOUBLE_PAGES
        // ZOUKAN <--
        Key.Escape -> Action.ESCAPE
        else -> null
    }

    /** One key per binding, named as the help names it: each action with a key that gives it, plus the variants that differ. For the button crawl and the tests. */
    val bindings: List<Binding> = listOf(
        Binding(Key.DirectionRight, "Right"), Binding(Key.DirectionLeft, "Left"),
        Binding(Key.DirectionDown, "Down"), Binding(Key.DirectionUp, "Up"),
        Binding(Key.J, "J"), Binding(Key.K, "K"), Binding(Key.Spacebar, "Space"), Binding(Key.Spacebar, "Shift+Space", shift = true),
        Binding(Key.PageDown, "Page Down"), Binding(Key.PageUp, "Page Up"), Binding(Key.MoveHome, "Home"), Binding(Key.MoveEnd, "End"),
        Binding(Key.N, "N"), Binding(Key.P, "P"), Binding(Key.Equals, "="), Binding(Key.Minus, "-"), Binding(Key.Zero, "0"),
        Binding(Key.M, "M"), Binding(Key.F, "F"), Binding(Key.Slash, "/"),
        // ZOUKAN -->
        Binding(Key.A, "A"), Binding(Key.S, "S"), Binding(Key.B, "B"), Binding(Key.C, "C"),
        Binding(Key.R, "R"), Binding(Key.R, "Shift+R", shift = true), Binding(Key.D, "D"),
        // ZOUKAN <--
        Binding(Key.Escape, "Escape"),
    )

    data class Binding(val key: Key, val name: String, val shift: Boolean = false)

    /**
     * What the reader's keys change that the screen does not name (the zoom, fullscreen, auto
     * scroll, the help): a semantics property for the tests, read when asked, so it is always
     * current. Screen readers do not see it.
     */
    data class State(
        val page: Int,
        val menu: Boolean,
        val zoom: Float,
        val fullscreen: Boolean,
        val help: Boolean,
        val autoScroll: Boolean,
        val settings: Boolean,
        val chapters: Boolean,
        /** Whether N and P have a chapter to go to. */
        val hasNext: Boolean,
        val hasPrevious: Boolean,
        /** The pages are shown, so a page key has a viewer to turn. */
        val ready: Boolean,
    )

    val StateKey = SemanticsPropertyKey<() -> State>("ReaderState")
    var SemanticsPropertyReceiver.readerState by StateKey
}
