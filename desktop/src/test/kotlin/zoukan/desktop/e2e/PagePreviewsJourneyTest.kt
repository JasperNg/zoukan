package zoukan.desktop.e2e

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
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
import zoukan.desktop.ui.manga.PagePreviewScreen
import kotlin.time.Duration.Companion.minutes

/**
 * The entry screen's page previews and related titles, with the fixture source serving both:
 * the thumbnails draw from the server, clicking the third opens the reader at page 3, More
 * previews lists them all, the related titles come from the source only and one opens its own
 * entry, a thumbnail whose image is corrupt draws nothing while the others do, and the previews
 * screen loads the next page of previews only when its grid is scrolled near the end.
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

                step("The previews screen loads the next page of previews as its grid scrolls") {
                    // The same source again, now with two pages of 60 previews; a title whose
                    // previews no step has loaded, so none are cached from the one-page source
                    val paged = FixtureSource(server.baseUrl, previews = true, previewPages = 2, previewsPerPage = PER_PAGE)
                    Injekt.get<BuiltInSources>().testSources.value = listOf(paged)
                    awaitListed(paged)
                    val second = addToLibrary("Fixture Second", from = paged)
                    runOnUiThread { navigator?.push(PagePreviewScreen(second.id)) }
                    waitFor(hasContentDescription("Page 1"))
                    waitForIdle()
                    // The first page does not reach the end of the window: nothing more is asked for yet
                    paged.previewPagesAsked shouldBe listOf(1)
                    onAllNodes(hasContentDescription("Page ${PER_PAGE + 1}")).fetchSemanticsNodes() shouldHaveSize 0
                    shot("6-previews-first-page")
                    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToIndex(PER_PAGE - 1)
                    waitOrDump("for the second page to be asked for") { paged.previewPagesAsked.contains(2) }
                    waitFor(hasContentDescription("Page ${PER_PAGE + 1}"))
                    // The second page goes on numbering and cycling through the chapter's pages: 61 is jpeg-1's colour
                    val next = onAllNodes(hasContentDescription("Page ${PER_PAGE + 1}")).onFirst()
                    next.performScrollTo()
                    val jpeg = FixtureCatalog.pages("jpeg")
                    waitOrDump("for preview ${PER_PAGE + 1} to draw") { previewShare(PER_PAGE + 1, jpeg[PER_PAGE % jpeg.size]) >= 0.25 }
                    // The last page says there is no next one: the end of the grid asks for nothing more
                    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToIndex(2 * PER_PAGE - 1)
                    waitFor(hasContentDescription("Page ${2 * PER_PAGE}"))
                    waitForIdle()
                    paged.previewPagesAsked shouldBe listOf(1, 2)
                    shot("7-previews-second-page")
                    runOnUiThread { navigator?.pop() }
                }
            }
        }
    }

    private companion object {
        /** More previews than the window shows, so the first page leaves the grid's end out of sight. */
        const val PER_PAGE = 60
    }

    /** The share of the preview thumbnail numbered [index] that is [page]'s colour. */
    private fun ComposeUiTest.previewShare(index: Int, page: FixturePage): Double {
        val node = onAllNodes(hasContentDescription("Page $index")).onFirst()
        val image = runCatching { node.captureToImage().toAwtImage() }.getOrNull() ?: return 0.0
        return journey.share(image, page)
    }
}
