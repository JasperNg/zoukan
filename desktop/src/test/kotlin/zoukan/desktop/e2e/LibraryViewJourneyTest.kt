package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.manga.interactor.CreateSortTag
import eu.kanade.domain.manga.interactor.DeleteSortTag
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * The library's views through the real UI, offline: display modes, the filter, sort and display
 * sheet, selecting an entry and marking it read (and the unread filter that follows), the search
 * syntax, tag sorting and the Updates filter sheet.
 *
 *   ./gradlew :desktop:journeyTest --tests "*LibraryViewJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class LibraryViewJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun libraryViewsFiltersAndSearch() {
        journey = Journey.start("library-view")
        val preferences = Injekt.get<DesktopPreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val favorites = LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                fun items() = onAllNodesWithTag("manga-item").fetchSemanticsNodes().size

                step("Library display modes") {
                    waitOrDump("for the 3 entries", 30_000) { items() == 3 }
                    library.downloadBadge().set(true)
                    shot("compact")
                    library.displayMode().set(LibraryDisplayMode.ComfortableGrid)
                    shot("comfortable")
                    library.displayMode().set(LibraryDisplayMode.List)
                    library.showContinueReadingButton().set(true)
                    shot("list")
                    library.displayMode().set(LibraryDisplayMode.CompactGrid)
                    waitOrDump("for the grid", 10_000) { items() == 3 }
                }

                step("Filter sheet and a filter") {
                    byDescription("Filter, sort and display").performClick()
                    waitFor(hasText("Downloaded"))
                    shotWithPopups("filter-sheet")
                    onAllNodesWithText("Sort").onFirst().performClick()
                    waitFor(hasText("Total chapters"))
                    shotWithPopups("sort-sheet")
                    onAllNodesWithText("Close").onFirst().performClick()
                    // Nothing is read yet, so "unread only" hides none of the entries
                    library.filterUnread().set(TriState.ENABLED_IS)
                    waitOrDump("for the unread filter", 10_000) { items() == 3 }
                    library.filterUnread().set(TriState.DISABLED)
                }

                step("Select an entry and mark it read") {
                    waitOrDump("for the 3 entries", 10_000) { items() == 3 }
                    // A right click opens the entry's menu; Select there starts the selection
                    onAllNodesWithText("Bravo").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    shotWithPopups("selection")
                    byDescription("Mark as read").performClick()
                    val bravo = favorites.first { it.title == "Bravo" }
                    waitOrDump("for Bravo's chapters to be read", 10_000) {
                        runBlocking { Injekt.get<GetChaptersByMangaId>().await(bravo.id).all { it.read } }
                    }
                    // Bravo is read, so "unread only" hides it
                    library.filterUnread().set(TriState.ENABLED_IS)
                    waitOrDump("for the unread filter to hide Bravo", 10_000) { items() == 2 }
                    shot("filtered-unread")
                    library.filterUnread().set(TriState.DISABLED)
                    waitOrDump("for the 3 entries", 10_000) { items() == 3 }
                }

                step("Library search syntax, global search row and KMK display options") {
                    // SY's search, with src:local for local entries; the global search row sits above the
                    // results. (A "-word" exclusion never keeps a local entry, as Komikku's filterManga.)
                    onAllNodes(hasSetTextAction()).onFirst().performTextInput("src:local")
                    waitFor(hasTestTag("library-global-search"))
                    onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                    onAllNodes(hasSetTextAction()).onFirst().performTextInput("bravo")
                    waitOrDump("for the search to leave Bravo", 10_000) { items() == 1 }
                    shot("search")
                    onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                    waitOrDump("for the 3 entries", 10_000) { items() == 3 }
                    byDescription("Filter, sort and display").performClick()
                    waitFor(hasText("Lewd"))
                    waitFor(hasText("Categories"))
                    onAllNodesWithText("Display").onFirst().performClick()
                    waitFor(hasText("Show hidden categories"))
                    waitFor(hasText("Source icon"))
                    waitFor(hasText("Use language icon"))
                    shotWithPopups("display-kmk")
                    onAllNodesWithText("Close").onFirst().performClick()
                }

                step("Tag sorting and the Updates filters") {
                    // Charlie has the sort tag, so a descending tag sort puts it first
                    val charlie = favorites.first { it.title == "Charlie" }
                    runBlocking {
                        Injekt.get<MangaRepository>().update(MangaUpdate(id = charlie.id, genre = listOf("female: sole female")))
                    }
                    Injekt.get<CreateSortTag>().await("female: sole female")
                    library.sortingMode().set(LibrarySort(LibrarySort.Type.TagList, LibrarySort.Direction.Descending))
                    fun SemanticsNode.hasTextDeep(text: String): Boolean =
                        config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text } || children.any { it.hasTextDeep(text) }
                    waitOrDump("for Charlie to lead the tag sort", 10_000) {
                        onAllNodesWithTag("manga-item", useUnmergedTree = true).onFirst().fetchSemanticsNode().hasTextDeep("Charlie")
                    }
                    byDescription("Filter, sort and display").performClick()
                    onAllNodesWithText("Sort").onFirst().performClick()
                    waitFor(hasText("Tag sorting"))
                    shotWithPopups("tag-sorting")
                    onAllNodesWithText("Close").onFirst().performClick()
                    library.sortingMode().set(LibrarySort.default)
                    Injekt.get<DeleteSortTag>().await("female: sole female")

                    runOnUiThread { HomeScreen.selectTab(HomeTab.Updates) }
                    byDescription("Filter").performClick()
                    waitFor(hasText("Bookmarked"))
                    shotWithPopups("updates-filter")
                    onAllNodesWithText("Close").onFirst().performClick()
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                    waitOrDump("for the 3 entries", 10_000) { items() == 3 }
                }
            }
        }
    }
}
