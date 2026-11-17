package zoukan.desktop.e2e

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * An entry's page through the real UI, offline: the tag menu (Search searches the library, Global
 * search asks the sources), the title menu's Library search, the chapter settings' Display tab,
 * Edit (Fill from tracker), the More menu, and Clear manga (it leaves Charlie with no chapters
 * in the database, and its files where they are). Then, on a fixture entry with twelve chapters:
 * every item of the Download menu (which chapters each queues), the scanlator filter (the chapters
 * it hides and the excluded_scanlators rows), Set as default (the default chapter settings, and the
 * library's entries only when asked), the notes of Edit, and the cover-based theme.
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
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 15.minutes) {
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

                // A fixture-source entry with twelve chapters: the four the source lists and eight more
                // in the database only, numbered 5 to 12 and read in that order. Chapter 1 is read and
                // 7 and 9 are bookmarked. Every download of it fails, so the queue keeps what was queued.
                val formats = addToLibrary(FixtureCatalog.FORMATS_TITLE)
                val chapterRepository = Injekt.get<ChapterRepository>()
                fun formatsChapters(): Map<Int, Chapter> =
                    runBlocking { chapterRepository.getChapterByMangaId(formats.id) }.associateBy { it.chapterNumber.toInt() }
                runBlocking {
                    chapterRepository.addAll(
                        (5..12).map { n ->
                            Chapter.create().copy(mangaId = formats.id, url = "/chapter/formats/extra-$n", name = "Chapter $n", chapterNumber = n.toDouble())
                        },
                    )
                    val all = formatsChapters()
                    all.keys shouldBe (1..12).toSet()
                    // Source order 0 is the newest, as a source lists them
                    chapterRepository.updateAll(
                        all.map { (n, chapter) ->
                            ChapterUpdate(id = chapter.id, sourceOrder = (12 - n).toLong(), read = n == 1, bookmark = n == 7 || n == 9)
                        },
                    )
                }
                val downloads = Injekt.get<DownloadManager>()
                fun queued() = downloads.queue.value.map { it.chapter.chapterNumber.toInt() }.sorted()

                step("Download menu: each item queues the chapters it names") {
                    server.fail("/chapter/formats/", FixtureFailure.Status(500))
                    runOnUiThread { navigator?.push(MangaScreen(formats.id)) }
                    waitFor(hasText("12 chapters"))
                    fun download(item: String, expected: List<Int>, fill: (() -> Unit)? = null) {
                        onAllNodes(hasContentDescription("Download")).onFirst().performClick()
                        waitFor(hasText(item))
                        onAllNodesWithText(item).onFirst().performClick()
                        fill?.invoke()
                        // A download queues its chapters at once, so the queue is either this or wrong
                        waitOrDump("for $item to queue $expected", 10_000) { queued() == expected }
                        downloads.clearQueue()
                        waitOrDump("for the queue to empty", 10_000) { downloads.queue.value.isEmpty() }
                    }
                    // The next unread chapters in reading order: chapter 1 is read
                    download("Next chapter", listOf(2))
                    download("Next 5 chapters", (2..6).toList())
                    download("Next 10 chapters", (2..11).toList())
                    download("Unread chapters", (2..12).toList())
                    download("Bookmarked chapters", listOf(7, 9))
                    download("Custom…", listOf(2, 3, 4)) {
                        waitFor(hasText("Download next chapters"))
                        onAllNodes(hasSetTextAction()).onLast().performTextInput("3")
                        shotWithPopups("download-custom")
                        onAllNodesWithText("Download").onLast().performClick()
                    }
                    server.clearFailures()
                }

                step("Scanlator filter: hiding a scanlator hides its chapters and is saved for the entry") {
                    val handler = Injekt.get<DatabaseHandler>()
                    fun excluded() = runBlocking { handler.awaitList { excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(formats.id) } }.toSet()
                    runBlocking {
                        chapterRepository.updateAll(
                            formatsChapters().map { (n, chapter) -> ChapterUpdate(id = chapter.id, scanlator = if (n <= 6) "Alpha Scans" else "Beta Scans") },
                        )
                    }
                    byDescription("Filter and sort").performClick()
                    waitFor(hasText("Scanlators"))
                    onAllNodesWithText("Scanlators").onFirst().performClick()
                    waitFor(hasText("Beta Scans"))
                    waitFor(hasText("Alpha Scans"))
                    // The chapter rows show their scanlator too: the dialog's row is the last
                    onAllNodesWithText("Beta Scans").onLast().performClick()
                    shotWithPopups("scanlator-filter")
                    onAllNodesWithText("Save").onFirst().performClick()
                    waitOrDump("for Beta Scans to be excluded", 10_000) { excluded() == setOf("Beta Scans") }
                    waitFor(hasText("Scanlators (some hidden)"))
                    onAllNodesWithText("Close").onFirst().performClick()
                    // Chapters 7 to 12 are Beta's: the newest shown is now chapter 6
                    waitFor(hasText("6 of 12 chapters"))
                    waitFor(hasText("Chapter 6"))
                    onAllNodesWithText("Chapter 12").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Chapter 7").fetchSemanticsNodes().size shouldBe 0

                    byDescription("Filter and sort (filters on)").performClick()
                    onAllNodesWithText("Scanlators (some hidden)").onFirst().performClick()
                    waitFor(hasText("Show all"))
                    onAllNodesWithText("Show all").onFirst().performClick()
                    onAllNodesWithText("Save").onFirst().performClick()
                    waitOrDump("for no scanlator to be excluded", 10_000) { excluded().isEmpty() }
                    onAllNodesWithText("Close").onFirst().performClick()
                    waitFor(hasText("12 chapters"))
                    // The list keeps its place, so the hidden chapters come back above chapter 6
                    waitFor(hasText("Chapter 7"))
                }

                step("Set as default: the default chapter settings, and the library's entries only when asked") {
                    val library = Injekt.get<LibraryPreferences>()
                    fun entry(id: Long) = runBlocking { Injekt.get<GetManga>().await(id)!! }
                    library.sortChapterBySourceOrNumber().get() shouldBe Manga.CHAPTER_SORTING_SOURCE
                    val secondFlags = entry(second.id).chapterFlags
                    entry(second.id).sorting shouldBe Manga.CHAPTER_SORTING_SOURCE
                    byDescription("Filter and sort").performClick()
                    onAllNodesWithText("Sort").onFirst().performClick()
                    waitFor(hasText("By chapter number"))
                    onAllNodesWithText("By chapter number").onFirst().performClick()
                    waitOrDump("for the entry to sort by chapter number", 10_000) { entry(formats.id).sorting == Manga.CHAPTER_SORTING_NUMBER }

                    fun setAsDefault(toLibrary: Boolean) {
                        val before = toasts.count { it == "Default chapter settings updated" }
                        onAllNodesWithText("Set as default").onFirst().performClick()
                        waitFor(hasText("Also apply them to every entry in the library"))
                        if (toLibrary) onAllNodesWithText("Also apply them to every entry in the library").onFirst().performClick()
                        shotWithPopups("set-as-default-$toLibrary")
                        onAllNodesWithText("OK").onLast().performClick()
                        // The toast comes after the preferences and, when asked, the library
                        waitOrDump("for the default chapter settings to be saved", 10_000) {
                            toasts.count { it == "Default chapter settings updated" } > before
                        }
                    }
                    setAsDefault(toLibrary = false)
                    library.sortChapterBySourceOrNumber().get() shouldBe Manga.CHAPTER_SORTING_NUMBER
                    // Another library entry keeps its own settings
                    entry(second.id).chapterFlags shouldBe secondFlags

                    setAsDefault(toLibrary = true)
                    val formatsFlags = entry(formats.id).chapterFlags
                    entry(second.id).chapterFlags shouldBe formatsFlags
                    runBlocking { Injekt.get<GetFavorites>().await() }.forEach {
                        it.sorting shouldBe Manga.CHAPTER_SORTING_NUMBER
                    }
                    onAllNodesWithText("Close").onFirst().performClick()
                }

                step("Edit: the notes are saved on the entry and shown on its page") {
                    val note = "Read the side stories after chapter 4"
                    byDescription("More").performClick()
                    onAllNodesWithText("Edit details, notes and cover").onFirst().performClick()
                    waitFor(hasText("Fill from tracker"))
                    onNode(hasSetTextAction() and hasText("Notes")).performTextInput(note)
                    shotWithPopups("edit-notes")
                    onAllNodesWithText("Save").onFirst().performClick()
                    waitOrDump("for the notes to be saved", 10_000) {
                        runBlocking { Injekt.get<GetManga>().await(formats.id)!!.notes } == note
                    }
                    waitFor(hasText(note))
                }

                step("Cover-based theme: the entry's page takes its colours from the cover only while the setting is on") {
                    val coverBased = Injekt.get<UiPreferences>().themeCoverBased()
                    coverBased.set(false)
                    waitForIdle()
                    val plain = commonestColour()
                    coverBased.set(true)
                    waitOrDump("for the page to take the cover's colours", 15_000) { commonestColour() != plain }
                    shot("cover-based-theme")
                    coverBased.set(false)
                    waitOrDump("for the plain colours to come back", 15_000) { commonestColour() == plain }
                    coverBased.set(true)
                    runOnUiThread { navigator?.popUntilRoot() }
                }
            }
        }
    }

    /** The colour most of the window is (the page's background), from every fourth pixel of a capture. */
    private fun ComposeUiTest.commonestColour(): Int {
        val image = with(journey) { root() }.captureToImage().toAwtImage()
        val counts = HashMap<Int, Int>()
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) counts.merge(image.getRGB(x, y), 1, Int::plus)
        }
        return counts.maxBy { it.value }.key
    }
}
