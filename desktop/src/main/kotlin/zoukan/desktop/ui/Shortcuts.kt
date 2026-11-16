package zoukan.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.library.LibraryUpdater
import zoukan.i18n.desktop.DMR

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

        /** Ctrl/Cmd + F: the cursor goes to the screen's search box */
        data object Find : Action

        /** Ctrl/Cmd + A where the screen has a selection */
        data object SelectAll : Action

        /**
         * Asked before a global shortcut or the mouse back button leaves the shown screen; a
         * screen with unsaved work answers true and shows its own confirmation. No key gives this.
         */
        data object Leave : Action
    }

    /** The shown screen's own handlers for [Action.Find], [Action.SelectAll] and [Action.Back]; the newest is asked first. */
    private val screenHandlers = mutableListOf<(Action) -> Boolean>()

    fun addScreenHandler(handler: (Action) -> Boolean) {
        screenHandlers += handler
    }

    fun removeScreenHandler(handler: (Action) -> Boolean) {
        screenHandlers -= handler
    }

    private fun screenHandled(action: Action): Boolean = screenHandlers.toList().asReversed().any { it(action) }

    /** Shortcut list for the README and the help in Settings. */
    val list = listOf(
        "Ctrl/Cmd + 1 to 5" to localized(DMR.strings.library_updates_history_browse_more),
        "Ctrl/Cmd + ," to localized(MR.strings.action_settings),
        "Ctrl/Cmd + Shift + U" to localized(DMR.strings.update_the_library),
        "Ctrl/Cmd + F" to localized(DMR.strings.search_the_screen),
        "Ctrl/Cmd + A" to localized(DMR.strings.select_everything_in_the_library_or),
        localized(DMR.strings.escape) to localized(DMR.strings.empty_the_search_box_clear_the),
        "Alt + Left, mouse back button" to localized(MR.strings.action_webview_back),
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
        if (ctrl && !shift && !alt && key == Key.F) return Action.Find
        if (ctrl && !shift && !alt && key == Key.A) return Action.SelectAll
        if (!ctrl && !shift && (key == Key.Escape || (alt && key == Key.DirectionLeft))) return Action.Back
        return null
    }

    /** Handles [event] if it is a shortcut; returns whether it was. */
    fun handle(event: KeyEvent, navigator: Navigator?): Boolean {
        // Nothing works behind the app lock
        if (Injekt.get<zoukan.desktop.security.AppLock>().locked.value) return false
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        return perform(actionFor(event.key, ctrl, event.isShiftPressed, event.isAltPressed), navigator, escape = event.key == Key.Escape)
    }

    /** Goes back one screen, as the mouse back button does; returns whether there was a screen to go back to. */
    fun goBack(navigator: Navigator?): Boolean {
        // The shown screen may keep the user here, as the migration list does while it holds search results
        if (screenHandled(Action.Leave)) return true
        if (navigator != null && navigator.canPop) {
            navigator.pop()
            return true
        }
        return false
    }

    /** Does [action]; [escape] is whether the key was Escape. Returns whether it was used. */
    internal fun perform(action: Action?, navigator: Navigator?, escape: Boolean): Boolean {
        return when (action) {
            is Action.OpenTab -> if (screenHandled(Action.Leave)) {
                true
            } else {
                navigator?.popUntilRoot()
                HomeScreen.selectTab(action.tab)
                true
            }
            Action.OpenSettings -> if (screenHandled(Action.Leave)) {
                true
            } else {
                navigator?.let { HomeScreen.openSettings(it) }
                navigator != null
            }
            Action.UpdateLibrary -> {
                if (!Injekt.get<LibraryUpdater>().start()) {
                    Toasts.show(localized(DMR.strings.a_library_update_is_already_running))
                } else {
                    Toasts.show(localized(DMR.strings.updating_the_library))
                }
                true
            }
            Action.Find, Action.SelectAll -> screenHandled(action)
            // Escape clears a screen's selection before it goes back; Alt + Left only goes back
            Action.Back -> if (escape && screenHandled(action)) true else goBack(navigator)
            // Never produced by a key; screens are asked it through screenHandled
            Action.Leave -> false
            null -> false
        }
    }
}

/** Lets the screen that shows this answer a shortcut; [handler] returns whether it used it. */
@Composable
fun OnShortcut(handler: (Shortcuts.Action) -> Boolean) {
    val current by rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val registered: (Shortcuts.Action) -> Boolean = { current(it) }
        Shortcuts.addScreenHandler(registered)
        onDispose { Shortcuts.removeScreenHandler(registered) }
    }
}
