package zoukan.desktop.ui.manga

import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.SourceFeedScreen
import zoukan.desktop.ui.browse.SourceScreen

/**
 * The searches the entry page starts from its title, author, artist, source and tags, as
 * Android's MangaScreen performSearch and performGenreSearch: a search goes back to the screen
 * the entry was opened from (a source's browse or feed, or the library), or opens a global search.
 */
object EntrySearch {

    /**
     * Searches [query] globally, or in the browse screen or library the entry was opened from; with
     * [library], always in the library (Komikku's Library search).
     */
    fun search(navigator: Navigator, query: String, global: Boolean, library: Boolean = false) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) return

        navigator.popUntil { screen ->
            screen is HomeScreen ||
                (!library && (screen is SourceScreen || screen is SourceFeedScreen))
        }

        when (val previous = navigator.lastItem) {
            is HomeScreen -> HomeScreen.searchLibrary(query)
            is SourceScreen -> SourceScreen.search(query)
            is SourceFeedScreen -> navigator.push(SourceScreen(previous.sourceId, query = query))
        }
    }

    /**
     * Searches by [genreName] in the source's browse screen the entry was opened from, turning on
     * the filter of that name, or else as [search] does.
     */
    fun searchGenre(navigator: Navigator, genreName: String, source: Source?) {
        if (navigator.size < 2) return

        var idx = navigator.size - 2
        while (idx >= 0) {
            val previous = navigator.items[idx--]
            if (previous is SourceScreen && source is HttpSource) {
                navigator.popUntil { navigator.size == idx + 2 }
                SourceScreen.searchGenre(genreName)
                return
            }
            if (previous is SourceFeedScreen && source is HttpSource) {
                navigator.popUntil { navigator.size == idx + 2 }
                navigator.push(SourceScreen(previous.sourceId, query = ""))
                SourceScreen.searchGenre(genreName)
                return
            }
        }
        search(navigator, genreName, global = false)
    }

    /**
     * Opens [source] from the entry page's source name, as Komikku's browseSource: its feed with
     * "Use new source navigation" on, else its popular list, replacing the same kind of screen
     * the entry was opened from.
     */
    fun browseSource(navigator: Navigator, source: Source) {
        val newNavigation = Injekt.get<UiPreferences>().useNewSourceNavigation().get()
        if (newNavigation) {
            navigator.popUntil { it is SourceFeedScreen }
            val screen = SourceFeedScreen(source.id)
            if (navigator.size > 1) navigator.replace(screen) else navigator.push(screen)
        } else {
            navigator.popUntil { it is SourceScreen }
            val screen = SourceScreen(source.id)
            if (navigator.size > 1) navigator.replace(screen) else navigator.push(screen)
        }
    }
}
