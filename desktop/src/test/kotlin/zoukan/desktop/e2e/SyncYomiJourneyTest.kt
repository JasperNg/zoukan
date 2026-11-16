package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import com.sun.net.httpserver.HttpExchange
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.more.SettingsScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.openSettingsCategory
import kotlin.time.Duration.Companion.minutes

/**
 * Syncs the library with SyncYomi against the fixture server's fake ([FixtureSyncServer]), through
 * Settings > Data: the first sync uploads the library, another device's backup comes down and
 * merges into the library, a wrong API key ends in an error message, and a 412 (another device
 * pushed first) leaves the library as it was until the next sync.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SyncYomiJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SyncYomiJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun syncUpMergeTheRemoteAndSurviveABadKeyAndAConflict() {
        journey = Journey.start("sync-syncyomi")
        val preferences = Injekt.get<DesktopPreferences>()
        val syncPreferences = Injekt.get<SyncPreferences>()
        var navigator: Navigator? = null
        val sync = FixtureSyncServer(journey.server)
        val steps = SyncSteps(journey)

        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        steps.markRead(formats.id, 1)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                with(steps) {
                    step("Settings > Data: SyncYomi, its host and API key") {
                        openSettingsCategory(SettingsCategory.Data)
                        runOnUiThread { navigator?.push(SettingsScreen) }
                        waitFor(hasText("Search settings"))
                        choose("Sync service", "SyncYomi")
                        editText("Host", sync.clientHost)
                        editText("API key", sync.apiKey)
                        // When to sync: also when a chapter opens
                        syncPreferences.getSyncTriggerOptions().syncOnChapterOpen shouldBe false
                        toggleInDialog("When to sync", "When a chapter opens", checkboxes = 4, index = 2)
                        syncPreferences.getSyncTriggerOptions().syncOnChapterOpen shouldBe true
                        shot("1-configured")
                    }

                    step("Sync now uploads the library") {
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.INFO
                        sync.calls.filter { it.path == "/api/sync/content" }.map { it.method to it.status } shouldContainExactly
                            listOf("GET" to 404, "PUT" to 200)
                        val uploaded = BackupCodec.decode(sync.remoteBackup!!)
                        uploaded.backupManga.map { it.title } shouldContainExactly listOf(FixtureCatalog.FORMATS_TITLE)
                        uploaded.backupManga.single().chapters.filter { it.read }.map { it.chapterNumber.toInt() } shouldContainExactly listOf(1)
                        // The app tells SyncYomi what it does: started, then succeeded
                        waitOrDump("for the sync events") { sync.events.size == 2 }
                        sync.events[0] shouldContain "SYNC_STARTED"
                        sync.events[1] shouldContain "SYNC_SUCCESS"
                        shot("2-uploaded")
                    }

                    step("Another device adds a title and reads chapter 2; Sync now brings both down") {
                        val second = backedUpTitle(source, "second")
                        sync.seed(otherDevicesBackup(sync.remoteBackup!!, FixtureCatalog.FORMATS_TITLE, second))
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.INFO
                        waitOrDump("for the merged library") { favoriteTitles().size == 2 }
                        favoriteTitles() shouldContainExactlyInAnyOrder listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second")
                        readChapters(formats.id) shouldContainExactly listOf(1, 2)
                    }

                    step("The Library shows both titles") {
                        runOnUiThread { navigator?.popUntilRoot() }
                        onAllNodesWithText("Library").onFirst().performClick()
                        waitOrDump("for both titles in the library") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                        waitFor(hasText("Fixture Second"))
                        shot("3-library")
                    }

                    step("A wrong API key: an error message, the library as it was") {
                        runOnUiThread { navigator?.push(SettingsScreen) }
                        waitFor(hasText("Search settings"))
                        editText("API key", "not-the-key")
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.ERROR
                        notice.text shouldContain "Invalid API token"
                        favoriteTitles() shouldContainExactlyInAnyOrder listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second")
                        readChapters(formats.id) shouldContainExactly listOf(1, 2)
                        shot("4-bad-key")
                    }

                    step("A 412 (another device pushed first): nothing changes, the next sync succeeds") {
                        editText("API key", sync.apiKey)
                        var conflict: (HttpExchange, List<String>) -> Boolean = { _, _ -> false }
                        conflict = { exchange, parts ->
                            if (exchange.requestMethod == "PUT" && parts == listOf("api", "sync", "content")) {
                                server.extraRoutes -= conflict
                                respond(exchange, 412, "text/plain", "ETag mismatch".toByteArray())
                                true
                            } else {
                                false
                            }
                        }
                        server.extraRoutes.add(0, conflict)
                        val lastSync = syncPreferences.lastSyncTimestamp().get()
                        val noticesBefore = notices.size
                        scrollSettingsTo("Sync now")
                        onAllNodesWithText("Sync now").onFirst().performClick()
                        waitOrDump("for the conflicting PUT") { !server.extraRoutes.contains(conflict) }
                        // The sync is over when its row says Sync now again
                        waitOrDump("for the sync to end") { onAllNodesWithText("Syncing…").fetchSemanticsNodes().isEmpty() }
                        waitForIdle()
                        favoriteTitles() shouldContainExactlyInAnyOrder listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second")
                        syncPreferences.lastSyncTimestamp().get() shouldBe lastSync
                        println("Journey $name: notices after the 412: ${notices.drop(noticesBefore)}")

                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.INFO
                        (syncPreferences.lastSyncTimestamp().get() > lastSync) shouldBe true
                        shot("5-recovered")
                    }
                }
            }
        }
    }
}
