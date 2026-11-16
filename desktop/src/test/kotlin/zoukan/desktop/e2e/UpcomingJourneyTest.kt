package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.visibleCalendarDay
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Updates > Upcoming through the real UI, offline: four library entries with expected release
 * dates, the calendar and list, the next month, and the "To be updated" list.
 *
 *   ./gradlew :desktop:journeyTest --tests "*UpcomingJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class UpcomingJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun upcomingCalendarListAndSmartUpdate() {
        journey = Journey.start("upcoming")
        val preferences = Injekt.get<DesktopPreferences>()
        // "To be updated" then lists every entry that is due, whatever its unread chapters
        Injekt.get<LibraryPreferences>().autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD))

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        // Alpha tomorrow, Bravo and Charlie in three days; Delta is completed, so not upcoming
        val entries = listOf(
            Triple("Alpha", 1L, SManga.ONGOING),
            Triple("Bravo", 3L, SManga.ONGOING),
            Triple("Charlie", 3L, SManga.PUBLISHING_FINISHED),
            Triple("Delta", 2L, SManga.COMPLETED),
        )
        runBlocking {
            val created = Injekt.get<NetworkToLocalManga>()(
                entries.map { (title, _, _) -> Manga.create().copy(url = "/$title", ogTitle = title, source = LocalSource.ID) },
            )
            created.forEach { manga ->
                val (_, days, status) = entries.first { it.first == manga.title }
                val next = today.plusDays(days).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                Injekt.get<MangaRepository>().update(
                    MangaUpdate(
                        id = manga.id,
                        favorite = true,
                        dateAdded = System.currentTimeMillis(),
                        status = status.toLong(),
                        nextUpdate = next,
                    ),
                )
            }
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) }

                step("Open Upcoming from the Updates tab") {
                    waitFor(hasText("Library"))
                    onAllNodesWithText("Updates").onFirst().performClick()
                    byDescription("View Upcoming Updates").performClick()
                    waitFor(hasText("Tomorrow"))
                    waitFor(hasText("In 3 days"))
                    onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size shouldBe 3
                    onAllNodesWithText("Delta").fetchSemanticsNodes().size shouldBe 0
                    shot("1-upcoming")
                }

                step("Click a day and change the month") {
                    // The calendar opens on this month and tags only its days: on the 29th-31st,
                    // today + 3 is not on screen, so the day comes from the visible grid
                    val day = visibleCalendarDay(today, listOf(1L, 3L))
                    check(YearMonth.from(day) == YearMonth.now()) { "$day is not in the month the calendar shows" }
                    onAllNodesWithTag("upcoming-day-${day.dayOfMonth}").onFirst().performClick()
                    byDescription("Next Month").performClick()
                    val next = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.now().plusMonths(1))
                    waitFor(hasText(next))
                    shot("2-next-month")
                    byDescription("Previous Month").performClick()
                    val current = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(YearMonth.now())
                    waitFor(hasText(current))
                }

                step("Smart update: the entries the next update checks") {
                    byDescription("Smart update").performClick()
                    waitFor(hasText("To be updated"))
                    waitOrDump("for the four entries to be updated") { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().size == 4 }
                    shot("3-to-be-updated")
                    byDescription("Smart update").performClick()
                    waitFor(hasText("Upcoming"))
                }

                step("Open an entry") {
                    onAllNodesWithText("Alpha").onFirst().performClick()
                    waitOrDump("for the entry to open", 10_000) { onAllNodesWithTag("upcoming-item").fetchSemanticsNodes().isEmpty() }
                    shot("4-entry")
                }
            }
        }
    }
}
