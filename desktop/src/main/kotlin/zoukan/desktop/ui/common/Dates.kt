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

    /**
     * How long ago [epochMillis] was, as Android's relativeTimeSpanString: "Never" for 0, "Just now"
     * within a minute, then minutes, hours and days, and the date from a week on.
     */
    fun timeAgo(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return "Never"
        val minutes = (now - epochMillis) / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
            days < 7 -> if (days == 1L) "Yesterday" else "$days days ago"
            else -> format(toLocalDate(epochMillis))
        }
    }

    /** The date formats Settings offers, as Android's ("" is the system's short format). */
    val formats = listOf("", "MM/dd/yy", "dd/MM/yy", "yyyy-MM-dd", "dd MMM yyyy", "MMM dd, yyyy")
}
