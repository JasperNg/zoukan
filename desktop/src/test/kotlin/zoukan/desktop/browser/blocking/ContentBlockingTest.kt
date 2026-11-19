package zoukan.desktop.browser.blocking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import xyz.nulldev.androidcompat.webkit.KcefPopupPolicy
import xyz.nulldev.androidcompat.webkit.KcefRequestFilter
import java.io.File
import java.io.IOException

/** The pure parts of the content blocker: host matching, list parsing and the block decision. */
class ContentBlockingTest {
    private val hosts = HostSet.of("ads.example.net", "tracker.example", "Popunder.Example.ORG.")

    @Test
    fun `a host set matches a host and its subdomains only`() {
        hosts.matches("ads.example.net") shouldBe true
        hosts.matches("cdn.ads.example.net") shouldBe true
        hosts.matches("ADS.example.net.") shouldBe true
        hosts.matches("popunder.example.org") shouldBe true
        hosts.matches("example.net") shouldBe false
        hosts.matches("badads.example.net") shouldBe false
        hosts.matches("") shouldBe false
        hosts.matches(null) shouldBe false
        HostSet.EMPTY.matches("ads.example.net") shouldBe false
    }

    @Test
    fun `the parser reads hosts files and whole-host filter rules`() {
        val text = """
            # a comment
            ! another comment
            [Adblock Plus 2.0]
            0.0.0.0 ads.example.net
            127.0.0.1 one.example two.example # trailing comment
            ::1 v6.example
            bare.example
            ||rule.example^
            ||third.example^${'$'}third-party
            ||Upper.Example^|
            0.0.0.0 0.0.0.0
            127.0.0.1 localhost
            192.168.1.1 router.example
            ||path.example/ads^
            ||wild*.example^
            ||script.example^${'$'}script
            @@||allowed.example^
            example.com##.ad-banner
            ##.ad
            not a host line at all
            1.2.3.4
            -bad.example
            singlelabel
        """.trimIndent()
        BlockListParser.parse(text) shouldBe listOf(
            "ads.example.net",
            "one.example",
            "two.example",
            "v6.example",
            "bare.example",
            "rule.example",
            "third.example",
            "upper.example",
        )
    }

    @Test
    fun `the parser stops at its limit and drops repeats`() {
        val text = (1..50).joinToString("\n") { "host$it.example" } + "\nhost1.example"
        BlockListParser.parse(text, limit = 10).size shouldBe 10
        BlockListParser.parse("a.example\na.example\nA.EXAMPLE") shouldBe listOf("a.example")
    }

    @Test
    fun `a listed host of another site is blocked`() {
        BlockPolicy.shouldBlock("https://cdn.ads.example.net/a.js", "https://reader.example.com/ch/1", hosts) shouldBe true
        BlockPolicy.shouldBlock("http://tracker.example/pixel.gif?x=1", "https://reader.example.com/", hosts) shouldBe true
    }

    @Test
    fun `a site's own hosts are never blocked, even when listed`() {
        BlockPolicy.shouldBlock("https://ads.example.net/banner.png", "https://www.example.net/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://tracker.example/x", "https://tracker.example/page", hosts) shouldBe false
    }

    @Test
    fun `unlisted hosts and unreadable addresses are let through`() {
        BlockPolicy.shouldBlock("https://cdn.example.com/a.js", "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", null, hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", "about:blank", hosts) shouldBe false
        BlockPolicy.shouldBlock("data:text/plain,hi", "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock(null, "https://reader.example.org/", hosts) shouldBe false
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", "https://reader.example.org/", HostSet.EMPTY) shouldBe false
    }

    @Test
    fun `Cloudflare, captcha and sign-in hosts are never blocked, whatever a list says`() {
        val everything = HostSet.of(
            "cloudflare.com",
            "hcaptcha.com",
            "recaptcha.net",
            "google.com",
            "gstatic.com",
            "anilist.co",
            "myanimelist.net",
            "ads.example.net",
        )
        val page = "https://reader.example.org/"
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://newassets.hcaptcha.com/captcha/v1/x.js",
            "https://www.recaptcha.net/recaptcha/api.js",
            "https://www.google.com/recaptcha/api2/anchor",
            "https://www.gstatic.com/recaptcha/releases/x/recaptcha__en.js",
            "https://anilist.co/api/v2/oauth/authorize",
            "https://myanimelist.net/v1/oauth2/authorize",
            "https://ads.example.net/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1",
        ).forEach { url -> BlockPolicy.shouldBlock(url, page, everything) shouldBe false }
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, everything) shouldBe true
    }

