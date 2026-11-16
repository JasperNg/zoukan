package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import okio.sink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.CreateBackupScreen
import zoukan.desktop.ui.settings.RestoreBackupScreen
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The backup screens' choices through the real UI, offline: Create backup's options (and that
 * nothing chosen means nothing to create), Restore backup for a backup that needs a source that
 * is not installed and a tracker that is not signed in, and a file that is not a backup. The
 * round trip itself is BackupRestoreJourneyTest's.
 *
 *   ./gradlew :desktop:journeyTest --tests "*BackupScreensJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class BackupScreensJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun backupAndRestoreChoices() {
        journey = Journey.start("backup-screens")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the app", 30_000) { navigator != null }

                step("Backup and restore choices") {
                    // Create backup: what goes in the backup, as Android's CreateBackupScreen
                    runOnUiThread { navigator?.push(CreateBackupScreen) }
                    waitFor(hasTestTag("create-backup-action"))
                    shot("create-backup")
                    onNodeWithTag("create-backup-action").assertIsEnabled()
                    listOf("Library entries", "Categories", "Saved Searches & Feeds", "App settings", "Extension stores", "Source settings")
                        .forEach { label ->
                            val list = onAllNodes(hasScrollAction()).onLast()
                            list.performScrollToNode(hasTestTag("backup-option:$label"))
                            onNodeWithTag("backup-option:$label").performClick()
                        }
                    // Nothing chosen: nothing to create
                    onNodeWithTag("create-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }

                    // Restore backup: a backup that needs a source that is not installed and a tracker
                    // that is not signed in shows both, as Android's RestoreBackupScreen
                    val file = File(dirs.root, "needs-more.tachibk")
                    val backup = Backup(
                        backupManga = listOf(
                            BackupManga(
                                source = 4242L,
                                url = "/ghost",
                                title = "Ghost",
                                tracking = listOf(BackupTracking(syncId = 2, libraryId = 0L)),
                            ),
                        ),
                        backupSources = listOf(BackupSource("Ghost Source", 4242L)),
                    )
                    file.sink().use { BackupCodec.write(backup, it) }
                    runOnUiThread { navigator?.push(RestoreBackupScreen(file.path)) }
                    waitFor(hasText("Missing sources:", substring = true))
                    waitFor(hasText("Ghost Source", substring = true))
                    waitFor(hasText("Trackers not logged into:", substring = true))
                    waitFor(hasText("AniList", substring = true))
                    shot("restore-backup")
                    onNodeWithTag("restore-backup-action").assertIsEnabled()
                    listOf("Library", "Categories", "App settings", "Extension stores", "Source settings", "Saved Searches & Feeds")
                        .forEach { label -> onNodeWithTag("backup-option:$label").performClick() }
                    onNodeWithTag("restore-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }

                    // A file that is not a backup cannot be restored
                    val broken = File(dirs.root, "broken.tachibk").apply { writeText("not a backup") }
                    runOnUiThread { navigator?.push(RestoreBackupScreen(broken.path)) }
                    waitFor(hasText("Invalid backup file:", substring = true))
                    onNodeWithTag("restore-backup-action").assertIsNotEnabled()
                    runOnUiThread { navigator?.pop() }
                }
            }
        }
    }
}
