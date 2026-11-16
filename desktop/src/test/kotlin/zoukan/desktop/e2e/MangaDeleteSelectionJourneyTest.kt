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
 * every downloaded file as it was until the user confirms.
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
    fun `deleting the downloads of a selection asks first and keeps the files until confirmed`() {
        journey = Journey.start("delete-selection")
        val preferences = Injekt.get<DesktopPreferences>()
        val downloads = Injekt.get<DownloadManager>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        fun chapters(): List<Chapter> = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
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
                    Thread.sleep(1_000)
                    untouched()
                }
                step("Cancel: files unchanged, selection still there") {
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    Thread.sleep(1_000)
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
            }
        }
    }
}
