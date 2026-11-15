package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.update.Updates
import kotlin.time.Duration.Companion.minutes

/**
 * The first thing a reader does: browse a source, open a title, add it to the library and read
 * it. Reads the JPEG, PNG and WebP chapters of the fixture title and checks each page drew on
 * screen (AVIF has its own journey, [AvifJourneyTest]).
 *
 *   ./gradlew :desktop:journeyTest --tests "*BrowseAndReadJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class BrowseAndReadJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun browseOpenAndReadEveryFormat() {
        journey = Journey.start("browse-read")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            step("The journey is offline: no update check") {
                Updates.autoCheck().get() shouldBe false
            }

            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Browse ${FixtureSource.NAME}") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    // The bonus title is off by default
                    onAllNodes(hasTestTag("manga-item") and hasText(FixtureCatalog.BONUS_TITLE, substring = true), useUnmergedTree = true)
                        .fetchSemanticsNodes() shouldHaveSize 0
                    // The covers come from the fixture server too
                    waitOrDump("for the covers") { (1..3).all { server.imageRequests("cover-$it.png") > 0 } }
                    shot("1-source")
                }

                step("Open ${FixtureCatalog.FORMATS_TITLE} and add it to the library") {
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    // The button turns on once the chapter list has loaded
                    waitFor(hasText("Start") and isEnabled())
                    FixtureCatalog.formats.forEachIndexed { i, format ->
                        waitFor(hasText("Chapter ${i + 1} (${format.uppercase()})"))
                    }
                    onAllNodesWithText("Add to library").onFirst().performClick()
                    waitFor(hasText("In library"))
                    shot("2-manga")
                }

                FixtureCatalog.formats.filter { it != "avif" }.forEachIndexed { i, format ->
                    step("Read chapter ${i + 1} (${format.uppercase()})") {
                        if (i == 0) {
                            onAllNodesWithText("Start").onFirst().performClick()
                        } else {
                            onAllNodesWithText("Chapter ${i + 1} (${format.uppercase()})").onFirst().performClick()
                        }
                        readThrough(FixtureCatalog.pages(format), "3-$format")
                        FixtureCatalog.pages(format).forEach { server.imageRequests(it.file) shouldBeGreaterThan 0 }
                        runOnUiThread { navigator?.pop() }
                        waitFor(hasText("In library"))
                    }
                }
            }
        }

        runBlocking {
            val favorites = Injekt.get<GetFavorites>().await()
            favorites.map { it.title } shouldContainExactlyInAnyOrder listOf(FixtureCatalog.FORMATS_TITLE)
            val chapters = Injekt.get<GetChaptersByMangaId>().await(favorites.single().id)
            chapters shouldHaveSize FixtureCatalog.formats.size
            // Reading to the last page marks a chapter read
            chapters.filter { it.read }.map { it.name } shouldContainExactlyInAnyOrder
                listOf("Chapter 1 (JPEG)", "Chapter 2 (PNG)", "Chapter 3 (WEBP)")
            Injekt.get<GetHistory>().subscribe("", null, null, null).first().isNotEmpty() shouldBe true
        }
    }
}
