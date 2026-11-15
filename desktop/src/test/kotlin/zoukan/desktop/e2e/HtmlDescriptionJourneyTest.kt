package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A title whose description the site sends as HTML (`Tom &amp; Jerry<br>Second line &lt;3`),
 * which the source turns into text with android.text.Html.fromHtml, as extensions do. Searches the
 * source for it (it is search-only), opens it and checks the details show the decoded text on two
 * lines, with no entity or tag left.
 *
 *   ./gradlew :desktop:journeyTest --tests "*HtmlDescriptionJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class HtmlDescriptionJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun anHtmlDescriptionShowsAsText() {
        journey = Journey.start("html-description")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 5.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Search ${FixtureSource.NAME} for the title") {
                    runOnUiThread { navigator?.push(SourceScreen(source.id, query = "Markup")) }
                    waitOrDump("for the search result") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    waitFor(hasText(FixtureCatalog.HTML_TITLE))
                    shot("1-search")
                }

                step("Open it: the description is text, on two lines") {
                    onAllNodesWithText(FixtureCatalog.HTML_TITLE).onFirst().performClick()
                    waitFor(hasText("Tom & Jerry", substring = true))
                    shot("2-details")
                    val description = texts().first { "Tom & Jerry" in it }
                    println("Journey $name: the description reads ${description.replace("\n", "\\n")}")
                    description.lines().map { it.trim() }.filter { it.isNotEmpty() } shouldBe listOf("Tom & Jerry", "Second line <3")
                    texts().filter { "&amp;" in it || "&lt;" in it || "<br" in it } shouldBe emptyList()
                }
            }
        }
    }

    /** Every text on screen. */
    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
