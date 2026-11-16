package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderCompositionProbe
import zoukan.desktop.ui.reader.ReaderScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Turning a page must not re-run the composables of a pager page whose inputs did not change.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ReaderRecompositionJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ReaderRecompositionJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `turning a page does not recompose a page that did not change`() {
        journey = Journey.start("reader-recomposition")
        val preferences = Injekt.get<DesktopPreferences>()
        preferences.pageLayout().set(0)
        preferences.pageTransitionsPager().set(false)
        preferences.showNavigationOverlayNewUser().set(false)
        preferences.showPageNumber().set(true)
        preferences.defaultReadingMode().set(ReadingModes.RIGHT_TO_LEFT)
        var navigator: Navigator? = null
        val pages = FixtureCatalog.pages(FixtureCatalog.formats.first())
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val chapter1 = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.first { it.name == "Chapter 1 (JPEG)" }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(ReaderScreen(manga.id, chapter1.id)) }
                waitOrDump("for page 1 to draw", timeout = Journey.PAGE_TIMEOUT) { pageDrew(pages[0]) }
                waitOrDump("for the page number", 10_000) {
                    onAllNodes(hasText("1 / 3", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                ReaderCompositionProbe.pageRecompositions.clear()

                step("Turn to page 2: the page beside it was not recomposed") {
                    root().performKeyInput { pressKey(Key.DirectionLeft) }
                    waitOrDump("for the page number 2 / 3", 10_000) {
                        onAllNodes(hasText("2 / 3", substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                    waitForIdle()
                    // Index 2 was composed before and after the turn and its inputs did not change
                    (ReaderCompositionProbe.pageRecompositions[2] ?: 0) shouldBe 0
                }
            }
        }
    }
}
