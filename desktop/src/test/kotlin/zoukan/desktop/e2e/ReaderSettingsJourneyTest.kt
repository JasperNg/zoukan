package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * The reader's settings through the real UI, offline: double pages (and keeping the page when
 * the layout changes), color filters, the settings dialog (scale type, filter tab, reader theme),
 * then the page actions: save the combined page, set a page as the cover, and a long press after
 * the layout changed. The pages are the chapter's CBZ colours.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ReaderSettingsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSettingsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        System.clearProperty("zoukan.picturesDir")
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun readerSettingsAndPageActions() {
        journey = Journey.start("reader-settings")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val favorites = LocalFixtures.seedParityLibrary(storage)
        var navigator: Navigator? = null
        val alpha = favorites.first { it.title == "Alpha" }
        val chapter = runBlocking { Injekt.get<GetChaptersByMangaId>().await(alpha.id) }.first { it.name == "Chapter 1" }
        // Chapter 1's pages, as the CBZ has them
        val pages = LocalFixtures.pages("Alpha 1", 4)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }

                step("Reader: double pages, filters and settings") {
                    preferences.pageLayout().set(1)
                    preferences.defaultReadingMode().set(ReadingModes.LEFT_TO_RIGHT)
                    runOnUiThread { navigator?.push(ReaderScreen(alpha.id, chapter.id)) }
                    waitOrDump("for the page", 30_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }
                    // The first reader ever shows the tap zones, as Android does for a new user; a key hides them
                    waitOrDump("for the tap zones", 10_000) { onAllNodesWithTag("tap-zones-overlay").fetchSemanticsNodes().isNotEmpty() }
                    shot("tap-zones")
                    // Pages 2 and 3 side by side
                    root().performKeyInput { pressKey(Key.DirectionRight) }
                    waitOrDump("for the tap zones to go", 10_000) { onAllNodesWithTag("tap-zones-overlay").fetchSemanticsNodes().isEmpty() }
                    waitOrDump("for two pages", 10_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().size >= 2 }
                    waitFor(hasText("2-3 / 4"), 10_000)
                    waitOrDump("for pages 2 and 3 to draw", 10_000) { pageDrew(pages[1]) && pageDrew(pages[2]) }
                    shot("double")
                    // Changing the page layout keeps the reader on the same page
                    preferences.pageLayout().set(0)
                    waitForIdle()
                    waitFor(hasText("3 / 4"), 10_000)
                    waitOrDump("for page 3 to draw", 10_000) { pageDrew(pages[2]) }
                    preferences.pageLayout().set(1)
                    waitForIdle()
                    waitFor(hasText("2-3 / 4"), 10_000)
                    waitOrDump("for two pages", 10_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().size >= 2 }
                    preferences.grayscale().set(true)
                    preferences.colorFilter().set(true)
                    preferences.colorFilterValue().set(0x40FF8000)
                    shot("filters")
                    root().performKeyInput { pressKey(Key.S) }
                    waitFor(hasText("Reading mode for this entry"))
                    shotWithPopups("settings")
                    // Android's scale types (imageScaleType), Smart fit included
                    onAllNodesWithText("Smart fit").onFirst().performScrollTo().performClick()
                    waitOrDump("for Smart fit", 5_000) { preferences.imageScaleType().get() == FitModes.SMART_FIT }
                    onAllNodesWithText("Filter").onFirst().performClick()
                    waitFor(hasText("Inverted colors"))
                    shotWithPopups("filter-tab")
                    // The Automatic reader theme
                    onAllNodesWithText("General").onFirst().performClick()
                    onAllNodesWithText("Auto").onFirst().performScrollTo().performClick()
                    waitOrDump("for the Auto theme", 5_000) { preferences.readerTheme().get() == 3 }
                    onAllNodesWithText("Close").onFirst().performClick()
                    preferences.readerTheme().set(1)
                    preferences.imageScaleType().set(FitModes.FIT_SCREEN)
                    preferences.grayscale().set(false)
                    preferences.colorFilter().set(false)
                    // The filters are off again: the pages are their own colours
                    waitOrDump("for the plain pages", 10_000) { pageDrew(pages[1]) && pageDrew(pages[2]) }
                }

                step("Reader page actions: save the combined page and set a page as the cover") {
                    val pictures = Files.createTempDirectory("zoukan-journey-pictures").toFile()
                    System.setProperty("zoukan.picturesDir", pictures.absolutePath)
                    // A right-click on the double page offers the first, the second and the combined page
                    root().performMouseInput { rightClick(center) }
                    waitFor(hasText("Save combined page"))
                    shotWithPopups("page-actions")
                    onAllNodesWithText("Save combined page").onFirst().performClick()
                    waitOrDump("for the saved page", 15_000) { pictures.listFiles().orEmpty().any { it.name.endsWith(" - 2-3.jpg") } }
                    val saved = ImageIO.read(pictures.listFiles()!!.first { it.name.endsWith(" - 2-3.jpg") })
                    saved.width shouldBe 1200
                    saved.height shouldBe 900
                    // Page 2 on the left (left to right), page 3 on the right
                    imageMatches(saved, pages[1]) shouldBe true
                    val before = runBlocking { Injekt.get<GetManga>().await(alpha.id)!!.coverLastModified }
                    root().performMouseInput { rightClick(center) }
                    waitFor(hasText("Set second page as cover"))
                    onAllNodesWithText("Set second page as cover").onFirst().performClick()
                    waitFor(hasText("Use this image as cover art?"))
                    shotWithPopups("set-cover")
                    onAllNodesWithText("OK").onFirst().performClick()
                    waitOrDump("for the new cover", 15_000) {
                        runBlocking { Injekt.get<GetManga>().await(alpha.id)!!.coverLastModified } != before
                    }
                    File(storage.local, "Alpha").listFiles()!!.any { it.name == "cover.png" } shouldBe true
                    System.clearProperty("zoukan.picturesDir")
                    // A long-press after the layout changed acts on the pages shown now, not the old spread
                    preferences.pageLayout().set(0)
                    waitForIdle()
                    waitFor(hasText("3 / 4"), 10_000)
                    root().performTouchInput { longClick(center) }
                    waitFor(hasText("Page 3"))
                    onAllNodesWithText("Pages 2 and 3").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Close").onFirst().performClick()
                    preferences.pageLayout().set(1)
                }
            }
        }
    }
}
