package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * An entry's page through the real UI, offline: the tag menu (Search searches the library, Global
 * search asks the sources), the title menu's Library search, the chapter settings' Display tab,
 * Edit (Fill from tracker), the More menu, and Clear manga last (it leaves Charlie with no chapters
 * in the database, and its files where they are).
 *
 *   ./gradlew :desktop:journeyTest --tests "*EntryPageJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class EntryPageJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun entryMenusDisplayEditAndClear() {
        journey = Journey.start("entry-page")
        val preferences = Injekt.get<DesktopPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val favorites = LocalFixtures.seedParityLibrary(storage)
        // A title from the fixture source, whose "drama" genre a tag's Global search asks the source for
        val second = journey.addToLibrary("Fixture Second")
        val charlie = favorites.first { it.title == "Charlie" }
        runBlocking {
            Injekt.get<MangaRepository>().update(MangaUpdate(id = charlie.id, genre = listOf("female: sole female")))
        }
        runBlocking {
            Injekt.get<MangaRepository>().update(MangaUpdate(id = second.id, genre = listOf(FixtureCatalog.GENRE_DRAMA)))
        }
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 4 }

                step("Tag menu: Search searches the library") {
                    runOnUiThread { navigator?.push(MangaScreen(charlie.id)) }
                    // A tag's click opens Search, Global search and Copy; Search from the library
                    // searches the library, as Android's performGenreSearch
                    waitFor(hasText("female: sole female"))
                    onAllNodesWithText("female: sole female").onFirst().performClick()
                    waitFor(hasText("Copy to clipboard"))
                    waitFor(hasText("Global search"))
                    shotWithPopups("tag-menu")
                    onAllNodesWithText("Search").onFirst().performClick()
                    waitFor(hasSetTextAction() and hasText("female: sole female"))
                    navigator!!.size shouldBe 1
                    onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                }

                step("Tag menu: Global search asks the fixture source") {
                    runOnUiThread { navigator?.push(MangaScreen(second.id)) }
                    waitFor(hasText(FixtureCatalog.GENRE_DRAMA))
                    val before = server.requests.count { it == "/catalog" }
                    onAllNodesWithText(FixtureCatalog.GENRE_DRAMA).onFirst().performClick()
                    waitFor(hasText("Global search"))
                    onAllNodesWithText("Global search").onFirst().performClick()
                    // The fixture source answers the search over HTTP
                    waitOrDump("for the fixture source to be searched", 30_000) {
                        server.requests.count { it == "/catalog" } > before
                    }
                    server.requests.count { it == "/catalog" } shouldBeGreaterThan before
                    shot("global-search")
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitOrDump("for the library", 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 4 }
                }

                step("Title menu: Library search searches the library for the title") {
                    runOnUiThread { navigator?.push(MangaScreen(charlie.id)) }
                    waitFor(hasTestTag("entry-title"))
                    onNodeWithTag("entry-title").performMouseInput { rightClick() }
                    waitFor(hasText("Library search"))
                    shotWithPopups("title-menu")
                    onAllNodesWithText("Library search").onFirst().performClick()
                    waitFor(hasSetTextAction() and hasText("Charlie"))
                    waitOrDump("for the search to leave Charlie", 10_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
                }

                step("Chapter settings: the Display tab saves the display mode on the entry") {
                    runOnUiThread { navigator?.push(MangaScreen(charlie.id)) }
                    waitFor(hasTestTag("entry-title"))
                    byDescription("Filter and sort").performClick()
                    onAllNodesWithText("Display").onFirst().performClick()
                    waitFor(hasText("Source title"))
                    onAllNodesWithText("Chapter number").onFirst().performClick()
                    waitOrDump("for the display mode", 10_000) {
                        runBlocking { Injekt.get<GetManga>().await(charlie.id)!!.displayMode } == Manga.CHAPTER_DISPLAY_NUMBER
                    }
                    shotWithPopups("chapter-display-tab")
                    onAllNodesWithText("Close").onFirst().performClick()
                }

                step("Edit has the thumbnail URL and Fill from tracker") {
                    byDescription("More").performClick()
                    waitFor(hasText("Open folder"))
                    waitFor(hasText("Clear manga"))
                    onAllNodesWithText("Edit details, notes and cover").onFirst().performClick()
                    waitFor(hasText("Fill from tracker"))
                    waitFor(hasText("Thumbnail url"))
                    shotWithPopups("edit-fill-from-tracker")
                    onAllNodesWithText("Cancel").onFirst().performClick()
                }

                step("Clear manga removes the chapters from the database, not the local files") {
                    // Every chapter file with its bytes, to prove none is deleted or rewritten
                    fun charlieFiles() = File(storage.local, "Charlie").listFiles().orEmpty()
                        .filter { it.name.endsWith(".cbz") }
                        .associate { it.name to it.readBytes().contentHashCode() }
                    val filesBefore = charlieFiles()
                    filesBefore.size shouldBe 4
                    byDescription("More").performClick()
                    onAllNodesWithText("Clear manga").onFirst().performClick()
                    waitFor(hasText("Chapters & History from Database"))
                    onAllNodesWithText("Chapters & History from Database").onFirst().performClick()
                    shotWithPopups("clear-manga")
                    onAllNodesWithText("OK").onFirst().performClick()
                    waitOrDump("for the chapters to go", 10_000) {
                        runBlocking { Injekt.get<GetChaptersByMangaId>().await(charlie.id).isEmpty() }
                    }
                    charlieFiles() shouldBe filesBefore
                    runOnUiThread { navigator?.popUntilRoot() }
                }
            }
        }
    }
}
