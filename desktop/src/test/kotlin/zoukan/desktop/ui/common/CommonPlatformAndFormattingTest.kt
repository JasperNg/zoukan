package zoukan.desktop.ui.common

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import dev.icerock.moko.resources.desc.StringDesc
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.exception_http
import tachiyomi.i18n.exception_unknown_host
import tachiyomi.i18n.label_default
import tachiyomi.i18n.notification_update_error
import zoukan.desktop.Platform
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.ui.settings.categoriesLabel
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.date_unknown
import zoukan.i18n.desktop.source_extension_outdated
import zoukan.i18n.desktop.source_request_failed
import zoukan.i18n.desktop.source_timed_out
import zoukan.i18n.desktop.unsupported_image_format
import java.awt.Rectangle
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Tests for shared desktop platform, locale, date formatting, error text, reduced-motion,
 * and window bounds utilities.
 */
class CommonPlatformAndFormattingTest {

    @Nested
    inner class AppLanguageBehavior {

        @Test
        fun `the strings are English on a system in another language, and the region stays`() {
            val before = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                AppLanguage.applyEnglish()
                Locale.getDefault() shouldBe Locale.forLanguageTag("en-DE")
                localized(MR.strings.label_default) shouldBe "Default"
                MR.strings.label_default.localized() shouldBe "Default"
                localizedPlural(MR.plurals.notification_update_error, 2, 2) shouldBe "2 updates failed"
                Month.MARCH.getDisplayName(TextStyle.FULL, Locale.getDefault()) shouldBe "March"
            } finally {
                Locale.setDefault(before)
                StringDesc.localeType = StringDesc.LocaleType.System
            }
        }

