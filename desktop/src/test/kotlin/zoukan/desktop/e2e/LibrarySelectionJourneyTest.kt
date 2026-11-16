package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * Selecting library entries through the real UI, offline: the More actions of a two-entry
 * selection (Merge, Find recommendations and its dialog), Download > Bookmarked, and Update on
 * one entry. Alpha's and Charlie's chapters are bookmarked before the window opens.
 *
 *   ./gradlew :desktop:journeyTest --tests "*LibrarySelectionJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class LibrarySelectionJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun selectionMoreDownloadAndUpdate() {
        journey = Journey.start("library-selection")
        val preferences = Injekt.get<DesktopPreferences>()
        val updater = Injekt.get<LibraryUpdater>()
        val favorites = LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        // Download > Bookmarked needs bookmarks; this journey makes its own
        runBlocking {
            val chapters = favorites.filter { it.title == "Alpha" || it.title == "Charlie" }
                .flatMap { Injekt.get<GetChaptersByMangaId>().await(it.id) }
            chapters.size shouldBe 7
            Injekt.get<ChapterRepository>().updateAll(chapters.map { ChapterUpdate(id = it.id, bookmark = true) })
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }

                step("Library selection: Update and the More actions") {
                    // A right click opens the entry's menu; Select there starts the selection, then a click adds
                    onAllNodesWithText("Alpha").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    onAllNodesWithText("Charlie").onFirst().performClick()
                    waitFor(hasText("2 selected"))
                    byDescription("More").performClick()
                    // Merge always; Find recommendations with two or more; the source-specific ones only when they apply
                    waitFor(hasText("Merge"))
                    waitFor(hasText("Find recommendations"))
                    onAllNodesWithText("Clean titles").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Reset Info").fetchSemanticsNodes().size shouldBe 0
                    shotWithPopups("more")
                    // The recommendation search dialog: its services and the library filter; Cancel keeps the selection
                    onAllNodesWithText("Find recommendations").onFirst().performClick()
                    waitFor(hasText("Recommendation services to search"))
                    waitFor(hasText("Hide results already in your library"))
                    shotWithPopups("recommendation-search")
                    onAllNodesWithText("Cancel").onLast().performClick()
                    waitOrDump("for the dialog to close", 10_000) { onAllNodesWithText("Recommendation services to search").fetchSemanticsNodes().isEmpty() }
                    waitFor(hasText("2 selected"))
                    // Alpha's and Charlie's chapters are bookmarked; local entries have nothing to download
                    byDescription("Download").performClick()
                    onAllNodesWithText("Bookmarked").onLast().performClick()
                    waitOrDump("for the selection to end", 10_000) { onAllNodes(textMatches(Regex(".* selected"))).fetchSemanticsNodes().isEmpty() }
                    onAllNodesWithText("Alpha").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    // The seed's own update left a result; this one must replace it
                    val seedResult = updater.lastResult.value
                    byDescription("Update").performClick()
                    waitOrDump("for the selection to end", 10_000) { onAllNodes(textMatches(Regex(".* selected"))).fetchSemanticsNodes().isEmpty() }
                    waitOrDump("for the update to end", 60_000) { updater.lastResult.value !== seedResult && updater.progress.value == null }
                    updater.lastResult.value!!.failures.shouldBeEmpty()
                }
            }
        }
    }
}
