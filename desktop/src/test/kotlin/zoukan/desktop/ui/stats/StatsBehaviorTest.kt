package zoukan.desktop.ui.stats

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.in_library
import tachiyomi.i18n.label_downloaded
import tachiyomi.i18n.label_more
import tachiyomi.i18n.label_read_chapters
import tachiyomi.i18n.label_total_chapters
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.ignore_non_library_entries
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.read_time
import java.util.concurrent.Executors

/**
 * Isolated Compose UI test for [StatsScreen] and [StatsScreenModel]:
 * displays library count, total/read chapters, read duration, and toggles "Include all read entries"
 * to count read non-library entries and back off again.
 */
@OptIn(ExperimentalTestApi::class)
class StatsBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun entry(id: Long, favorite: Boolean, total: Long, read: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, ogTitle = "Manga $id", favorite = favorite, source = 1L),
        categories = emptyList(),
        totalChapters = total,
        readCount = read,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `stats screen displays seeded counts and toggles non-library read entries`() {
        val library = listOf(
            entry(1L, favorite = true, total = 4, read = 2),
            entry(2L, favorite = true, total = 2, read = 0),
        )
        val nonLibrary = listOf(entry(3L, favorite = false, total = 3, read = 3))

        Injekt.addSingleton(mockk<GetLibraryManga> { coEvery { await() } returns library })
        Injekt.addSingleton(mockk<GetReadMangaNotInLibraryView> { coEvery { await() } returns nonLibrary })
        Injekt.addSingleton(mockk<GetTotalReadDuration> { coEvery { await() } returns 90 * 60 * 1000L })
        Injekt.addSingleton(mockk<GetTracks> { coEvery { await(any<List<Long>>()) } returns emptyMap() })
        Injekt.addSingleton(mockk<TrackerManager> { every { loggedInTrackers() } returns emptyList() })
        Injekt.addSingleton(LibraryPreferences(TestPreferenceStore()))
        Injekt.addSingleton(mockk<DownloadManager> { every { downloadCounts(any()) } returns emptyMap() })

        fun stat(value: String, label: String): SemanticsMatcher = hasAnyChild(hasText(value)) and hasAnyChild(hasText(label))

        runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
            setContent { Navigator(StatsScreen) }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(stat("2", localized(MR.strings.in_library)), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onAllNodes(stat("6", localized(MR.strings.label_total_chapters)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1
            onAllNodes(stat("2", localized(MR.strings.label_read_chapters)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1
            onAllNodes(stat("1h 30m", localized(DMR.strings.read_time)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1
            onAllNodes(stat("0", localized(MR.strings.label_downloaded)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1

            // Include all read entries
            onNodeWithContentDescription(localized(MR.strings.label_more)).performClick()
            onNodeWithTag("stats-all-read").performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(stat("3", localized(MR.strings.in_library)), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onAllNodes(stat("5", localized(MR.strings.label_read_chapters)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1
            onAllNodes(stat("9", localized(MR.strings.label_total_chapters)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1

            // Ignore non-library entries again
            onNodeWithContentDescription(localized(MR.strings.label_more)).performClick()
            onAllNodesWithText(localized(SYMR.strings.ignore_non_library_entries)).fetchSemanticsNodes() shouldHaveSize 1
            onNodeWithTag("stats-all-read").performClick()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(stat("2", localized(MR.strings.in_library)), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            onAllNodes(stat("2", localized(MR.strings.label_read_chapters)), useUnmergedTree = true).fetchSemanticsNodes() shouldHaveSize 1
        }
    }
}