    @Test
    fun `same site compares registrable domains`() {
        BlockPolicy.sameSite("https://a.example.co.uk/x", "https://b.example.co.uk/y") shouldBe true
        BlockPolicy.sameSite("https://example.co.uk/", "https://other.co.uk/") shouldBe false
        BlockPolicy.sameSite("https://example.com/", "not a url") shouldBe false
    }

    @Test
    fun `the built-in list is valid and keeps clear of the hosts checks and sign-ins need`() {
        val text = ContentBlocker::class.java.getResourceAsStream("blocklist.txt")!!.use { it.readBytes().decodeToString() }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        // Every entry is a host the parser takes
        BlockListParser.parse(text) shouldBe lines.distinct()
        (lines.size > 50) shouldBe true
        val page = "https://reader.example.org/"
        val list = HostSet.of(lines)
        listOf(
            "https://challenges.cloudflare.com/turnstile/v0/api.js",
            "https://www.google.com/recaptcha/api.js",
            "https://www.gstatic.com/recaptcha/x.js",
            "https://hcaptcha.com/1/api.js",
            "https://fonts.googleapis.com/css2",
            "https://cdnjs.cloudflare.com/ajax/libs/x.js",
            "https://cdn.jsdelivr.net/npm/x",
            "https://ajax.googleapis.com/ajax/libs/jquery/3/jquery.min.js",
        ).forEach { url -> BlockPolicy.shouldBlock(url, page, list) shouldBe false }
        // The main source of pop-unders and ads on these sites is
        BlockPolicy.shouldBlock("https://pagead2.googlesyndication.com/x.js", page, list) shouldBe true
        // No entry is a whole public suffix
        lines.filter { !it.contains('.') }.shouldBeEmpty()
    }

    @Test
    fun `the parser reads whole-host exception rules apart from blocked hosts`() {
        val text = """
            ||ads.example^
            @@||good.example^
            @@||Third.Example^${'$'}third-party
            @@||path.example/x^
            @@||doc.example^${'$'}document
            @@ads.example
        """.trimIndent()
        val rules = BlockListParser.parseRules(text)
        rules.hosts shouldBe listOf("ads.example")
        rules.exceptions shouldBe listOf("good.example", "third.example")
        BlockListParser.parseRules("@@||a.example^\n||b.example^\n||c.example^", limit = 2) shouldBe
            BlockListParser.Rules(listOf("b.example"), listOf("a.example"))
    }

