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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.e2e.LocalEntryFixture.addLocalEntry
import zoukan.desktop.e2e.LocalEntryFixture.fingerprint
import zoukan.desktop.e2e.LocalEntryFixture.openClearManga
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * "Clear manga" on a local entry must never delete the user's own files in the local folder as
 * "downloaded data".
 *
 *   ./gradlew :desktop:journeyTest --tests "*ClearMangaLocalJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ClearMangaLocalJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `clearing downloaded data of a local entry keeps the user's files`() {
        journey = Journey.start("clear-local")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val (manga, files) = addLocalEntry()
        val before = files.associateWith { fingerprint(it) }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())

                step("Clear manga > Downloaded data, if the dialog still offers it for a local entry") {
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
                    waitForIdle()
                    Thread.sleep(1_000)
                }
            }
        }

        files.forEach { file ->
            file.exists() shouldBe true
            fingerprint(file) shouldBe before.getValue(file)
        }
    }
}
