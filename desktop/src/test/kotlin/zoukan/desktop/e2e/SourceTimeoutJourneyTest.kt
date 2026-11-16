package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A source that answers too late. The app's client waits 30 s to read; the fixture source here has
 * a 3 s read timeout (a test-only setting of [FixtureSource]) and the catalogue answers after 5 s,
 * so the user sees the timeout error with a Retry. Once the route is fast again, Retry lists the
 * catalogue. The whole journey takes well under a minute.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SourceTimeoutJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SourceTimeoutJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun timeoutShowsAndRetryWorks() {
        journey = Journey.start("source-timeout", sourceReadTimeoutSeconds = READ_TIMEOUT_SECONDS)
        val preferences = Injekt.get<DesktopPreferences>()

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) {} }

                step("The catalogue answers after ${SLOW_MILLIS / 1000} s, past the $READ_TIMEOUT_SECONDS s timeout: the timeout error") {
                    server.fail("/catalog", FixtureFailure.Slow(SLOW_MILLIS))
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitFor(hasText(TIMEOUT_MESSAGE), timeout = 20_000)
                    waitFor(hasText("Retry"))
                    onAllNodesWithTag("manga-item").fetchSemanticsNodes() shouldHaveSize 0
                    shot("1-timeout")
                }

                step("The server is fast again: Retry lists the catalogue") {
                    server.clearFailures()
                    onAllNodesWithText("Retry").onFirst().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    onAllNodesWithText(TIMEOUT_MESSAGE).fetchSemanticsNodes() shouldHaveSize 0
                    waitOrDump("for the covers") { (1..3).all { server.imageRequests("cover-$it.png") > 0 } }
                    shot("2-retried")
                }
            }
        }
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 3L
        const val SLOW_MILLIS = 5_000L

        /** What the source screen shows for OkHttp's read timeout (a SocketTimeoutException). */
        const val TIMEOUT_MESSAGE = "The source took too long to answer"
    }
}
