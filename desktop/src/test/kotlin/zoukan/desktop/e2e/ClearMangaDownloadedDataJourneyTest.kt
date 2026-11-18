package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.e2e.LocalEntryFixture.addLocalEntry
import zoukan.desktop.e2e.LocalEntryFixture.fingerprint
import zoukan.desktop.e2e.LocalEntryFixture.openClearManga
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * "Clear manga > Downloaded data" must never delete the user's own files in the local folder:
 * not on a local entry, and not on a merged entry that has a local part.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ClearMangaDownloadedDataJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ClearMangaDownloadedDataJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `clearing downloaded data keeps the user's local files, on a local and on a merged entry`() {
        journey = Journey.start("clear-downloaded-data")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val (local, files) = addLocalEntry()
        val before = files.associateWith { fingerprint(it) }
        val online = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)

        fun assertFilesKept(files: List<File>) = files.forEach { file ->
            file.exists() shouldBe true
            fingerprint(file) shouldBe before.getValue(file)
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(local.id)) }
                waitFor(hasText("Start") and isEnabled())

                step("Local entry: Clear manga > Downloaded data, if the dialog still offers it, keeps the files") {
                    openClearManga()
                    if (onAllNodesWithText("Downloaded data").fetchSemanticsNodes().isNotEmpty()) {
                        onAllNodesWithText("Downloaded data").onFirst().performClick()
                    }
                    waitForIdle()
                    // With nothing ticked OK stays disabled: then there is nothing to confirm
                    if (onAllNodes(hasText("OK") and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
                        onNode(hasText("OK") and isEnabled()).performClick()
                    } else {
                        onNodeWithText("Cancel").performClick()
                    }
                    awaitAppIdle()
                    assertFilesKept(files)
                }
                step("Merged entry with that local part: Clear manga > Downloaded data keeps the files") {
                    val merged = runBlocking { SmartSearchMerge().smartSearchMerge(online, local.id) }
                    runOnUiThread { navigator?.push(MangaScreen(merged.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    openClearManga()
                    onAllNodesWithText("Downloaded data").onFirst().performClick()
                    waitForIdle()
                    onNode(hasText("OK") and isEnabled()).performClick()
                    awaitAppIdle()
                }
            }
        }

        assertFilesKept(files)
    }
}
