package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR
import java.util.Date
import kotlin.time.Duration.Companion.minutes

/**
 * More > Statistics: the counts on screen are the library, the read chapters and the read time
 * that were seeded, and reading one more chapter through the reader raises the read count by one
 * (seen through the library header's Statistics button). "Include all read entries" adds a read
 * entry that left the library, and switching back leaves it out again.
 *
 *   ./gradlew :desktop:journeyTest --tests "*StatsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class StatsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun statisticsMatchTheLibraryAndReadingData() {
        journey = Journey.start("stats")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val second = journey.addToLibrary("Fixture Second")
        val formatsChapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(formats.id) }.sortedBy { it.chapterNumber }
        val secondChapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(second.id) }
        val total = formatsChapters.size + secondChapters.size
        // A read entry that left the library: only "Include all read entries" counts it
        val third = journey.addToLibrary("Fixture Third")
        val thirdChapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(third.id) }
        runBlocking {
            Injekt.get<UpdateChapter>().awaitAll(thirdChapters.map { ChapterUpdate(id = it.id, read = true) })
            Injekt.get<MangaRepository>().update(MangaUpdate(id = third.id, favorite = false))
        }

        // Two chapters read: 1 h and 30 min of reading
        runBlocking {
            Injekt.get<UpdateChapter>().awaitAll(formatsChapters.take(2).map { ChapterUpdate(id = it.id, read = true, lastPageRead = 1) })
            val upsert = Injekt.get<UpsertHistory>()
            upsert.await(HistoryUpdate(formatsChapters[0].id, Date(), 60 * 60 * 1000L))
            upsert.await(HistoryUpdate(formatsChapters[1].id, Date(), 30 * 60 * 1000L))
            Injekt.get<GetTotalReadDuration>().await() shouldBe 90 * 60 * 1000L
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                /** The stat card with [value] above [label]. */
                fun stat(value: String, label: String): SemanticsMatcher = hasAnyChild(hasText(value)) and hasAnyChild(hasText(label))
                fun cards(value: String, label: String) = onAllNodes(stat(value, label), useUnmergedTree = true).fetchSemanticsNodes()
                fun home() = runOnUiThread { while (navigator?.canPop == true) navigator?.pop() }
                fun openStatistics() {
                    onAllNodesWithText(localized(MR.strings.label_more)).onFirst().performClick()
                    waitFor(hasText(localized(MR.strings.label_stats)))
                    onAllNodesWithText(localized(MR.strings.label_stats)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.read_time)))
                }

                step("Statistics shows what was seeded") {
                    openStatistics()
                    waitOrDump("for the library count") { cards("2", localized(MR.strings.in_library)).size == 1 }
                    waitOrDump("for the total chapters") { cards("$total", localized(MR.strings.label_total_chapters)).size == 1 }
                    cards("2", localized(MR.strings.label_read_chapters)) shouldHaveSize 1
                    cards("1h 30m", localized(DMR.strings.read_time)) shouldHaveSize 1
                    // Nothing was downloaded
                    cards("0", localized(MR.strings.label_downloaded)) shouldHaveSize 1
                    shot("1-stats")
                }

                step("Reading one more chapter through the reader raises the read count") {
                    home()
                    waitOrDump("for Statistics to close") { onAllNodesWithText(localized(MR.strings.label_total_chapters)).fetchSemanticsNodes().isEmpty() }
                    runOnUiThread { navigator?.push(MangaScreen(formats.id)) }
                    waitFor(hasText("Chapter 3 (WEBP)"))
                    onAllNodesWithText("Chapter 3 (WEBP)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("webp"), "2-read")
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the chapter to be read") {
                        runBlocking { Injekt.get<GetChaptersByMangaId>().await(formats.id) }.count { it.read } == 3
                    }
                    home()
                    // The library header's Statistics button opens the same screen
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                    waitFor(hasContentDescription(localized(MR.strings.label_stats)))
                    byDescription(localized(MR.strings.label_stats)).performClick()
                    waitFor(hasText(localized(DMR.strings.read_time)))
                    waitOrDump("for the read count to go up") { cards("3", localized(MR.strings.label_read_chapters)).size == 1 }
                    cards("2", localized(MR.strings.in_library)) shouldHaveSize 1
                    shot("3-stats-after")
                }

                step("Include all read entries counts the read entry outside the library, and the switch turns it off again") {
                    onAllNodesWithContentDescription(localized(MR.strings.label_more)).onLast().performClick()
                    waitFor(hasText(localized(SYMR.strings.include_all_read_entries)))
                    onAllNodesWithText(localized(SYMR.strings.include_all_read_entries)).onFirst().performClick()
                    waitOrDump("for the read entry to count") { cards("3", localized(MR.strings.in_library)).size == 1 }
                    waitOrDump("for its chapters to count") {
                        cards("${3 + thirdChapters.size}", localized(MR.strings.label_read_chapters)).size == 1 &&
                            cards("${total + thirdChapters.size}", localized(MR.strings.label_total_chapters)).size == 1
                    }
                    shot("4-all-read")
                    onAllNodesWithContentDescription(localized(MR.strings.label_more)).onLast().performClick()
                    waitFor(hasText(localized(SYMR.strings.ignore_non_library_entries)))
                    onAllNodesWithText(localized(SYMR.strings.ignore_non_library_entries)).onFirst().performClick()
                    waitOrDump("for the library alone") { cards("2", localized(MR.strings.in_library)).size == 1 }
                    cards("3", localized(MR.strings.label_read_chapters)) shouldHaveSize 1
                }
            }
        }
    }
}
