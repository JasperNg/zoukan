package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import kotlin.time.Duration.Companion.minutes

/**
 * A webtoon reader with "Always show chapter transition" off who scrolls with the wheel to the
 * end of a chapter must find a way on, not a last page with nothing below it.
 *
 *   ./gradlew :desktop:journeyTest --tests "*WebtoonEndOfChapterJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class WebtoonEndOfChapterJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `scrolling a webtoon chapter to its end with the wheel goes on to the next chapter`() {
        journey = Journey.start("webtoon-end")
        val preferences = Injekt.get<DesktopPreferences>()
        preferences.alwaysShowChapterTransition().set(false)
        preferences.defaultReadingMode().set(ReadingModes.WEBTOON)
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        // Chapters 1 and 2 are back to back, so the transition between them is skipped
        val chapter1 = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.first { it.name == "Chapter 1 (JPEG)" }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(ReaderScreen(manga.id, chapter1.id)) }
                waitOrDump("for the first page", 30_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }

                step("The wheel takes the strip to its end and a way on shows") {
                    repeat(6) {
                        root().performMouseInput { repeat(40) { scroll(1200f) } }
                        waitForIdle()
                    }
                    waitOrDump("for a way to the next chapter", 10_000) {
                        onAllNodes(hasText("Next chapter")).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("1-end")
                }
            }
        }
    }
}
