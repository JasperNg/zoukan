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
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Reads a chapter whose pages the site serves scrambled, as some sites do: the page squeezed into
 * one half of the image, garbage in the other. The source puts them back together in an OkHttp
 * interceptor with android.graphics (BitmapFactory, Bitmap.createBitmap, Canvas.drawBitmap with
 * source and destination rects, Bitmap.compress), as descrambling extensions do.
 *
 * Checks that each page (JPEG, PNG and AVIF) draws with far more of its colour than its scrambled
 * image has (about half), and that the last page, an image URL the site answers with HTML, which
 * the interceptor passes on when BitmapFactory returns null, shows the reader's error and the
 * reader goes on working.
 *
 *   ./gradlew :desktop:journeyTest --tests "*DescramblingJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class DescramblingJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun readAScrambledChapter() {
        journey = Journey.start("descrambling")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val pages = FixtureCatalog.pages(FixtureCatalog.SCRAMBLED)
        val error = "Page ${pages.size + 1} could not load"

        journey.step("The scrambled images are about half their page's colour") {
            pages.forEach { page ->
                val image = javaClass.getResourceAsStream("/e2e-fixtures/images/${page.file}")!!.use { ImageIO.read(it) }.shouldNotBeNull()
                journey.share(image, page) shouldBeLessThan SCRAMBLED_SHARE
            }
        }
        val manga = journey.addToLibrary(FixtureCatalog.SCRAMBLED_TITLE)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Read the scrambled chapter: every page drew whole") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 1 (scrambled)"))
                    onAllNodesWithText("Chapter 1 (scrambled)").onFirst().performClick()
                    readThrough(pages, "scrambled", minShare = DESCRAMBLED_SHARE)
                    pages.forEach { server.imageRequests(it.file) shouldBeGreaterThan 0 }
                    source.descrambled.get() shouldBeGreaterThanOrEqual pages.size
                }

                step("The page the site answers with HTML shows the reader's error") {
                    // Turn only while a page drew and the error is not on screen; a slow decode only makes this slower
                    var turns = 0
                    while (true) {
                        var shown = emptyList<FixturePage>()
                        runCatching {
                            waitUntil(timeoutMillis = Journey.PAGE_TIMEOUT) {
                                shown = pagesShown(pages, DESCRAMBLED_SHARE)
                                textOnScreen(error) || (shown.isNotEmpty() && !loadingOnScreen())
                            }
                        }
                        if (textOnScreen(error) || shown.isEmpty() || turns >= 3) break
                        println("Journey $name: the error is not on screen; showing ${shown.map { it.file }}, turn $turns")
                        root().performKeyInput { pressKey(Key.DirectionLeft) }
                        waitForIdle()
                        turns++
                    }
                    waitFor(hasText(error))
                    waitFor(hasText("Retry"))
                    shot("html-page")
                    server.imageRequests(FixtureCatalog.HTML_PAGE) shouldBeGreaterThan 0
                }

                step("The reader goes on: back to the first page") {
                    // Right turns back, past the HTML page and the AVIF and PNG pages. Each turn waits
                    // until the reader shows something known: a page, the error or the transition
                    val first = pages.first()
                    var turns = 0
                    while (true) {
                        var shown = emptyList<FixturePage>()
                        runCatching {
                            waitUntil(timeoutMillis = Journey.PAGE_TIMEOUT) {
                                shown = pagesShown(pages, DESCRAMBLED_SHARE)
                                first in shown ||
                                    ((shown.isNotEmpty() || textOnScreen(error) || textOnScreen(NO_PREVIOUS)) && !loadingOnScreen())
                            }
                        }
                        if (first in shown || turns++ >= 6) break
                        // One turn too many shows the previous chapter's transition
                        val transition = textOnScreen(NO_PREVIOUS)
                        if (shown.isEmpty() && !transition && !textOnScreen(error)) break
                        println("Journey $name: the first page is not on screen; showing ${shown.map { it.file }}, transition $transition, turn $turns")
                        root().performKeyInput { pressKey(if (transition) Key.DirectionLeft else Key.DirectionRight) }
                        waitForIdle()
                    }
                    waitOrDump("for the first page to draw again") { pageDrew(pages.first(), DESCRAMBLED_SHARE) }
                    shot("back-to-first")
                }
            }
        }
    }

    private companion object {
        /** A scrambled image is about half its colour (generate.py's pages measure 0.46). */
        const val SCRAMBLED_SHARE = 0.6

        /**
         * A page put back together is nearly all its colour (0.92 of the image; the frame and
         * number are not), less the reader's tap zones overlay over its edges when a chapter opens
         * (0.77 on screen). A scrambled one on screen is under 0.46.
         */
        const val DESCRAMBLED_SHARE = 0.7

        /** The transition before the first page of a chapter with none before it. */
        const val NO_PREVIOUS = "There is no previous chapter"
    }
}
