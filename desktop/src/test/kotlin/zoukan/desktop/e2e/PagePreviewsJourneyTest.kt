package zoukan.desktop.e2e

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * The entry screen's page previews and related titles, with the fixture source serving both:
 * the thumbnails draw from the server, clicking the third opens the reader at page 3, More
 * previews lists them all, the related titles come from the source only and one opens its own
 * entry, and a thumbnail whose image is corrupt draws nothing while the others do.
 *
 *   ./gradlew :desktop:journeyTest --tests "*PagePreviewsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class PagePreviewsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun previewsOpenTheReaderAndRelatedTitlesOpenTheirEntries() {
        journey = Journey.start("previews")
        // The same source (same name, same id) with previews and related titles on, in place of the default one
        val entrySource = FixtureSource(journey.server.baseUrl, previews = true, related = true)
        Injekt.get<BuiltInSources>().testSources.value = listOf(entrySource)
        journey.awaitListed(entrySource)
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE, from = entrySource)
        val third = journey.addToLibrary("Fixture Third", from = entrySource)
        val jpeg = FixtureCatalog.pages("jpeg")
        val png = FixtureCatalog.pages("png")
        // One of the third title's previews is not an image
        journey.server.fail("/images/${png[1].file}", FixtureFailure.CorruptImage)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("The previews of ${FixtureCatalog.FORMATS_TITLE} drew from the server") {
                    runOnUiThread { navigator?.push(MangaScreen(formats.id)) }
                    jpeg.forEachIndexed { i, page ->
                        waitFor(hasContentDescription("Page ${i + 1}"))
                        onAllNodes(hasContentDescription("Page ${i + 1}")).onFirst().performScrollTo()
                        waitOrDump("for preview ${i + 1} (${page.file}) to draw") { previewShare(i + 1, page) >= 0.25 }
                        server.imageRequests(page.file) shouldBeGreaterThan 0
                    }
                    shot("1-previews")
                }

                step("Clicking the third preview opens the reader at page 3") {
                    val chapter = runBlocking { Injekt.get<GetChaptersByMangaId>().await(formats.id).minBy { it.sourceOrder } }
                    val pages = FixtureCatalog.pages(chapter.url.substringAfterLast('/'))
                    println("Journey $name: the reader opens ${chapter.name}")
                    onAllNodes(hasContentDescription("Page 3")).onFirst().performScrollTo().performClick()
                    waitOrDump("for page 3 (${pages[2].file}) of ${chapter.name} to draw") { pageDrew(pages[2]) }
                    pageShare(pages[0]) shouldBeLessThan 0.25
                    shot("2-reader-at-page-3")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasContentDescription("Page 3"))
                }

                step("More previews lists every preview") {
                    onAllNodesWithText("More previews").onFirst().performScrollTo().performClick()
                    waitFor(hasText("Page previews"))
                    jpeg.forEachIndexed { i, page ->
                        waitFor(hasContentDescription("Page ${i + 1}"))
                        waitOrDump("for preview ${i + 1} of the list to draw") { previewShare(i + 1, page) >= 0.25 }
                    }
                    shot("3-all-previews")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText("Related titles"))
                }

                step("The related titles are two other titles from the source; opening one opens its entry") {
                    onAllNodesWithText("Related titles").onFirst().performScrollTo()
                    waitFor(hasText("Fixture Second"))
                    waitFor(hasText("Fixture Third"))
                    // Every path of the app's lookup has answered and the row shows all it got
                    waitOrDump("for the related-titles lookup to finish") { entrySource.relatedLookups.get() > 0 }
                    waitForIdle()
                    // From the source only. The app's search for the title's words ("fixture") would
                    // also bring the search-only title below, which the source does not relate
                    onAllNodesWithText(FixtureCatalog.MISSING_COVER_TITLE).fetchSemanticsNodes() shouldHaveSize 0
                    shot("4-related")
                    onAllNodesWithText("Fixture Third").onFirst().performClick()
                    // Its own entry: its description, not the first title's
                    waitFor(hasText("A third title."))
                }

                step("A corrupt preview image draws nothing while the others draw") {
                    png.forEachIndexed { i, page ->
                        waitFor(hasContentDescription("Page ${i + 1}"))
                        onAllNodes(hasContentDescription("Page ${i + 1}")).onFirst().performScrollTo()
                    }
                    waitOrDump("for the first and third previews of ${third.title}") {
                        previewShare(1, png[0]) >= 0.25 && previewShare(3, png[2]) >= 0.25
                    }
                    waitOrDump("for the corrupt image to be asked for") { server.imageRequests(png[1].file) > 0 }
                    waitForIdle()
                    previewShare(2, png[1]) shouldBeLessThan 0.25
                    shot("5-corrupt-preview")
                }
            }
        }
    }

    /** The share of the preview thumbnail numbered [index] that is [page]'s colour. */
    private fun ComposeUiTest.previewShare(index: Int, page: FixturePage): Double {
        val node = onAllNodes(hasContentDescription("Page $index")).onFirst()
        val image = runCatching { node.captureToImage().toAwtImage() }.getOrNull() ?: return 0.0
        return journey.share(image, page)
    }
}
