package zoukan.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** The selected home tab and the tabs the rail shows. */
@Stable
class HomeTabState(initial: HomeTab = HomeTab.Library) {
    var selected by mutableStateOf(initial)
        private set
    var shown by mutableStateOf(HomeTab.entries.toList())
        private set

    /** Selects [tab] unless the rail hides it; a hidden tab stays reachable from More. */
    fun select(tab: HomeTab) {
        if (tab in shown) selected = tab
    }

    /** Sets the tabs the rail shows, and leaves the selected tab for the library when it is no longer shown. */
    fun showOnly(tabs: List<HomeTab>) {
        shown = tabs
        if (selected !in tabs) selected = HomeTab.Library
    }

    companion object {
        val Saver = Saver<HomeTabState, String>(
            save = { it.selected.name },
            restore = { HomeTabState(HomeTab.valueOf(it)) },
        )
    }
}
