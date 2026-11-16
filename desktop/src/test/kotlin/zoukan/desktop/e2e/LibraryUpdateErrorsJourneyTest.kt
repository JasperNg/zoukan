package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * The library update errors screen through the real UI, offline: an entry whose folder is gone
 * fails its update, the screen lists it, selecting it, then the folder is back and Retry updates
 * the entry and clears the error.
 *
 *   ./gradlew :desktop:journeyTest --tests "*LibraryUpdateErrorsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class LibraryUpdateErrorsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun failedUpdateThenRetry() {
        journey = Journey.start("library-update-errors")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val updater = Injekt.get<LibraryUpdater>()
        val favorites = LocalFixtures.seedParityLibrary(storage)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }

                step("Library update errors: a failed entry, then a retry that fixes it") {
                    // Charlie's folder is gone, so its update fails
                    val charlie = favorites.first { it.title == "Charlie" }
                    val folder = File(storage.local, "Charlie")
                    val moved = File(storage.local.parentFile, "Charlie-moved")
                    folder.renameTo(moved) shouldBe true
                    val failed = CountDownLatch(1)
                    updater.start(mangaIds = setOf(charlie.id)) { failed.countDown() }
                    failed.await(2, TimeUnit.MINUTES) shouldBe true
                    updater.lastResult.value!!.failures.map { it.manga.id } shouldBe listOf(charlie.id)
                    waitOrDump("for the update to end", 10_000) { updater.progress.value == null }

                    byDescription("More").performClick()
                    onAllNodesWithText("Library update errors").onFirst().performClick()
                    waitOrDump("for the error row", 30_000) { onAllNodesWithTag("update-error").fetchSemanticsNodes().size == 1 }
                    waitFor(hasText("Library update errors (1)"))
                    shot("errors")

                    // Right-click selects, as in the library
                    onAllNodesWithTag("update-error").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("1 selected"))
                    shot("errors-selected")
                    byDescription("Clear selection").performClick()

                    // The folder is back: retrying updates Charlie and its error goes
                    moved.renameTo(folder) shouldBe true
                    onAllNodes(
                        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Retry")),
                    ).onFirst().performClick()
                    waitFor(hasText("You have no library update errors."), 120_000)
                    onAllNodesWithTag("update-error").fetchSemanticsNodes().size shouldBe 0
                    updater.lastResult.value!!.failures.size shouldBe 0
                    shot("errors-empty")
                }
            }
        }
    }
}
