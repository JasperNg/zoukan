package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.e2e.LocalEntryFixture.fingerprint
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Deleting the downloads of a selection asks first, with the number of chapters, and keeps
 * every downloaded file as it was until the user confirms. Then the selection toolbar's other actions
 * (mark read and unread, bookmark and its removal, download) and a chapter's own right-click menu
 * (mark previous as read, read and unread, bookmark, download and delete its download), each
 * checked in the database and on disk.
 *
 *   ./gradlew :desktop:journeyTest --tests "*MangaDeleteSelectionJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MangaDeleteSelectionJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `chapter selection and row actions, and deleting downloads asks first`() {
        journey = Journey.start("delete-selection")
        val preferences = Injekt.get<DesktopPreferences>()
        val downloads = Injekt.get<DownloadManager>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        fun chapters(): List<Chapter> = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 15.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())

                (1..2).forEach { i ->
                    step("Download the next chapter: chapter $i") {
                        onAllNodes(hasContentDescription("Download")).onFirst().performClick()
                        waitFor(hasText("Next chapter"))
                        onAllNodesWithText("Next chapter").onFirst().performClick()
                        waitOrDump("for the download to finish", timeout = 120_000) {
                            downloads.queue.value.isEmpty() && !downloads.running.value &&
                                downloads.downloadedIds(manga, chapters()).size == i
                        }
                    }
                }

                val all = chapters()
                val files = all.take(2).map { downloads.findChapter(manga, it).shouldNotBeNull() }
                val before = files.associateWith { fingerprint(it) }
                fun untouched() = files.forEach {
                    it.exists() shouldBe true
                    fingerprint(it) shouldBe before.getValue(it)
                }
                val title = "Delete the downloads of ${all.size} chapters?"

                step("Select every chapter and click Delete downloads: a question with the count, files untouched") {
                    onNodeWithContentDescription("Select all").performClick()
                    waitFor(hasContentDescription("Delete downloads"))
                    onNodeWithContentDescription("Delete downloads").performClick()
                    waitFor(hasText(title))
                    awaitAppIdle()
                    untouched()
                }
                step("Cancel: files unchanged, selection still there") {
                    onNodeWithText("Cancel").performClick()
                    awaitAppIdle()
                    untouched()
                    onNodeWithContentDescription("Delete downloads").assertExists()
                }
                step("Ask again from the selection's right-click menu and confirm: the files go") {
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Delete downloads"))
                    onNodeWithText("Delete downloads").performClick()
                    waitFor(hasText(title))
                    untouched()
                    onNodeWithText("Delete downloads").performClick()
                    waitOrDump("for the downloads to go", timeout = 30_000) {
                        downloads.downloadedIds(manga, chapters()).isEmpty()
                    }
                }
                files.none { it.exists() } shouldBe true
                downloads.downloadedIds(manga, chapters()).shouldBeEmpty()

                // The selection toolbar's other actions and a chapter's own right-click menu, on
                // chapters 1 to 4 (oldest first), each checked in the database
                fun chapter(n: Int) = chapters()[n - 1]
                fun select(vararg numbers: Int) {
                    numbers.forEach { onNodeWithContentDescription(all[it - 1].name).performClick() }
                    waitFor(hasText("${numbers.size} selected"))
                }
                fun selectionCleared() = waitFor(hasContentDescription("Select all"))
                fun rowMenu(n: Int, item: String) {
                    onAllNodesWithText(all[n - 1].name).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText(item))
                    onAllNodesWithText(item).onFirst().performClick()
                }

                step("Toolbar: mark the selection read, then one of it unread, which starts it again") {
                    runBlocking { Injekt.get<ChapterRepository>().update(ChapterUpdate(id = all[1].id, lastPageRead = 2)) }
                    select(1, 2)
                    byDescription("Mark as read").performClick()
                    waitOrDump("for chapters 1 and 2 to be read", 10_000) { chapter(1).read && chapter(2).read }
                    selectionCleared()
                    select(2)
                    byDescription("Mark as unread").performClick()
                    waitOrDump("for chapter 2 to be unread from its first page", 10_000) { !chapter(2).read && chapter(2).lastPageRead == 0L }
                    chapter(1).read shouldBe true
                    selectionCleared()
                }
                step("Toolbar: bookmark the selection, then take one bookmark off") {
                    select(3, 4)
                    byDescription("Bookmark").performClick()
                    waitOrDump("for chapters 3 and 4 to be bookmarked", 10_000) { chapter(3).bookmark && chapter(4).bookmark }
                    waitFor(hasContentDescription("Bookmarked"))
                    select(4)
                    byDescription("Remove bookmark").performClick()
                    waitOrDump("for chapter 4's bookmark to go", 10_000) { !chapter(4).bookmark }
                    chapter(3).bookmark shouldBe true
                    selectionCleared()
                }
                step("Toolbar: download the selection") {
                    select(3, 4)
                    byDescription("Download").performClick()
                    waitOrDump("for chapters 3 and 4 to download", timeout = 120_000) {
                        downloads.queue.value.isEmpty() && !downloads.running.value &&
                            downloads.downloadedIds(manga, chapters()) == setOf(all[2].id, all[3].id)
                    }
                    downloads.findChapter(manga, chapter(3)).shouldNotBeNull()
                    downloads.findChapter(manga, chapter(4)).shouldNotBeNull()
                    selectionCleared()
                }
                step("Row menu: Mark previous as read marks the older chapters, not the chapter itself") {
                    chapters().map { it.read } shouldBe listOf(true, false, false, false)
                    rowMenu(3, "Mark previous as read")
                    waitOrDump("for chapters 1 and 2 to be read", 10_000) { chapter(1).read && chapter(2).read }
                    chapter(3).read shouldBe false
                    chapter(4).read shouldBe false
                }
                step("Row menu: read and unread, bookmark and its removal") {
                    rowMenu(4, "Mark as read")
                    waitOrDump("for chapter 4 to be read", 10_000) { chapter(4).read }
                    rowMenu(4, "Mark as unread")
                    waitOrDump("for chapter 4 to be unread", 10_000) { !chapter(4).read }
                    rowMenu(1, "Bookmark")
                    waitOrDump("for chapter 1 to be bookmarked", 10_000) { chapter(1).bookmark }
                    rowMenu(1, "Remove bookmark")
                    waitOrDump("for chapter 1's bookmark to go", 10_000) { !chapter(1).bookmark }
                }
                step("Row menu: download one chapter, then delete its download") {
                    rowMenu(1, "Download")
                    waitOrDump("for chapter 1 to download", timeout = 120_000) {
                        downloads.queue.value.isEmpty() && !downloads.running.value && all[0].id in downloads.downloadedIds(manga, chapters())
                    }
                    val file = downloads.findChapter(manga, chapter(1)).shouldNotBeNull()
                    rowMenu(1, "Delete download")
                    waitOrDump("for chapter 1's download to go", 30_000) { !file.exists() }
                    // The others stay
                    downloads.downloadedIds(manga, chapters()) shouldBe setOf(all[2].id, all[3].id)
                }
            }
        }
    }
}
