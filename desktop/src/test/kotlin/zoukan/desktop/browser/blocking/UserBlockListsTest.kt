package zoukan.desktop.browser.blocking

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** The user's extra block lists: their addresses, downloads, saved copies and refresh interval. */
class UserBlockListsTest {
    @TempDir
    lateinit var folder: File

    private val first = "https://lists.example/one.txt"
    private val second = "https://lists.example/two.txt"

    @Test
    fun `only https addresses are kept, without repeats and up to the limit`() {
        val text = """
            https://lists.example/one.txt
              https://lists.example/one.txt
            http://lists.example/plain.txt
            file:///etc/hosts
            not a url

            https://lists.example/two.txt
        """.trimIndent()
        UserBlockLists.parseUrls(text) shouldBe listOf(first, second)
        UserBlockLists.parseUrls((1..20).joinToString("\n") { "https://l.example/$it" }).size shouldBe UserBlockLists.MAX_LISTS
        UserBlockLists.parseUrls("") shouldBe emptyList()
    }

    @Test
    fun `downloaded lists are saved and loaded`() {
        val lists = UserBlockLists(folder) { url -> if (url == first) "0.0.0.0 a.example" else "||b.example^" }
        lists.refresh(listOf(first, second)) shouldBe 2
        val hosts = lists.load(listOf(first, second)).hosts
        hosts.matches("a.example") shouldBe true
        hosts.matches("x.b.example") shouldBe true
        // Nothing for an address without a saved copy
        lists.load(listOf("https://lists.example/never.txt")).isEmpty shouldBe true
    }

    @Test
    fun `a failed or empty download keeps the previous copy`() {
        UserBlockLists(folder) { "a.example" }.refresh(listOf(first))
        UserBlockLists(folder) { throw IOException("offline") }.refresh(listOf(first)) shouldBe 0
        UserBlockLists(folder) { "<html>not a list</html>" }.refresh(listOf(first)) shouldBe 0
        UserBlockLists(folder) { error("unused") }.load(listOf(first)).hosts.matches("a.example") shouldBe true
    }

    @Test
    fun `exception rules are saved and loaded, also from a list that holds only exceptions`() {
        val lists = UserBlockLists(folder) { url ->
            if (url == first) "||ads.example^\n@@||good.ads.example^" else "@@||cdn.example^${'$'}third-party"
        }
        lists.refresh(listOf(first, second)) shouldBe 2
        val rules = lists.load(listOf(first, second))
        rules.hosts.matches("ads.example") shouldBe true
        rules.exceptions.matches("x.good.ads.example") shouldBe true
        rules.exceptions.matches("cdn.example") shouldBe true
        rules.exceptions.matches("ads.example") shouldBe false
    }

    @Test
    fun `a removed address loses its saved copy and other files are left alone`() {
        val own = File(folder, "notes.txt").apply { writeText("keep") }
        val lists = UserBlockLists(folder) { "a.example" }
        lists.refresh(listOf(first, second))
        lists.refresh(listOf(second))
        lists.fileFor(first).exists() shouldBe false
        lists.fileFor(second).exists() shouldBe true
        own.exists() shouldBe true
    }

    @Test
    fun `a missing or unreadable folder loads nothing and never throws`() {
        UserBlockLists(File(folder, "missing")) { "a.example" }.load(listOf(first)).isEmpty shouldBe true
        val notAFolder = File(folder, "file").apply { writeText("x") }
        UserBlockLists(notAFolder) { "a.example" }.refresh(listOf(first)) shouldBe 0
    }

    @Test
    fun `lists are due once a week, at first, and when the clock went back`() {
        val week = UserBlockLists.REFRESH_INTERVAL_MS
        UserBlockLists.isDue(0, 1_000) shouldBe true
        UserBlockLists.isDue(1_000, 1_000 + week - 1) shouldBe false
        UserBlockLists.isDue(1_000, 1_000 + week) shouldBe true
        UserBlockLists.isDue(5_000, 1_000) shouldBe true
    }

