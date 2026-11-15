package zoukan.desktop.e2e

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
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
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * The entry screen's sections that come from the source: page previews and related titles. The
 * fixture source serves both when asked ([FixtureSource.previews], [FixtureSource.related]). Opens
 * "Fixture Formats" from Browse, checks the previews drew from the server and the related titles
 * list two other catalogue titles, then opens the reader from a preview at that page.
 *
 *   ./gradlew :desktop:journeyTest --tests "*EntrySectionsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class EntrySectionsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun previewsAndRelatedTitlesOnTheEntryScreen() {
        journey = Journey.start("entry-sections")
        // The same source (same name, same id) with previews and related titles on, in place of the default one
        val entrySource = FixtureSource(journey.server.baseUrl, previews = true, related = true)
        install(entrySource)
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val previews = FixtureCatalog.pages("jpeg")

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 5.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Open ${FixtureCatalog.FORMATS_TITLE} from Browse") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    waitFor(hasText("Chapter 1 (JPEG)"))
                }

                step("The page previews drew, fetched from the server") {
                    previews.forEachIndexed { i, page ->
                        waitFor(hasContentDescription("Page ${i + 1}"))
                        onAllNodes(hasContentDescription("Page ${i + 1}")).onFirst().performScrollTo()
                        waitOrDump("for preview ${i + 1} (${page.file}) to draw") { previewShare(i + 1, page) >= 0.25 }
                        server.imageRequests(page.file) shouldBeGreaterThan 0
                    }
                    shot("1-previews")
                }

                step("The related titles are two other catalogue titles") {
                    onAllNodesWithText("Related titles").onFirst().performScrollTo()
                    waitFor(hasText("Fixture Second"))
                    waitFor(hasText("Fixture Third"))
                    // From the source only: no search-only title, as the app's search would bring
                    onAllNodesWithText(FixtureCatalog.HTML_TITLE).fetchSemanticsNodes() shouldHaveSize 0
                    onAllNodesWithText(FixtureCatalog.SCRAMBLED_TITLE).fetchSemanticsNodes() shouldHaveSize 0
                    shot("2-related")
                }

                step("A preview opens the reader at its page") {
                    // The app opens the entry's first chapter in source order (the newest), as Android does
                    val chapter = runBlocking {
                        val manga = Injekt.get<GetManga>().await("/manga/formats", entrySource.id)!!
                        Injekt.get<GetChaptersByMangaId>().await(manga.id).minBy { it.sourceOrder }
                    }
                    val pages = FixtureCatalog.pages(chapter.url.substringAfterLast('/'))
                    println("Journey $name: the reader opens ${chapter.name}")
                    onAllNodes(hasContentDescription("Page 2")).onFirst().performScrollTo().performClick()
                    waitOrDump("for page 2 (${pages[1].file}) of ${chapter.name} to draw") { pageDrew(pages[1]) }
                    // At page 2, not at the start
                    pageShare(pages[0]) shouldBeLessThan 0.25
                    shot("3-reader")
                    runOnUiThread { navigator?.pop() }
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

    /** Puts [source] in place of the journey's fixture source and waits until the app lists it. */
    private fun install(source: FixtureSource) {
        Injekt.get<BuiltInSources>().testSources.value = listOf(source)
        journey.awaitListed(source)
    }
}
