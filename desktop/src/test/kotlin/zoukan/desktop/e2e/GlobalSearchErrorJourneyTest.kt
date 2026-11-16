package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A source that blocks the global search (HTTP 403, as a Cloudflare check does): its row explains
 * that the site blocked the request instead of the raw status, and its Retry searches that one
 * source again, which lists its results once the site answers.
 *
 *   ./gradlew :desktop:journeyTest --tests "*GlobalSearchErrorJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class GlobalSearchErrorJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun blockedSourceExplainsItselfAndRetryWorks() {
        journey = Journey.start("global-search-error")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("The site answers 403: the row says the site blocked the request and offers Retry") {
                    server.fail("/catalog", FixtureFailure.Status(403))
                    runOnUiThread { navigator?.push(GlobalSearchScreen("Fixture")) }
                    waitFor(hasText(BLOCKED))
                    waitFor(hasText("Retry"))
                    onAllNodesWithText("HTTP error 403").fetchSemanticsNodes() shouldHaveSize 0
                    shot("1-global-search-blocked")
                }

                step("The site answers again: Retry on the row lists its results") {
                    val failed = server.requests.count { it == "/catalog" }
                    server.clearFailures()
                    onAllNodesWithText("Retry").onFirst().performClick()
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    server.requests.count { it == "/catalog" } shouldBeGreaterThan failed
                    onAllNodesWithText(BLOCKED).fetchSemanticsNodes() shouldHaveSize 0
                    shot("2-global-search-retried")
                }
            }
        }
    }

    private companion object {
        const val BLOCKED = "The site blocked the request. Open the site to pass its check or sign in, then try again."
    }
}
