package zoukan.desktop.browser.blocking

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import java.io.File

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
