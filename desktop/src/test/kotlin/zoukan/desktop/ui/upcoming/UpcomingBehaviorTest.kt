package zoukan.desktop.ui.upcoming

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.domain.upcoming.interactor.GetUpcomingManga
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.visibleCalendarDay
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Isolated Compose UI test for [UpcomingScreen]: displays upcoming ongoing entries, switches months,
 * clicks a day with events, and toggles Smart update ("To be updated") to show all due entries.
 */
@OptIn(ExperimentalTestApi::class)
class UpcomingBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Images.init(File(temp, "covers"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun `upcoming shows ongoing entries, switches month, and toggles smart update list`() {
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        fun entry(id: Long, title: String, days: Long, status: Int) = Manga.create().copy(
            id = id,
            ogTitle = title,
            favorite = true,
            ogStatus = status.toLong(),
            nextUpdate = today.plusDays(days).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        val alpha = entry(1L, "Alpha", 1L, SManga.ONGOING)
        val bravo = entry(2L, "Bravo", 3L, SManga.ONGOING)
        val charlie = entry(3L, "Charlie", 3L, SManga.PUBLISHING_FINISHED)
        val delta = entry(4L, "Delta", 2L, SManga.COMPLETED)

        val libraryPrefs = LibraryPreferences(TestPreferenceStore())
        libraryPrefs.autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD))
        val getUpcoming = mockk<GetUpcomingManga> {
            coEvery { subscribe() } returns flowOf(listOf(alpha, bravo, charlie))
            coEvery { updatingMangas() } returns listOf(alpha, delta, bravo, charlie)
        }
        Injekt.addSingleton(libraryPrefs)
        Injekt.addSingleton(getUpcoming)

        runDesktopComposeUiTest(effectContext = e2eEffectContext()) {
            setContent { Navigator(UpcomingScreen) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size == 3 }
            onNodeWithText("Delta").assertDoesNotExist()

            // Click a visible calendar day, then step forward and back a month
            val day = visibleCalendarDay(today, listOf(1L, 3L))
            onAllNodesWithTag("upcoming-day-${day.dayOfMonth}").onFirst().performClick()
            onNodeWithContentDescription("Next Month").performClick()
            val nextMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.now().plusMonths(1))
            onNodeWithText(nextMonth).assertExists()
            onNodeWithContentDescription("Previous Month").performClick()
            val currentMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.now())
            onNodeWithText(currentMonth).assertExists()

            // Toggle Smart update ("To be updated"): shows all 4 including completed Delta
            onNodeWithContentDescription(localized(MR.strings.pref_library_update_smart_update)).performClick()
            onNodeWithText(localized(KMR.strings.label_to_be_updated)).assertExists()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size == 4 }
            onNodeWithText("Delta").assertExists()

            onNodeWithContentDescription(localized(MR.strings.pref_library_update_smart_update)).performClick()
            onNodeWithText(localized(MR.strings.label_upcoming)).assertExists()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size == 3 }
        }
    }
}
