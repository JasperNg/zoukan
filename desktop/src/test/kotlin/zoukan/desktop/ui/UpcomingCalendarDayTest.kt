package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The Upcoming calendar opens on the current month and only tags that month's days, so the
 * end-to-end test must click a release day inside it, also on the last days of a month.
 */
class UpcomingCalendarDayTest {

    @Test
    fun `a release three days ahead is clicked when it is in this month`() {
        visibleCalendarDay(LocalDate.of(2026, 11, 10), listOf(1, 3)) shouldBe LocalDate.of(2026, 11, 13)
    }

    @Test
    fun `on the 29th the release tomorrow is clicked, not one in the next month`() {
        visibleCalendarDay(LocalDate.of(2026, 11, 29), listOf(1, 3)) shouldBe LocalDate.of(2026, 11, 30)
    }

    @Test
    fun `on the last day of the month today is clicked`() {
        visibleCalendarDay(LocalDate.of(2026, 12, 31), listOf(1, 3)) shouldBe LocalDate.of(2026, 12, 31)
    }
}
