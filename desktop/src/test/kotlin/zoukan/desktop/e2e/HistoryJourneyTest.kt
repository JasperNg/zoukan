package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.i18n.desktop.DMR
import java.util.Date
import kotlin.time.Duration.Companion.minutes

/**
 * The History tab: read a chapter, find it in History, search, resume (opens the next chapter),
 * remove one chapter, remove the whole title, filter, select and clear.
 *
 *   ./gradlew :desktop:journeyTest --tests "*HistoryJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class HistoryJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    private fun rows(): List<HistoryWithRelations> =
        runBlocking { Injekt.get<GetHistory>().subscribe("", null, null, null).first() }

    private fun chapters(mangaId: Long): List<Chapter> =
        runBlocking { Injekt.get<GetChaptersByMangaId>().await(mangaId) }.sortedBy { it.chapterNumber }

    private fun seed(chapter: Chapter, agoMs: Long = 0) = runBlocking {
        Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(System.currentTimeMillis() - agoMs), 60_000))
    }

    @Test
    fun readFindResumeRemoveFilterAndClear() {
        journey = Journey.start("history")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val second = journey.addToLibrary(SECOND_TITLE)
        val formatsChapters = chapters(formats.id)
        val secondChapter = chapters(second.id).single()
        // A title read an hour ago, so it sits below the one read in this journey
        seed(secondChapter, agoMs = 60 * 60 * 1000L)

        val historyTab = localized(MR.strings.history)
        val removeFromHistory = localized(DMR.strings.remove_from_history)
        val remove = localized(MR.strings.action_remove)
        val filter = localized(MR.strings.action_filter)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                fun rowCount() = onAllNodesWithTag("history-item").fetchSemanticsNodes().size
                fun openHistory() {
                    runOnUiThread { while (navigator?.canPop == true) navigator?.pop() }
                    onAllNodesWithText(historyTab).onFirst().performClick()
                }

                step("Read chapter 1 of ${FixtureCatalog.FORMATS_TITLE} through the UI") {
                    waitForIdle()
                    runOnUiThread { navigator?.push(MangaScreen(formats.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodesWithText("Start").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("jpeg"), "1-read")
                    runOnUiThread { navigator?.pop() }
                    // The reader writes the history as it closes
                    waitOrDump("for the history of chapter 1") { rows().any { it.mangaId == formats.id && it.chapterId == formatsChapters[0].id } }
                }

                step("History lists one row per title") {
                    openHistory()
                    waitOrDump("for two history rows") { rowCount() == 2 }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    waitFor(hasText(SECOND_TITLE))
                    shot("2-history")
                }

                step("Search narrows the list; a search with no match shows the empty message") {
                    val search = hasSetTextAction()
                    onAllNodes(search).onFirst().performTextReplacement("Second")
                    waitOrDump("for the search to narrow the list") { rowCount() == 1 }
                    onAllNodesWithText(SECOND_TITLE).fetchSemanticsNodes() shouldHaveSize 1
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).fetchSemanticsNodes() shouldHaveSize 0
                    onAllNodes(search).onFirst().performTextReplacement("zzz-nothing")
                    waitFor(hasText(localized(DMR.strings.nothing_matches, "zzz-nothing")))
                    rowCount() shouldBe 0
                    shot("3-no-match")
                    onAllNodes(search).onFirst().performTextReplacement("")
                    waitOrDump("for both rows back") { rowCount() == 2 }
                }

                step("Resume opens the next chapter") {
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    // Chapter 1 is finished, so chapter 2 (PNG) opens, not chapter 1 again
                    waitOrDump("for chapter 2's first page", timeout = 60_000) { pageDrew(FixtureCatalog.pages("png").first()) }
                    shot("4-resumed")
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the history of chapter 2") { rows().any { it.mangaId == formats.id && it.chapterId == formatsChapters[1].id } }
                    openHistory()
                    waitFor(hasText("Chapter 2 (PNG)", substring = true))
                }

                step("Remove this chapter only: the title stays with its earlier chapter") {
                    onAllNodes(hasContentDescription(removeFromHistory)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.this_removes_the_read_date_of)))
                    shot("5-remove-dialog")
                    onAllNodesWithText(remove).onFirst().performClick()
                    waitOrDump("for the chapter to leave the history") {
                        rows().firstOrNull { it.mangaId == formats.id }?.chapterId == formatsChapters[0].id
                    }
                    waitFor(hasText("Chapter 1 (JPEG)", substring = true))
                    rowCount() shouldBe 2
                }

                step("Remove the whole title") {
                    onAllNodes(hasContentDescription(removeFromHistory)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.remove_every_chapter_of_this_entry)))
                    onAllNodesWithText(localized(DMR.strings.remove_every_chapter_of_this_entry)).onFirst().performClick()
                    onAllNodesWithText(remove).onFirst().performClick()
                    waitOrDump("for the title to leave the history") { rows().none { it.mangaId == formats.id } }
                    waitOrDump("for one row") { rowCount() == 1 }
                    rows().map { it.mangaId } shouldBe listOf(second.id)
                    shot("6-title-removed")
                }

                step("Searching for the removed title shows the empty result") {
                    onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Formats")
                    waitFor(hasText(localized(DMR.strings.nothing_matches, "Formats")))
                    rowCount() shouldBe 0
                    onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("")
                    waitOrDump("for the other title back") { rowCount() == 1 }
                }

                step("The filter dialog narrows the list") {
                    onAllNodes(hasContentDescription(filter)).onFirst().performClick()
                    val notInLibrary = localized(DMR.strings.not_in_library)
                    waitFor(hasText(notInLibrary))
                    // Both titles are in the library: "not in library" leaves nothing
                    onAllNodesWithText(notInLibrary).onFirst().performClick()
                    onAllNodesWithText(localized(MR.strings.action_close)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.no_history_matches_the_filters)))
                    rowCount() shouldBe 0
                    shot("7-filtered")
                    // The next click inverts it: "in library" shows the title again
                    onAllNodes(hasContentDescription(localized(DMR.strings.filter_filtered))).onFirst().performClick()
                    onAllNodesWithText(notInLibrary).onFirst().performClick()
                    waitFor(hasText(localized(MR.strings.in_library)))
                    onAllNodesWithText(localized(MR.strings.action_close)).onFirst().performClick()
                    waitOrDump("for the title with the filter inverted") { rowCount() == 1 }
                    // And off again
                    onAllNodes(hasContentDescription(localized(DMR.strings.filter_filtered))).onFirst().performClick()
                    onAllNodesWithText(localized(MR.strings.in_library)).onFirst().performClick()
                    waitFor(hasText(notInLibrary))
                    onAllNodesWithText(localized(MR.strings.action_close)).onFirst().performClick()
                    waitOrDump("for the filter to be off") {
                        onAllNodes(hasContentDescription(filter)).fetchSemanticsNodes().isNotEmpty()
                    }
                }

                step("Select all and remove") {
                    seed(formatsChapters[0])
                    waitOrDump("for two rows") { rowCount() == 2 }
                    onAllNodes(hasContentDescription(localized(DMR.strings.select))).onFirst().performClick()
                    waitFor(hasTestTag("history-selection"))
                    onAllNodes(hasContentDescription(localized(MR.strings.action_select_all))).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.selected, 2)))
                    shot("8-selected")
                    onAllNodes(hasContentDescription(removeFromHistory) and isEnabled()).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.remove_from_history_2, 2)))
                    onAllNodesWithText(remove).onFirst().performClick()
                    waitOrDump("for the selection bar to go") { onAllNodesWithTag("history-selection").fetchSemanticsNodes().isEmpty() }
                    waitFor(hasText(localized(DMR.strings.nothing_read_yet)))
                    rows() shouldHaveSize 0
                }

                step("Clear all history") {
                    seed(secondChapter)
                    waitOrDump("for the row") { rowCount() == 1 }
                    onAllNodes(hasContentDescription(localized(MR.strings.pref_clear_history))).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.this_removes_all_reading_history_read)))
                    onAllNodesWithText(localized(DMR.strings.clear)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.nothing_read_yet)))
                    rows() shouldHaveSize 0
                    shot("9-cleared")
                }
            }
        }
    }

    private companion object {
        const val SECOND_TITLE = "Fixture Second"
    }
}
