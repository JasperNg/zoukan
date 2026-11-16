package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * The screens with nothing to show, on a fresh data folder: the library, the history, the
 * download queue, a source search and a global search that find nothing. Each shows its message
 * (and its call to action), and one title in the library replaces the library's message.
 *
 *   ./gradlew :desktop:journeyTest --tests "*EmptyStatesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class EmptyStatesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun emptyScreensShowTheirMessages() {
        journey = Journey.start("empty-states")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                fun count(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().size

                step("Empty library: the message and a way to Browse") {
                    onAllNodesWithText("Library").onFirst().performClick()
                    waitFor(hasText(LIBRARY_EMPTY))
                    count("manga-item") shouldBe 0
                    shot("1-library-empty")
                    // The rail's Browse and the message's action share a label: the action is the last one
                    val browse = onAllNodesWithText("Browse")
                    browse.fetchSemanticsNodes().size shouldBeGreaterThanOrEqual 2
                    browse[browse.fetchSemanticsNodes().size - 1].performClick()
                    waitFor(hasText("Sources"))
                    onAllNodesWithText("Library").onFirst().performClick()
                    waitFor(hasText(LIBRARY_EMPTY))
                }

                step("Empty history: the message, also under a search") {
                    onAllNodesWithText("History").onFirst().performClick()
                    waitFor(hasText(HISTORY_EMPTY))
                    count("history-item") shouldBe 0
                    shot("2-history-empty")
                    onAllNodes(hasSetTextAction()).onFirst().performTextInput(NO_MATCH)
                    waitFor(hasText("Nothing matches \"$NO_MATCH\""))
                    count("history-item") shouldBe 0
                    shot("2-history-search-empty")
                }

                step("Empty download queue") {
                    onAllNodesWithText("More").onFirst().performClick()
                    waitFor(hasText("Download queue"))
                    onAllNodesWithText("Download queue").onFirst().performClick()
                    waitFor(hasText(DOWNLOADS_EMPTY))
                    shot("3-downloads-empty")
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Source search with no results") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for the catalogue") { count("manga-item") == 3 }
                    val catalogueRequests = server.requests.count { it == "/catalog" }
                    onAllNodes(hasSetTextAction()).onFirst().apply {
                        performTextInput(NO_MATCH)
                        performKeyInput { pressKey(Key.Enter) }
                    }
                    waitFor(hasText("Nothing found"))
                    count("manga-item") shouldBe 0
                    // The search went to the server (the path is recorded without its query)
                    server.requests.count { it == "/catalog" } shouldBeGreaterThan catalogueRequests
                    shot("4-source-search-empty")
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Global search with no results; Has results hides the source") {
                    runOnUiThread { navigator?.push(GlobalSearchScreen(NO_MATCH)) }
                    waitFor(hasTestTag("global-search-has-results"))
                    waitFor(hasText("No results"))
                    waitFor(hasText(FixtureSource.NAME))
                    shot("5-global-search-empty")
                    onAllNodes(hasTestTag("global-search-has-results")).onFirst().performClick()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithText(FixtureSource.NAME).fetchSemanticsNodes().isEmpty()
                    }
                    shot("5-global-search-has-results")
                    onAllNodes(hasTestTag("global-search-has-results")).onFirst().performClick()
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("One title in the library replaces the empty message") {
                    addToLibrary(FixtureCatalog.FORMATS_TITLE)
                    onAllNodesWithText("Library").onFirst().performClick()
                    waitOrDump("for the library entry") { count("manga-item") == 1 }
                    onAllNodesWithText(LIBRARY_EMPTY).fetchSemanticsNodes().size shouldBe 0
                    shot("6-library-one")
                }
            }
        }
    }

    private companion object {
        const val NO_MATCH = "zzz-no-such-title"

        // DMR.strings.your_library_is_empty_find_something, nothing_read_yet,
        // no_downloads_queued_download_chapters_from
        const val LIBRARY_EMPTY = "Your library is empty. Find something to read in Browse and add it to your library."
        const val HISTORY_EMPTY = "Nothing read yet"
        const val DOWNLOADS_EMPTY = "No downloads queued. Download chapters from an entry's chapter list."
    }
}