    @Test
    fun `an exception host and its subdomains are never blocked, by any list`() {
        val page = "https://reader.example.org/"
        val exceptions = HostSet.of("ads.example.net")
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, hosts, exceptions) shouldBe false
        BlockPolicy.shouldBlock("https://cdn.ads.example.net/a.js", page, hosts, exceptions) shouldBe false
        // Other hosts on the list are still blocked
        BlockPolicy.shouldBlock("https://tracker.example/x", page, hosts, exceptions) shouldBe true
        // An exception for a subdomain leaves its parent blocked
        val subdomainOnly = HostSet.of("cdn.ads.example.net")
        BlockPolicy.shouldBlock("https://ads.example.net/a.js", page, hosts, subdomainOnly) shouldBe true
    }

    @Test
    fun `block rules combine hosts and exceptions`() {
        val builtIn = BlockRules(HostSet.of("ads.example"))
        val user = BlockRules(HostSet.of("more.example"), HostSet.of("ads.example"))
        val all = builtIn + user
        all.hosts.matches("ads.example") shouldBe true
        all.hosts.matches("more.example") shouldBe true
        all.exceptions.matches("ads.example") shouldBe true
        (builtIn + BlockRules.EMPTY) shouldBe builtIn
        BlockRules.EMPTY.isEmpty shouldBe true
    }
}

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
        ContentBlocker.freshAction(enabled = true, firstCall = true, due = firstDue, hasLists = false) shouldBe
            ContentBlocker.FreshAction.MARK_CHECKED
        // The next windows are not due, so they do nothing until the week is up
        val laterDue = backoff.isDue(lastCheckMs = week, nowMs = week + 60_000)
        laterDue shouldBe false
        ContentBlocker.freshAction(enabled = true, firstCall = false, due = laterDue, hasLists = false) shouldBe
            ContentBlocker.FreshAction.NOTHING
        ContentBlocker.freshAction(enabled = true, firstCall = false, due = true, hasLists = false) shouldBe
            ContentBlocker.FreshAction.MARK_CHECKED

        // With lists, a due check downloads them, and the first call alone loads the saved copies
        ContentBlocker.freshAction(enabled = true, firstCall = true, due = true, hasLists = true) shouldBe ContentBlocker.FreshAction.REFRESH
        ContentBlocker.freshAction(enabled = true, firstCall = false, due = true, hasLists = true) shouldBe ContentBlocker.FreshAction.REFRESH
        ContentBlocker.freshAction(enabled = true, firstCall = true, due = false, hasLists = true) shouldBe ContentBlocker.FreshAction.RELOAD
        ContentBlocker.freshAction(enabled = true, firstCall = true, due = false, hasLists = false) shouldBe ContentBlocker.FreshAction.RELOAD
        ContentBlocker.freshAction(enabled = true, firstCall = false, due = false, hasLists = true) shouldBe ContentBlocker.FreshAction.NOTHING
    }

    @Test
    fun `with the blocker off, opening a browser downloads no list`() {
        for (firstCall in listOf(true, false)) {
            for (due in listOf(true, false)) {
                for (hasLists in listOf(true, false)) {
                    ContentBlocker.freshAction(enabled = false, firstCall = firstCall, due = due, hasLists = hasLists) shouldBe
                        ContentBlocker.FreshAction.NOTHING
                }
            }
        }
    }

    @Test
    fun `with the blocker on and the lists a week old, opening a browser downloads them`() {
        val week = UserBlockLists.REFRESH_INTERVAL_MS
        // Last downloaded more than a week ago: stale, so due
        val due = RefreshBackoff().isDue(lastCheckMs = 1_000, nowMs = 1_000 + week)
        due shouldBe true
        // Also on the first window after the blocker is turned on (the first call was not used up while it was off)
        ContentBlocker.freshAction(enabled = true, firstCall = true, due = due, hasLists = true) shouldBe
            ContentBlocker.FreshAction.REFRESH
        ContentBlocker.freshAction(enabled = true, firstCall = false, due = due, hasLists = true) shouldBe
            ContentBlocker.FreshAction.REFRESH
    }
}

/** When the user's block lists are loaded and downloaded: never while the blocker is off. */
class BlockListUpdaterTest {
    @TempDir
    lateinit var folder: File

    private val url = "https://lists.example/one.txt"
    private val now = 10L * UserBlockLists.REFRESH_INTERVAL_MS

    private val keys = ContentBlockerPreferences(InMemoryPreferenceStore())

    // The stored values; the store reads them on every get, so the test can change them
    private val enabled = InMemoryPreference(keys.enabled().key(), true, true)
    private val checkedAt = InMemoryPreference(keys.listsCheckedAt().key(), 0L, 0L)
    private val listUrls = InMemoryPreference(keys.listUrls().key(), url, "")

    private val preferences = ContentBlockerPreferences(InMemoryPreferenceStore(sequenceOf(enabled, checkedAt, listUrls)))

    private val downloads = mutableListOf<String>()
    private val loaded = mutableListOf<BlockRules>()

    /** Background work waiting to run, when [deferred] is on; else it runs at once. */
    private val pending = mutableListOf<() -> Unit>()
    private var deferred = false

    private val updater = BlockListUpdater(
        preferences = { preferences },
        lists = {
            UserBlockLists(folder) { downloaded ->
                downloads += downloaded
                "ads.example\n"
            }
        },
        launch = { work -> if (deferred) pending += work else work() },
        onLoaded = { loaded += it },
        clock = { now },
    )

    private fun runPending() {
        val work = pending.toList()
        pending.clear()
        work.forEach { it() }
    }

    @Test
    fun `with the blocker off, opening a browser or changing the lists downloads and loads nothing`() {
        enabled.set(false)
        updater.ensureFresh()
        updater.ensureFresh()
        updater.refreshNow()
        downloads.shouldBeEmpty()
        loaded.shouldBeEmpty()
    }

    @Test
    fun `with the blocker on and the lists stale, opening a browser downloads them`() {
        updater.ensureFresh()
        downloads shouldBe listOf(url)
        loaded.single().hosts.matches("ads.example") shouldBe true
    }