        @Test
        fun `a script in the system locale is dropped`() {
            val before = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("zh-Hant-TW"))
                AppLanguage.applyEnglish()
                Locale.getDefault() shouldBe Locale.forLanguageTag("en-TW")
                localized(MR.strings.label_default) shouldBe "Default"
            } finally {
                Locale.setDefault(before)
                StringDesc.localeType = StringDesc.LocaleType.System
            }
        }
    }

    @Nested
    inner class DatesFormatting {

        private val today = LocalDate.of(2026, 11, 10)

        @Test
        fun `relative dates read as Android's within a week`() {
            Dates.relative(today, relative = true, today = today) shouldBe "Today"
            Dates.relative(today.minusDays(1), relative = true, today = today) shouldBe "Yesterday"
            Dates.relative(today.minusDays(3), relative = true, today = today) shouldBe "3 days ago"
            Dates.relative(today.plusDays(1), relative = true, today = today) shouldBe "Tomorrow"
            Dates.relative(today.plusDays(4), relative = true, today = today) shouldBe "In 4 days"
        }

        @Test
        fun `a missing date is localized`() {
            Dates.relative(0L) shouldBe localized(DMR.strings.date_unknown)
        }

        @Test
        fun `dates outside a week and with relative off use the date format`() {
            Dates.format(today, "yyyy-MM-dd") shouldBe "2026-11-10"
            Dates.format(today.minusDays(30), "dd/MM/yy") shouldBe "11/10/26"
        }

        @Test
        fun `time ago reads as Android's relative time span`() {
            val now = 1_000_000_000_000L
            val minute = 60_000L
            Dates.timeAgo(0L, now) shouldBe "Never"
            Dates.timeAgo(now - 30_000, now) shouldBe "Just now"
            Dates.timeAgo(now - minute, now) shouldBe "1 minute ago"
            Dates.timeAgo(now - 5 * minute, now) shouldBe "5 minutes ago"
            Dates.timeAgo(now - 60 * minute, now) shouldBe "1 hour ago"
            Dates.timeAgo(now - 3 * 60 * minute, now) shouldBe "3 hours ago"
            Dates.timeAgo(now - 24 * 60 * minute, now) shouldBe "Yesterday"
            Dates.timeAgo(now - 3 * 24 * 60 * minute, now) shouldBe "3 days ago"
        }

        @Test
        fun `category labels read as Android's`() {
            val categories = listOf(category(0, "Default", 0), category(1, "Reading", 1), category(2, "Done", 2))
            categoriesLabel(categories, emptySet(), emptySet()) shouldBe "Include: All\nExclude: None"
            categoriesLabel(categories, setOf("1"), setOf("2")) shouldBe "Include: Reading\nExclude: Done"
            categoriesLabel(categories, setOf("0", "1", "2"), emptySet()) shouldBe "Include: All\nExclude: None"
            categoriesLabel(categories, emptySet(), setOf("0", "1", "2")) shouldBe "Include: None\nExclude: All"
        }

        private fun category(id: Long, name: String, order: Long) = Category(id = id, name = name, order = order, flags = 0, hidden = false)
    }

    @Nested
    inner class ReducedMotionHelper {

        private val windows get() = Platform.current == Platform.WINDOWS

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        fun `a helper that hangs gives no answer within the time limit`() {
            // Keeps its output open and writes nothing for 30 s
            val hang = if (windows) arrayOf("cmd", "/c", "ping -n 30 127.0.0.1 >NUL") else arrayOf("sleep", "30")
            val started = System.nanoTime()
            ReducedMotion.run(*hang, timeoutSeconds = 1) shouldBe null
            val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
            seconds shouldBeLessThan 5L
        }

        @Test
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        fun `a helper that answers is read`() {
            val echo = if (windows) arrayOf("cmd", "/c", "echo 1") else arrayOf("echo", "1")
            ReducedMotion.run(*echo) shouldBe "1"
        }
    }

    @Nested
    inner class SourceErrors {

        @Test
        fun `a source that answers 503 shows the translated server error, not the raw status`() {
            sourceErrorText(HttpException(503)) shouldBe localized(MR.strings.exception_http, 503)
        }

        @Test
        fun `a timeout and an outdated extension show what to do, not a class name`() {
            sourceErrorText(java.net.SocketTimeoutException()) shouldBe localized(DMR.strings.source_timed_out)
            sourceErrorText(AbstractMethodError("eu.kanade.Foo.bar()")) shouldBe localized(DMR.strings.source_extension_outdated)
        }

        @Test
        fun `any other failure shows a generic message, never its class name or address`() {
            val expected = localized(DMR.strings.source_request_failed)
            sourceErrorText(IllegalStateException("GET https://example.org/manga/1 failed")) shouldBe expected
            sourceErrorText(java.io.FileNotFoundException("https://example.org/cover.jpg")) shouldBe expected
            sourceErrorText(Exception("  ")) shouldBe expected
        }

        @Test
        fun `an unknown host names the host, and never leaves the name empty`() {
            sourceErrorText(java.net.UnknownHostException("example.org")) shouldBe localized(MR.strings.exception_unknown_host, "example.org")
            sourceErrorText(java.net.UnknownHostException()) shouldBe localized(DMR.strings.source_request_failed)
        }

        @Test
        fun `the unsupported image format message of the page decoder is kept`() {
            val text = localized(DMR.strings.unsupported_image_format, "JPEG XL")
            sourceErrorText(UnreadableImageException(text)) shouldBe text
        }

        @Test
        fun `a failed check or a malformed address shows the generic message, not developer text`() {
            val expected = localized(DMR.strings.source_request_failed)
            sourceErrorText(IllegalArgumentException("Failed requirement.")) shouldBe expected
            sourceErrorText(IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for /p/1.jpg")) shouldBe expected
        }

        @Test
        fun `a message the source wrote for the user is kept`() {
            sourceErrorText(Exception("Log in via WebView")) shouldBe "Log in via WebView"
        }
    }

    @Nested
    inner class WindowBoundsBehavior {

        private val screen = listOf(Rectangle(0, 0, 1920, 1080))

        @Test
        fun `bounds survive saving`() {
            val bounds = WindowBounds(-1200, 40, 1100, 700, maximized = true)
            WindowBounds.decode(bounds.encode()) shouldBe bounds
        }

        @Test
        fun `bad or too small bounds are not used`() {
            WindowBounds.decode("") shouldBe null
            WindowBounds.decode("1,2,3") shouldBe null
            WindowBounds.decode("0,0,wide,700,0") shouldBe null
            WindowBounds.decode("0,0,300,200,0") shouldBe null
        }

        @Test
        fun `the default window fits a small screen`() {
            val state = WindowBounds.initialState("", listOf(Rectangle(0, 0, 1280, 680)))
            (state.size.height.value <= 680f) shouldBe true
            (state.size.width.value <= 1280f) shouldBe true
        }

        @Test
        fun `a window saved on a large monitor is clamped to the laptop screen`() {
            val saved = WindowBounds(0, 0, 2560, 1400, false).encode()
            val state = WindowBounds.initialState(saved, listOf(Rectangle(0, 0, 1366, 728)))
            (state.size.width.value <= 1366f) shouldBe true
            (state.size.height.value <= 728f) shouldBe true
            state.position shouldBe WindowPosition(0.dp, 0.dp)
        }

        @Test
        fun `a maximized window keeps its placement`() {
            val saved = WindowBounds(0, 0, 1280, 860, true).encode()
            WindowBounds.initialState(saved, screen).placement shouldBe WindowPlacement.Maximized
        }

        @Test
        fun `the minimum size never exceeds a small screen`() {
            val small = WindowBounds.minimumSize(Rectangle(0, 0, 853, 480))
            small.width shouldBe 800
            small.height shouldBe 480
            val tiny = WindowBounds.minimumSize(Rectangle(0, 0, 700, 400))
            tiny.width shouldBe 700
            WindowBounds.minimumSize(null) shouldBe java.awt.Dimension(WindowBounds.MIN_WIDTH, WindowBounds.MIN_HEIGHT)
        }

        @Test
        fun `a window on a screen that is gone loses its position`() {
            WindowBounds(100, 100, 1280, 860, false).onScreen(screen) shouldBe WindowBounds(100, 100, 1280, 860, false)
            // On a second monitor to the left that is no longer there
            WindowBounds(-1800, 100, 1280, 860, false).onScreen(screen) shouldBe null
            // Only a sliver of the title bar would show
            WindowBounds(1900, 100, 1280, 860, false).onScreen(screen) shouldBe null
            // Title bar above the top of the screen
            WindowBounds(100, -200, 1280, 860, false).onScreen(screen) shouldBe null
        }
    }
}
