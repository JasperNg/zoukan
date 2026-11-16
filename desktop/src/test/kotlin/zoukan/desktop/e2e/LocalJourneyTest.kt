package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.ComicInfoXml
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The local source, through the real UI and with no network: browse it, add an entry (with its
 * ComicInfo details) to the library, pick up a chapter added on disk by updating the library from
 * the Library tab, and read that chapter from the Updates tab. The pages are solid colours, so
 * the journey checks the reader drew them.
 *
 *   ./gradlew :desktop:journeyTest --tests "*LocalJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class LocalJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun localSourceAddUpdateAndRead() {
        journey = Journey.start("local")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val updater = Injekt.get<LibraryUpdater>()
        var navigator: Navigator? = null

        val title = "Local Test Manga"
        val summary = "Made by the journey test"
        val mangaDir = LocalFixtures.localEntry(
            storage,
            title,
            chapters = 1,
            comicInfo = ComicInfoXml(mapOf("Series" to title, "Writer" to "Zoukan", "Summary" to summary)).write(),
        )
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(emptySet())
        var chapter2 = emptyList<FixturePage>()

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Open the local entry and add it to the library") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText("Local source"))
                    onAllNodesWithText("Local source").onFirst().performScrollTo().performClick()
                    waitOrDump("for the local entry") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().isNotEmpty() }
                    shot("1-local-source")
                    onAllNodesWithTag("manga-item").onFirst().performClick()
                    waitFor(hasText("Start") and isEnabled())
                    // The details come from ComicInfo.xml
                    waitFor(hasText(summary))
                    onAllNodesWithText("Add to library").onFirst().performClick()
                    waitFor(hasText("In library"))
                    shot("2-manga")
                    runOnUiThread { navigator?.popUntilRoot() }
                    runBlocking { Injekt.get<GetFavorites>().await().map { it.title } } shouldBe listOf(title)
                }

                step("Add a chapter on disk and update the library") {
                    chapter2 = LocalFixtures.addChapter(mangaDir, title, 2, pagesPerChapter = 5)
                    onAllNodesWithText("Library").onFirst().performClick()
                    waitOrDump("for the library entry") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    byDescription("Update library").performClick()
                    waitOrDump("for the library update", 60_000) { updater.lastResult.value != null && updater.progress.value == null }
                    val result = updater.lastResult.value!!
                    result.failures.shouldBeEmpty()
                    result.updated shouldBe 1
                    result.newChapters shouldBe 1
                    val manga = runBlocking { Injekt.get<GetFavorites>().await().single() }
                    runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.map { it.name }.toSet() shouldBe
                        setOf("Chapter 1", "Chapter 2")
                    shot("3-library")
                }

                step("Read the new chapter from the Updates tab") {
                    onAllNodesWithText("Updates").onFirst().performClick()
                    waitFor(hasText("Chapter 2"))
                    shot("4-updates")
                    onAllNodesWithText("Chapter 2").onFirst().performClick()
                    // The pages are the CBZ's colours: the first one drew
                    readThrough(chapter2.take(1), "5-reader")
                    root().performKeyInput { pressKey(Key.M) }
                    waitFor(hasText("Local file", substring = true))
                    shot("6-reader-menu")
                    runOnUiThread { navigator?.popUntilRoot() }
                }

                step("Downloads screen and settings") {
                    onAllNodesWithText("Library").onFirst().performClick()
                    byDescription("Downloads").performClick()
                    waitFor(hasText("No downloads queued", substring = true))
                    shot("7-downloads")
                    runOnUiThread { navigator?.popUntilRoot() }
                    // Settings is in the More tab, as on Android
                    onAllNodesWithText("More").onFirst().performClick()
                    waitFor(hasText("Downloaded only"))
                    onAllNodesWithText("Settings").onFirst().performClick()
                    waitFor(hasText("App theme"))
                    shot("8-settings")
                }
            }
        }
    }
}
