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
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A source that answers with errors: the catalogue too late (a timeout), the catalogue (500), a title's details and chapters (404) and
 * only its chapter list (500; the details call answers, but the source API returns details and
 * chapters as one update, so the screen has neither). Each shows the error the user reads and a retry
 * (the "Retry" button, the entry's "Refresh") that works once the server answers again. Every call
 * fails while a rule is on, so a silent client retry cannot hide the error. A failed load adds
 * nothing to the library. Last, a source that blocks the global search (HTTP 403, as a Cloudflare
 * check does): its row explains that the site blocked the request instead of the raw status, and
 * its Retry searches that one source again.
 *
 * The app's client waits 30 s to read; the fixture source here has a 3 s read timeout (a test-only
 * setting of [FixtureSource]) and the slow catalogue answers after 5 s, so the timeout step takes
 * seconds, not minutes.
 *
 * The fixture serves a title's details at `/manga/<key>` and its chapter list at
 * `/manga/<key>/chapters`, so the 404 rule (`/manga/`) fails both and the 500 rule only the list.
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
        journey = Journey.start("source-error", sourceReadTimeoutSeconds = READ_TIMEOUT_SECONDS)
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("The catalogue answers after ${SLOW_MILLIS / 1000} s, past the $READ_TIMEOUT_SECONDS s timeout: the timeout error") {
                    server.fail("/catalog", FixtureFailure.Slow(SLOW_MILLIS))
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitFor(hasText(TIMEOUT_MESSAGE), timeout = 20_000)
                    waitFor(hasText("Retry"))
                    onAllNodesWithTag("manga-item").fetchSemanticsNodes() shouldHaveSize 0
                    shot("0-timeout")
                }

                step("The catalogue answers 500 to Retry: the error and a Retry, no titles") {
                    val slow = server.requests.count { it == "/catalog" }
                    server.clearFailures()
                    server.fail("/catalog", FixtureFailure.Status(500))
                    onAllNodesWithText("Retry").onFirst().performClick()
                    waitFor(hasText("HTTP error 500"))
                    waitFor(hasText("Retry"))
                    (server.requests.count { it == "/catalog" } shouldBeGreaterThan slow)
                    onAllNodesWithText(TIMEOUT_MESSAGE).fetchSemanticsNodes() shouldHaveSize 0
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
                    waitFor(hasText("HTTP 404, check website in WebView"))
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
                    onAllNodesWithText("HTTP 404, check website in WebView").fetchSemanticsNodes() shouldHaveSize 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("4-details-refreshed")
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the catalogue again") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                }

                step("Only a title's chapter list answers 500: the error and no chapters") {
                    server.fail("/manga/second/chapters", FixtureFailure.Status(500))
                    onAllNodesWithText("Fixture Second").onFirst().performClick()
                    waitFor(hasText("HTTP 500, check website in WebView"))
                    waitFor(hasText("No chapters"))
                    // The details call answered; the source API returns details and chapters as one
                    // update, so the chapter error drops both until Refresh
                    server.requests.count { it == "/manga/second" } shouldBeGreaterThan 0
                    // Failing every call: the client did not hide it with a retry that worked
                    server.requests.count { it == "/manga/second/chapters" } shouldBeGreaterThan 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("5-chapters-500")
                }

                step("The server answers again: Refresh loads the details and lists the chapters") {
                    server.clearFailures()
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitFor(hasText("Start") and isEnabled())
                    waitFor(hasText("Chapter 1", substring = true))
                    waitFor(hasText(SECOND_DESCRIPTION, substring = true))
                    onAllNodesWithText("HTTP 500, check website in WebView").fetchSemanticsNodes() shouldHaveSize 0
                    onAllNodesWithText("No chapters").fetchSemanticsNodes() shouldHaveSize 0
                    runBlocking { Injekt.get<GetFavorites>().await() }.shouldBeEmpty()
                    shot("6-chapters-refreshed")
                }

                step("Global search, the site answers 403: the row says the site blocked the request and offers Retry") {
                    server.fail("/catalog", FixtureFailure.Status(403))
                    runOnUiThread { navigator?.push(GlobalSearchScreen("Fixture")) }
                    waitFor(hasText(BLOCKED))
                    waitFor(hasText("Retry"))
                    onAllNodesWithText("HTTP error 403").fetchSemanticsNodes() shouldHaveSize 0
                    shot("7-global-search-blocked")
                }

                step("The site answers again: Retry on the row lists its results") {
                    val failed = server.requests.count { it == "/catalog" }
                    server.clearFailures()
                    onAllNodesWithText("Retry").onFirst().performClick()
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    server.requests.count { it == "/catalog" } shouldBeGreaterThan failed
                    onAllNodesWithText(BLOCKED).fetchSemanticsNodes() shouldHaveSize 0
                    shot("8-global-search-retried")
                }
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 3L
        const val SLOW_MILLIS = 5_000L

        /** What the source screen shows for OkHttp's read timeout (a SocketTimeoutException). */
        const val TIMEOUT_MESSAGE = "The source took too long to answer"

        const val BLOCKED = "The site blocked the request. Open the site to pass its check or sign in, then try again."

        /** The start of Fixture Second's description, shown once its details load. */
        const val SECOND_DESCRIPTION = "A second title"
    }
}
