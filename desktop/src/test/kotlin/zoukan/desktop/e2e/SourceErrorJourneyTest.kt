package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A source that answers with errors: the catalogue (500), a title's details (404) and its chapter
 * list (500). Each shows the error the user reads and a retry (the "Retry" button, the entry's
 * "Refresh") that works once the server answers again. Every call fails while a rule is on, so a
 * silent client retry cannot hide the error. A failed load adds nothing to the library.
 *
 * The fixture serves a title's details and its chapters from one URL (`/manga/<key>`), so the
 * details and the chapter list fail together: a title whose first load fails has neither, which
 * is what the 404 and the 500 steps show (no details, "No chapters").
 *
 *   ./gradlew :desktop:journeyTest --tests "*SourceErrorJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SourceErrorJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun errorsShowAndRetryWorks() {
        journey = Journey.start("source-error")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("The catalogue answers 500: the error and a Retry, no titles") {
                    server.fail("/catalog", FixtureFailure.Status(500))
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitFor(hasText("HTTP error 500"))
                    waitFor(hasText("Retry"))
                    onAllNodesWithTag("manga-item").fetchSemanticsNodes() shouldHaveSize 0
                    shot("1-catalogue-500")
                }

                step("The server answers again: Retry lists the catalogue and its covers") {
                    val failed = server.requests.count { it == "/catalog" }
                    server.clearFailures()
                    onAllNodesWithText("Retry").onFirst().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    (server.requests.count { it == "/catalog" } shouldBeGreaterThan failed)
                    waitOrDump("for the covers") { (1..3).all { server.imageRequests("cover-$it.png") > 0 } }
                    onAllNodesWithText("HTTP error 500").fetchSemanticsNodes() shouldHaveSize 0
                    shot("2-catalogue-retried")
                }

                step("A title's details answer 404: the error, no chapters, nothing in the library") {
                    server.fail("/manga/", FixtureFailure.Status(404))
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    waitFor(hasText("HTTP error 404"))
                    waitFor(hasText("No chapters"))
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("3-details-404")
                }

                step("The server answers again: Refresh loads the details and the chapters") {
                    server.clearFailures()
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitFor(hasText("Start") and isEnabled())
                    FixtureCatalog.formats.forEachIndexed { i, format ->
                        waitFor(hasText("Chapter ${i + 1} (${format.uppercase()})"))
                    }
                    onAllNodesWithText("HTTP error 404").fetchSemanticsNodes() shouldHaveSize 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("4-details-refreshed")
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the catalogue again") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                }

                step("A title's chapter list answers 500: the error and no chapters") {
                    server.fail("/manga/", FixtureFailure.Status(500))
                    onAllNodesWithText("Fixture Second").onFirst().performClick()
                    waitFor(hasText("HTTP error 500"))
                    waitFor(hasText("No chapters"))
                    // Failing every call: the client did not hide it with a retry that worked
                    server.requests.count { it == "/manga/second" } shouldBeGreaterThan 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("5-chapters-500")
                }

                step("The server answers again: Refresh lists the chapters") {
                    server.clearFailures()
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitFor(hasText("Start") and isEnabled())
                    waitFor(hasText("Chapter 1", substring = true))
                    onAllNodesWithText("HTTP error 500").fetchSemanticsNodes() shouldHaveSize 0
                    onAllNodesWithText("No chapters").fetchSemanticsNodes() shouldHaveSize 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("6-chapters-refreshed")
                }
            }
        }
    }
}
