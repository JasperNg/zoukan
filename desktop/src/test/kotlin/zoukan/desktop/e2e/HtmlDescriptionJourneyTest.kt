package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * A title whose description the site sends as HTML (`Tom &amp; Jerry<br>Second line &lt;3`),
 * which the source turns into text with android.text.Html.fromHtml, as extensions do. Searches the
 * source for it (it is search-only), opens it and checks the details show the decoded text on two
 * lines, with no entity or tag left.
 *
 * Then two more search-only titles that stress what the entry screen draws: the CJK title
 * (Japanese and Korean, from a system fallback font; its screenshot shows whether the glyphs drew)
 * and the title whose cover the server answers 404, which shows the broken-cover placeholder.
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

                step("Search for the CJK title and open it: its title and description show") {
                    openFromSearch(navigator, query = "東京の漫画", title = FixtureCatalog.CJK_TITLE)
                    waitFor(hasText(FixtureCatalog.CJK_DESCRIPTION, substring = true))
                    // For a look at the glyphs: they draw from a system CJK font, or as boxes without one
                    shot("3-cjk")
                }

                step("Open the title whose cover is missing: the placeholder shows, nothing breaks") {
                    openFromSearch(navigator, query = "Without Cover", title = FixtureCatalog.MISSING_COVER_TITLE)
                    waitOrDump("for the cover placeholder") {
                        server.requests.contains("/images/${FixtureCatalog.MISSING_COVER}") &&
                            // Unmerged: the clickable cover merges its content, and a merge keeps no child's tag
                            onAllNodesWithTag("cover-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("4-missing-cover")
                    onAllNodes(hasTestTag("entry-title") and hasText(FixtureCatalog.MISSING_COVER_TITLE)).fetchSemanticsNodes() shouldHaveSize 1
                }
            }
        }
    }

    /** Searches the source for [query] and opens [title], waiting for its entry screen. */
    private fun ComposeUiTest.openFromSearch(navigator: Navigator?, query: String, title: String) = with(journey) {
        runOnUiThread { navigator?.push(SourceScreen(source.id, query = query)) }
        waitFor(hasTestTag("manga-item") and hasText(title))
        onAllNodes(hasTestTag("manga-item") and hasText(title)).onFirst().performClick()
        waitFor(hasTestTag("entry-title") and hasText(title))
        waitForIdle()
    }

    /** Every text on screen. */
    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
