package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * One page of a chapter is a corrupt image (the server answers 200 with the format's magic and
 * zeros): the reader shows "Page 2 could not load" with the page's Retry, the other pages draw.
 * After the route is switched off, the page's Retry draws it. A page the source refuses (HTTP 403)
 * shows a readable reason under "Page n could not load", not the raw status line.
 *
 *   ./gradlew :desktop:journeyTest --tests "*CorruptPageJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class CorruptPageJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun corruptPageShowsErrorAndRetryDrawsIt() {
        journey = Journey.start("corrupt-page")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val pages = FixtureCatalog.pages(FixtureCatalog.formats.first())
        val first = pages[0]
        val corrupt = pages[1]
        val error = "Page 2 could not load"
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        journey.server.fail("/images/${corrupt.file}", FixtureFailure.CorruptImage)
        // The first page of chapter 2 is refused
        val refused = FixtureCatalog.pages(FixtureCatalog.formats[1]).first().file
        journey.server.extraRoutes += { exchange, parts ->
            if (parts.lastOrNull() == refused) {
                exchange.sendResponseHeaders(403, -1)
                true
            } else {
                false
            }
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Open chapter 1: page 1 draws, page 2 shows the reader's error") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 1 (JPEG)"))
                    onAllNodesWithText("Chapter 1 (JPEG)").onFirst().performClick()
                    // The first page draws; a spread shows the error beside it, otherwise one turn reaches it
                    var firstDrew = false
                    var turns = 0
                    while (true) {
                        runCatching {
                            waitUntil(timeoutMillis = Journey.PAGE_TIMEOUT) {
                                val settled = settledBeforeCapture()
                                firstDrew = firstDrew || pageDrew(first)
                                textOnScreen(error) || (firstDrew && settled)
                            }
                        }
                        if (textOnScreen(error) || turns >= 3) break
                        root().performKeyInput { pressKey(Key.DirectionLeft) }
                        waitForIdle()
                        turns++
                    }
                    waitOrDump("for the error of page 2 on screen after $turns turns") { textOnScreen(error) && textOnScreen("Retry") }
                    check(firstDrew) { "page 1 never drew" }
                    pageShare(corrupt) shouldBeLessThan 0.25
                    server.imageRequests(corrupt.file) shouldBeGreaterThan 0
                    shot("1-corrupt-page")
                }

                step("The route is off: the page's Retry draws page 2") {
                    server.clearFailures()
                    onAllNodesWithText("Retry").fetchSemanticsNodes().indexOfFirst { it.isOnScreen() }.let { index ->
                        check(index >= 0) { "no Retry on screen" }
                        onAllNodesWithText("Retry")[index].performClick()
                    }
                    waitOrDump("for page 2 to draw", timeout = Journey.PAGE_TIMEOUT) { pageDrew(corrupt) }
                    textOnScreen(error) shouldBe false
                    shot("2-retried")
                }

                step("A page the source refuses (403): a readable reason, not the raw status") {
                    // No silent second try: the error shows on the first answer
                    preferences.readerInstantRetry().set(false)
                    val chapter2 = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.first { it.name == "Chapter 2 (PNG)" }
                    runOnUiThread {
                        navigator?.pop()
                        navigator?.push(ReaderScreen(manga.id, chapter2.id))
                    }
                    waitOrDump("for the page error", 30_000) {
                        onAllNodes(hasText(localized(DMR.strings.page_could_not_load, 1))).fetchSemanticsNodes().isNotEmpty()
                    }
                    onAllNodes(hasText(localized(MR.strings.exception_http, 403))).fetchSemanticsNodes().isNotEmpty() shouldBe true
                    onAllNodes(hasText("HTTP 403")).fetchSemanticsNodes().isEmpty() shouldBe true
                    shot("3-refused-page")
                }
            }
        }
    }
}
