package zoukan.desktop.ui.common

import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * Dates as the Android app shows them: in the date format of Settings > Appearance, and, when
 * "Relative timestamps" is on, as "Today", "Yesterday" or "3 days ago" within a week (Android's
 * LocalDate.toRelativeString).
 */
object Dates {
    private val preferences by lazy { Injekt.get<UiPreferences>() }

    fun toLocalDate(epochMillis: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())

    /** [epochMillis] in the chosen date format. */
    fun format(epochMillis: Long): String = format(toLocalDate(epochMillis))

    fun format(date: LocalDate, pattern: String = preferences.dateFormat().get()): String =
        UiPreferences.dateFormat(pattern).format(date)

    /** [epochMillis] as a relative date when that setting is on, else as [format]; 0 is "N/A". */
    fun relative(epochMillis: Long): String =
        if (epochMillis == 0L) "N/A" else relative(toLocalDate(epochMillis), preferences.relativeTime().get())

    fun relative(date: LocalDate, relative: Boolean, today: LocalDate = LocalDate.now()): String {
        if (!relative) return format(date)
        val difference = ChronoUnit.DAYS.between(date, today)
        return when {
            difference < -7 -> format(date)
            difference < 0 -> if (difference == -1L) "Tomorrow" else "In ${difference.absoluteValue} days"
            difference < 1 -> "Today"
            difference < 7 -> if (difference == 1L) "Yesterday" else "$difference days ago"
            else -> format(date)
        }
    }

    /** The date formats Settings offers, as Android's ("" is the system's short format). */
    val formats = listOf("", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy")
}
