package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import kotlin.time.Duration.Companion.minutes

/**
 * "Clear manga > Downloaded data" on a merged entry must leave the files of its local part alone.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ClearMangaMergedJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ClearMangaMergedJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `clearing a merged entry keeps the files of its local part`() {
        journey = Journey.start("clear-merged")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val (local, files) = addLocalEntry()
        val before = files.associateWith { fingerprint(it) }
        val online = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val merged = runBlocking { SmartSearchMerge().smartSearchMerge(online, local.id) }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(merged.id)) }
                waitFor(hasText("Start") and isEnabled())

                step("Clear manga > Downloaded data on the merged entry") {
                    openClearManga()
                    onAllNodesWithText("Downloaded data").onFirst().performClick()
                    waitForIdle()
                    onNode(hasText("OK") and isEnabled()).performClick()
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
