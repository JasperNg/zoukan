package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.more.SettingsScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * Syncs the library over WebDAV against the fixture server's fake ([FixtureSyncServer]), through
 * Settings > Data: the first sync uploads the library (MKCOL, then PUT), another device's backup
 * on the remote comes down and merges into the library, wrong credentials end in an error
 * message with the library untouched, and resetting the last sync time asks first.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SyncWebDavJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SyncWebDavJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun syncUpMergeTheRemoteAndSurviveBadCredentials() {
        journey = Journey.start("sync-webdav")
        val preferences = Injekt.get<DesktopPreferences>()
        val syncPreferences = Injekt.get<SyncPreferences>()
        var navigator: Navigator? = null
        val sync = FixtureSyncServer(journey.server)
        val steps = SyncSteps(journey)

        val formats = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        steps.markRead(formats.id, 1)
        steps.readChapters(formats.id) shouldContainExactly listOf(1)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                with(steps) {
                    step("Settings > Data: WebDAV, its URL and the credentials") {
                        openSettingsCategory(SettingsCategory.Data)
                        runOnUiThread { navigator?.push(SettingsScreen) }
                        waitFor(hasText("Settings") or hasText("Search settings"))
                        choose("Sync service", "WebDAV")
                        editText("Server URL", sync.webDavUrl)
                        editText("Username", sync.user)
                        editText("Password", sync.password)
                        // What to sync: history off
                        syncPreferences.getSyncSettings().history shouldBe true
                        toggleInDialog("What to sync", "History", checkboxes = 12, index = 4)
                        syncPreferences.getSyncSettings().history shouldBe false
                        shot("1-configured")
                    }

                    step("Sync now uploads the library: MKCOL, then PUT") {
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.INFO
                        sync.calls.map { it.method to it.status } shouldContainExactly listOf("MKCOL" to 201, "GET" to 404, "PUT" to 201)
                        val uploaded = BackupCodec.decode(sync.remoteBackup!!)
                        uploaded.backupManga.map { it.title } shouldContainExactly listOf(FixtureCatalog.FORMATS_TITLE)
                        uploaded.backupManga.single().chapters.filter { it.read }.map { it.chapterNumber.toInt() } shouldContainExactly listOf(1)
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

                    step("Wrong credentials: an error message, the library as it was") {
                        openSettingsCategory(SettingsCategory.Data)
                        runOnUiThread { navigator?.push(SettingsScreen) }
                        waitFor(hasText("Search settings"))
                        editText("Password", "not-the-password")
                        val callsBefore = sync.calls.size
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.ERROR
                        sync.calls.drop(callsBefore).first().status shouldBe 401
                        favoriteTitles() shouldContainExactlyInAnyOrder listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Second")
                        readChapters(formats.id) shouldContainExactly listOf(1, 2)
                        shot("4-bad-credentials")
                    }

                    step("With the right password the next sync succeeds") {
                        editText("Password", sync.password)
                        val notice = syncNow()
                        println("Journey $name: sync notice: $notice")
                        notice.kind shouldBe Notifications.Kind.INFO
                        (sync.calls.last().status in 200..299) shouldBe true
                        favoriteTitles().size shouldBe 2
                    }

                    step("Reset the last sync time asks first: Cancel keeps it, Reset clears it") {
                        val last = syncPreferences.lastSyncTimestamp().get()
                        (last > 0L) shouldBe true
                        val row = localized(DMR.strings.reset_the_last_sync_time)
                        val reset = localized(MR.strings.action_reset)
                        val callsBefore = sync.calls.size
                        scrollSettingsTo(row)
                        onAllNodesWithText(row).onFirst().performClick()
                        waitFor(hasText(localized(DMR.strings.reset_sync_time_warning)))
                        shot("5-reset-asks")
                        onAllNodes(hasText(localized(MR.strings.action_cancel)) and hasClickAction()).onLast().performClick()
                        waitOrDump("for the question to close") { onAllNodesWithText(localized(DMR.strings.reset_sync_time_warning)).fetchSemanticsNodes().isEmpty() }
                        syncPreferences.lastSyncTimestamp().get() shouldBe last

                        onAllNodesWithText(row).onFirst().performClick()
                        waitFor(hasText(localized(DMR.strings.reset_sync_time_warning)))
                        onAllNodes(hasText(reset) and hasClickAction()).onLast().performClick()
                        waitOrDump("for the last sync time to clear") { syncPreferences.lastSyncTimestamp().get() == 0L }
                        // Only the time: nothing is synced or sent by the reset itself, and the library stays
                        sync.calls.size shouldBe callsBefore
                        favoriteTitles().size shouldBe 2
                    }
                }
            }
        }
    }
}
