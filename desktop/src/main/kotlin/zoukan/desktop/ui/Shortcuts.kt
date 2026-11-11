package zoukan.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import cafe.adriel.voyager.navigator.Navigator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.common.Toasts

/**
 * The app-wide keyboard shortcuts. They run only when the focused screen did not use the key, so
 * the reader's keys and text fields win. Ctrl on Windows and Linux is Cmd on macOS.
 */
object Shortcuts {
    /** What a shortcut does, independent of Compose, so it can be tested. */
    sealed interface Action {
        data class OpenTab(val tab: HomeTab) : Action
        data object OpenSettings : Action
        data object UpdateLibrary : Action
        data object Back : Action
    }

    /** Shortcut list for the README and the help in Settings. */
    val list = listOf(
        "Ctrl/Cmd + 1 to 5" to "Library, Updates, History, Browse, More",
        "Ctrl/Cmd + ," to "Settings",
        "Ctrl/Cmd + Shift + U" to "Update the library",
        "Escape, Alt + Left, mouse back button" to "Back",
    )

    fun actionFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): Action? {
        if (ctrl && !alt && !shift && key == Key.Comma) return Action.OpenSettings
        if (ctrl && !alt && !shift) {
            val tab = when (key) {
                Key.One, Key.NumPad1 -> HomeTab.Library
                Key.Two, Key.NumPad2 -> HomeTab.Updates
                Key.Three, Key.NumPad3 -> HomeTab.History
                Key.Four, Key.NumPad4 -> HomeTab.Browse
                Key.Five, Key.NumPad5 -> HomeTab.More
                else -> null
            }
            if (tab != null) return Action.OpenTab(tab)
        }
        if (ctrl && shift && !alt && key == Key.U) return Action.UpdateLibrary
        if (!ctrl && !shift && (key == Key.Escape || (alt && key == Key.DirectionLeft))) return Action.Back
        return null
    }

    /** Handles [event] if it is a shortcut; returns whether it was. */
    fun handle(event: KeyEvent, navigator: Navigator?): Boolean {
        // Nothing works behind the app lock
        if (Injekt.get<zoukan.desktop.security.AppLock>().locked.value) return false
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        return when (val action = actionFor(event.key, ctrl, event.isShiftPressed, event.isAltPressed)) {
            is Action.OpenTab -> {
                navigator?.popUntilRoot()
                HomeScreen.selectTab(action.tab)
                true
            }
            Action.OpenSettings -> {
                navigator?.let { HomeScreen.openSettings(it) }
                navigator != null
            }
            Action.UpdateLibrary -> {
                if (!Injekt.get<LibraryUpdater>().start()) {
                    Toasts.show("A library update is already running")
                } else {
                    Toasts.show("Updating the library")
                }
                true
            }
            Action.Back -> if (navigator != null && navigator.canPop) {
                navigator.pop()
                true
            } else {
                false
            }
            null -> false
        }
    }
}
