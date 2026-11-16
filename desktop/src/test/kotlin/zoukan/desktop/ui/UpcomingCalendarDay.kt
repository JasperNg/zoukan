package zoukan.desktop.ui

import java.time.LocalDate
import java.time.YearMonth

/**
 * The day to click in the Upcoming calendar: the first seeded release day in the month the
 * calendar opens on, or today when every release falls in the next month.
 */
internal fun visibleCalendarDay(today: LocalDate, releaseOffsets: List<Long>): LocalDate {
    val month = YearMonth.from(today)
    return releaseOffsets.sortedDescending()
        .map { today.plusDays(it) }
        .firstOrNull { YearMonth.from(it) == month }
        ?: today
}