    @Test
    fun `a refresh counts as done only when a list came through or there are none`() {
        val backoff = RefreshBackoff()
        backoff.recordRefresh(updated = 1, listCount = 3, nowMs = 1_000) shouldBe true
        backoff.recordRefresh(updated = 0, listCount = 0, nowMs = 1_000) shouldBe true
        backoff.recordRefresh(updated = 0, listCount = 2, nowMs = 1_000) shouldBe false
    }

    @Test
    fun `after a failed refresh the lists are tried again sooner, backing off up to a week`() {
        val hour = RefreshBackoff.FIRST_RETRY_MS
        val week = UserBlockLists.REFRESH_INTERVAL_MS
        val backoff = RefreshBackoff()
        // Never downloaded: due now
        backoff.isDue(0, 10 * hour) shouldBe true
        backoff.recordRefresh(updated = 0, listCount = 1, nowMs = 10 * hour)
        backoff.isDue(0, 10 * hour + hour - 1) shouldBe false
        backoff.isDue(0, 11 * hour) shouldBe true
        backoff.recordFailure(11 * hour)
        backoff.isDue(0, 12 * hour) shouldBe false
        backoff.isDue(0, 13 * hour) shouldBe true
        // A clock that went back is due
        backoff.isDue(0, 5 * hour) shouldBe true
        // A success goes back to the weekly refresh
        backoff.recordRefresh(updated = 1, listCount = 1, nowMs = 13 * hour)
        backoff.isDue(13 * hour, 14 * hour) shouldBe false
        backoff.isDue(13 * hour, 13 * hour + week) shouldBe true

        RefreshBackoff.delayMs(0) shouldBe 0L
        RefreshBackoff.delayMs(1) shouldBe hour
        RefreshBackoff.delayMs(3) shouldBe 4 * hour
        RefreshBackoff.delayMs(10) shouldBe week
        RefreshBackoff.delayMs(Int.MAX_VALUE) shouldBe week
    }

    @Test
    fun `a backed-off refresh still waits for the weekly refresh after a recent success`() {
        val backoff = RefreshBackoff()
        backoff.recordFailure(1_000)
        // The last good download was an hour ago: not due, whatever the backoff says
        backoff.isDue(lastCheckMs = 1_000, nowMs = 1_000 + 2 * RefreshBackoff.FIRST_RETRY_MS) shouldBe false
    }

    @Test
    fun `with no lists, opening a browser restarts the weekly clock instead of reloading every time`() {
        val week = UserBlockLists.REFRESH_INTERVAL_MS
        val backoff = RefreshBackoff()
        // Never checked and no lists: the first window marks the lists as checked
        val firstDue = backoff.isDue(lastCheckMs = 0, nowMs = week)
        firstDue shouldBe true
        ContentBlocker.freshAction(firstCall = true, due = firstDue, hasLists = false) shouldBe
            ContentBlocker.FreshAction.MARK_CHECKED
        // The next windows are not due, so they do nothing until the week is up
        val laterDue = backoff.isDue(lastCheckMs = week, nowMs = week + 60_000)
        laterDue shouldBe false
        ContentBlocker.freshAction(firstCall = false, due = laterDue, hasLists = false) shouldBe
            ContentBlocker.FreshAction.NOTHING
        ContentBlocker.freshAction(firstCall = false, due = true, hasLists = false) shouldBe
            ContentBlocker.FreshAction.MARK_CHECKED

        // With lists, a due check downloads them, and the first call alone loads the saved copies
        ContentBlocker.freshAction(firstCall = true, due = true, hasLists = true) shouldBe ContentBlocker.FreshAction.REFRESH
        ContentBlocker.freshAction(firstCall = false, due = true, hasLists = true) shouldBe ContentBlocker.FreshAction.REFRESH
        ContentBlocker.freshAction(firstCall = true, due = false, hasLists = true) shouldBe ContentBlocker.FreshAction.RELOAD
        ContentBlocker.freshAction(firstCall = true, due = false, hasLists = false) shouldBe ContentBlocker.FreshAction.RELOAD
        ContentBlocker.freshAction(firstCall = false, due = false, hasLists = true) shouldBe ContentBlocker.FreshAction.NOTHING
    }
}
