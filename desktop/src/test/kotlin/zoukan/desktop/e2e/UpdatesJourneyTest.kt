package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
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
 * The Updates tab through the real UI, offline: a day's chapters of one entry fold into one row
 * that expands and collapses; selecting a folded group selects all its chapters, Select all takes
 * every chapter, and Bookmark bookmarks them all and ends the selection.
 *
 *   ./gradlew :desktop:journeyTest --tests "*UpdatesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class UpdatesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun foldedGroupsSelectionAndBookmark() {
        journey = Journey.start("updates")
        val preferences = Injekt.get<DesktopPreferences>()
        val favorites = LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        val total = LocalFixtures.chapterCount(favorites)
        total shouldBe 9

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }

                step("Updates: a day's chapters of one entry fold into one row that expands") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Updates) }
                    // Alpha, Bravo and Charlie each lead their chapters; the others stay folded
                    waitOrDump("for the 3 leaders", 30_000) { onAllNodesWithTag("updates-leader").fetchSemanticsNodes().size == 3 }
                    onAllNodesWithTag("updates-item").fetchSemanticsNodes().size shouldBe 0
                    shot("grouped")
                    byDescription("Expand").performClick()
                    waitOrDump("for the expanded rows", 10_000) { onAllNodesWithTag("updates-item").fetchSemanticsNodes().isNotEmpty() }
                    shot("expanded")
                    byDescription("Collapse").performClick()
                    waitOrDump("for the rows to fold", 10_000) { onAllNodesWithTag("updates-item").fetchSemanticsNodes().isEmpty() }
                }

                step("Updates selection: a folded group selects all its chapters, then bookmark them all") {
                    // KMK's group selection: the folded row stands for the entry's chapters of that day
                    onAllNodesWithTag("updates-leader").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitOrDump("for the group's chapters to be selected", 10_000) {
                        onAllNodes(textMatches(Regex("[234] selected"))).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("group-selected")
                    byDescription("Select all").performClick()
                    waitFor(hasText("$total selected"))
                    byDescription("Bookmark").performClick()
                    waitOrDump("for every chapter to be bookmarked", 10_000) {
                        runBlocking { favorites.all { m -> Injekt.get<GetChaptersByMangaId>().await(m.id).all { it.bookmark } } }
                    }
                    // The selection ends with the action, as on Android
                    waitOrDump("for the selection to end", 10_000) { onAllNodes(textMatches(Regex(".* selected"))).fetchSemanticsNodes().isEmpty() }
                }
            }
        }
    }
}
