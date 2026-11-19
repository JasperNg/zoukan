package zoukan.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.navigator.Navigator
import zoukan.desktop.ui.more.SettingsScreen

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

/** Per-window home tab navigation and library-search controller; see [LocalHomeController]. */
@Stable
class HomeController(val tabs: HomeTabState = HomeTabState()) {
    private var librarySearchHandler: ((String) -> Unit)? = null
    private var pendingLibrarySearch: String? = null

    fun selectTab(tab: HomeTab) {
        tabs.select(tab)
    }

    fun openSettings(navigator: Navigator) {
        navigator.popUntilRoot()
        selectTab(HomeTab.More)
        navigator.push(SettingsScreen)
    }

    fun searchLibrary(query: String) {
        tabs.select(HomeTab.Library)
        librarySearchHandler?.invoke(query) ?: run {
            pendingLibrarySearch = query
        }
    }

    internal fun bindLibrarySearch(handler: (String) -> Unit): () -> Unit {
        librarySearchHandler = handler
        pendingLibrarySearch?.let { pending ->
            pendingLibrarySearch = null
            handler(pending)
        }
        return {
            if (librarySearchHandler === handler) {
                librarySearchHandler = null
            }
        }
    }

    companion object {
        val Saver = Saver<HomeController, String>(
            save = { it.tabs.selected.name },
            restore = { HomeController(HomeTabState(HomeTab.valueOf(it))) },
        )
    }
}

val LocalHomeController = staticCompositionLocalOf<HomeController?> { null }
