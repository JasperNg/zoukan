package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * Selecting library entries through the real UI, offline. The local entries Alpha and Charlie:
 * the More actions of a two-entry selection (Merge, Find recommendations and its dialog) and no
 * Download for local entries. Fixture Second and Fixture Third (their chapters bookmarked before
 * the window opens): Download > Bookmarked downloads them; Remove from library keeps Second's
 * download, and with "Also delete downloaded chapters" deletes Third's. Update on Alpha. Merge of
 * Fixture Formats with Markup Description of a second source: the merged entry holds both, and
 * the "remove the original entries?" prompt takes them out of the library.
 *
 *   ./gradlew :desktop:journeyTest --tests "*LibrarySelectionJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class LibrarySelectionJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun selectionMoreDownloadRemoveMergeAndUpdate() {
        journey = Journey.start("library-selection")
        val preferences = Injekt.get<DesktopPreferences>()
        val updater = Injekt.get<LibraryUpdater>()
        val downloads = Injekt.get<DownloadManager>()
        val mangas = Injekt.get<MangaRepository>()
        var navigator: Navigator? = null
        LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        val mirror = journey.addSource()
        val second = journey.addToLibrary("Fixture Second")
        val third = journey.addToLibrary("Fixture Third")
        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val markup = journey.addToLibrary(FixtureCatalog.HTML_TITLE, from = mirror)
        fun chapters(manga: Manga): List<Chapter> = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }
        fun favorite(manga: Manga): Boolean = runBlocking { mangas.getMangaById(manga.id).favorite }
        // Download > Bookmarked needs bookmarks; this journey makes its own
        runBlocking {
            val bookmarked = chapters(second) + chapters(third)
            bookmarked.size shouldBe 2
            Injekt.get<ChapterRepository>().updateAll(bookmarked.map { ChapterUpdate(id = it.id, bookmark = true) })
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 12.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                fun items() = onAllNodesWithTag("manga-item").fetchSemanticsNodes().size
                waitOrDump("for the library", 30_000) { items() == 7 }
                fun selectionEnded() = onAllNodes(textMatches(Regex(".* selected"))).fetchSemanticsNodes().isEmpty()

                /** Starts a selection with [first] (a right click, then Select) and adds [others] with a click. */
                fun select(first: String, vararg others: String) {
                    onAllNodesWithText(first).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Select"))
                    onAllNodesWithText("Select").onFirst().performClick()
                    waitFor(hasText("1 selected"))
                    others.forEachIndexed { i, title ->
                        onAllNodesWithText(title).onFirst().performClick()
                        waitFor(hasText("${i + 2} selected"))
                    }
                }

                step("Local selection: the More actions, and no Download") {
                    select("Alpha", "Charlie")
                    byDescription("More").performClick()
                    // Merge always; Find recommendations with two or more; the source-specific ones only when they apply
                    waitFor(hasText("Merge"))
                    waitFor(hasText("Find recommendations"))
                    onAllNodesWithText("Clean titles").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Reset Info").fetchSemanticsNodes().size shouldBe 0
                    shotWithPopups("more")
                    // The recommendation search dialog: its services and the library filter; Cancel keeps the selection
                    onAllNodesWithText("Find recommendations").onFirst().performClick()
                    waitFor(hasText("Recommendation services to search"))
                    waitFor(hasText("Hide results already in your library"))
                    shotWithPopups("recommendation-search")
                    onAllNodesWithText("Cancel").onLast().performClick()
                    waitOrDump("for the dialog to close", 10_000) { onAllNodesWithText("Recommendation services to search").fetchSemanticsNodes().isEmpty() }
                    waitFor(hasText("2 selected"))
                    // As Android's LibraryTab: local chapters are files already, so the bar has no Download
                    onAllNodesWithContentDescription("Download").fetchSemanticsNodes().size shouldBe 0
                    byDescription("Clear selection").performClick()
                    waitOrDump("for the selection to end", 10_000) { selectionEnded() }
                }

                step("Download > Bookmarked downloads the bookmarked chapters of the selection") {
                    select("Fixture Second", "Fixture Third")
                    byDescription("Download").performClick()
                    onAllNodesWithText("Bookmarked").onLast().performClick()
                    waitOrDump("for the selection to end", 10_000) { selectionEnded() }
                    waitOrDump("for both downloads", 120_000) {
                        downloads.queue.value.isEmpty() && !downloads.running.value &&
                            downloads.downloadedIds(second, chapters(second)).size == 1 &&
                            downloads.downloadedIds(third, chapters(third)).size == 1
                    }
                }

                step("Update on one entry") {
                    select("Alpha")
                    // The seed's own update left a result; this one must replace it
                    val seedResult = updater.lastResult.value
                    byDescription("Update").performClick()
                    waitOrDump("for the selection to end", 10_000) { selectionEnded() }
                    waitOrDump("for the update to end", 60_000) { updater.lastResult.value !== seedResult && updater.progress.value == null }
                    updater.lastResult.value!!.failures.shouldBeEmpty()
                }

                val secondFile = downloads.findChapter(second, chapters(second).single()).shouldNotBeNull()
                val thirdFile = downloads.findChapter(third, chapters(third).single()).shouldNotBeNull()

                step("Remove from library keeps the downloads unless asked") {
                    select("Fixture Second")
                    byDescription("Remove from library").performClick()
                    waitFor(hasText("Remove from library?"))
                    waitFor(hasText("Also delete downloaded chapters"))
                    shotWithPopups("remove")
                    onAllNodesWithText("Remove").onLast().performClick()
                    waitOrDump("for Fixture Second to leave the library", 10_000) { !favorite(second) }
                    waitOrDump("for the library to list 6", 10_000) { items() == 6 }
                    secondFile.exists() shouldBe true
                    downloads.downloadedIds(second, chapters(second)).size shouldBe 1
                }

                step("Remove from library with Also delete downloaded chapters deletes them") {
                    select("Fixture Third")
                    byDescription("Remove from library").performClick()
                    waitFor(hasText("Also delete downloaded chapters"))
                    onAllNodesWithText("Also delete downloaded chapters").onFirst().performClick()
                    onAllNodesWithText("Remove").onLast().performClick()
                    waitOrDump("for Fixture Third to leave the library", 10_000) { !favorite(third) }
                    waitOrDump("for the download to go", 10_000) { !thirdFile.exists() }
                    downloads.downloadedIds(third, chapters(third)).shouldBeEmpty()
                    // The other entry's download is not touched
                    secondFile.exists() shouldBe true
                    waitOrDump("for the library to list 5", 10_000) { items() == 5 }
                }

                step("More > Merge on two entries, then remove the originals") {
                    select(FixtureCatalog.FORMATS_TITLE, FixtureCatalog.HTML_TITLE)
                    byDescription("More").performClick()
                    waitFor(hasText("Merge"))
                    onAllNodesWithText("Merge").onLast().performClick()
                    waitFor(hasText("Remove original entries from library?"))
                    shotWithPopups("remove-merged")
                    val merged = runBlocking { mangas.getFavorites() }.single { it.source == MERGED_SOURCE_ID }
                    val references = runBlocking { Injekt.get<GetMergedReferencesById>().await(merged.id) }
                    references.map { it.mangaId } shouldContainAll listOf(formats.id, markup.id)
                    // Both are still in the library until the prompt is answered
                    favorite(formats) shouldBe true
                    favorite(markup) shouldBe true
                    onAllNodesWithText("Remove").onLast().performClick()
                    waitOrDump("for the originals to leave the library", 10_000) { !favorite(formats) && !favorite(markup) }
                    favorite(merged) shouldBe true
                    // The prompt opens the merged entry, with the chapters of both
                    waitFor(hasText("Chapter 2 (PNG)"))
                    runOnUiThread { while (navigator?.canPop == true) navigator?.pop() }
                    waitOrDump("for the library to list 4", 10_000) { items() == 4 }
                }
            }
        }
    }
}