    @Test
    fun `with the blocker on, changing the lists downloads them`() {
        checkedAt.set(now)
        updater.refreshNow()
        downloads shouldBe listOf(url)
    }

    @Test
    fun `with the blocker on and the lists fresh, the saved copies are loaded without a download`() {
        checkedAt.set(now)
        updater.ensureFresh()
        downloads.shouldBeEmpty()
        loaded.size shouldBe 1
    }

    @Test
    fun `turning the blocker on after it was off still loads the lists on the next browser window`() {
        enabled.set(false)
        updater.ensureFresh()
        enabled.set(true)
        updater.ensureFresh()
        downloads shouldBe listOf(url)
    }

    @Test
    fun `a blocker turned off before the background work runs downloads nothing and keeps the first load`() {
        deferred = true
        updater.ensureFresh()
        enabled.set(false)
        runPending()
        downloads.shouldBeEmpty()
        loaded.shouldBeEmpty()

        // Turned on again, the next browser window still loads (and, being due, downloads) the lists
        deferred = false
        enabled.set(true)
        updater.ensureFresh()
        downloads shouldBe listOf(url)
        loaded.size shouldBe 1
    }

    @Test
    fun `a blocker turned off before a requested download runs downloads nothing`() {
        deferred = true
        updater.refreshNow()
        enabled.set(false)
        runPending()
        downloads.shouldBeEmpty()
    }
}

/** What extensions' hidden WebViews do with pop-ups and with the app's request filter. */
class KcefContentRulesTest {
    @AfterEach
    fun clearFilter() {
        KcefRequestFilter.filter = null
        KcefRequestFilter.reportError = null
    }

    @Test
    fun `a hidden WebView loads a pop-up in place only when the extension let scripts open windows`() {
        KcefPopupPolicy.loadInPlace("https://example.com/next", javaScriptCanOpenWindows = true) shouldBe "https://example.com/next"
        KcefPopupPolicy.loadInPlace("HTTP://example.com/", javaScriptCanOpenWindows = true) shouldBe "HTTP://example.com/"
        KcefPopupPolicy.loadInPlace("https://example.com/next", javaScriptCanOpenWindows = false) shouldBe null
    }

    @Test
    fun `a hidden WebView never loads a pop-up that is not a web address`() {
        listOf("file:///etc/hosts", "javascript:alert(1)", "about:blank", "", null).forEach {
            KcefPopupPolicy.loadInPlace(it, javaScriptCanOpenWindows = true) shouldBe null
        }
    }

    @Test
    fun `the request filter blocks what the app's filter says and fails open`() {
        KcefRequestFilter.shouldBlock("https://ads.example/x", "https://site.example/") shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { url, _ -> url.contains("ads") }
        KcefRequestFilter.shouldBlock("https://ads.example/x", "https://site.example/") shouldBe true
        KcefRequestFilter.shouldBlock("https://cdn.example/x", "https://site.example/") shouldBe false
        KcefRequestFilter.shouldBlock(null, "https://site.example/") shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> error("boom") }
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
    }

    @Test
    fun `the built-in browser's blocker throws nothing back into native code and reports the first error`() {
        val reported = mutableListOf<Throwable>()
        KcefRequestFilter.reportError = { reported += it }
        // Runs in Chromium's onBeforeResourceLoad and onBeforePopup: every failure lets the request through
        ContentBlocker.failOpen { true } shouldBe true
        ContentBlocker.failOpen { error("block list not loaded") } shouldBe false
        reported shouldBe emptyList()
        ContentBlocker.failOpen { throw OutOfMemoryError("Java heap space") } shouldBe false
        ContentBlocker.failOpen { throw NoClassDefFoundError("a class of the app") } shouldBe false
        // Once, shared with the extension WebViews' filter: the checks run for every request
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
        KcefRequestFilter.filter = KcefRequestFilter.Filter { _, _ -> throw StackOverflowError() }
        KcefRequestFilter.shouldBlock("https://ads.example/x", null) shouldBe false
        reported.map { it::class } shouldBe listOf(OutOfMemoryError::class)

        // A report that fails is not thrown back either
        KcefRequestFilter.reportError = { throw IllegalStateException("no report folder") }
        ContentBlocker.failOpen { throw StackOverflowError() } shouldBe false
    }
}
