package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.e2e.LocalEntryFixture.addLocalEntry
import zoukan.desktop.e2e.LocalEntryFixture.openClearManga
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.onRefreshStart
import zoukan.desktop.ui.manga.systemTrash
import java.util.Collections
import kotlin.time.Duration.Companion.minutes

/**
 * The refresh after clearing a local entry starts on the UI thread, where its busy check cannot
 * race the Refresh button, and starts once.
 *
 *   ./gradlew :desktop:journeyTest --tests "*MangaClearLocalRefreshJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MangaClearLocalRefreshJourneyTest {

    private lateinit var journey: Journey
    private val originalTrash = systemTrash

    @AfterEach
    fun close() {
        onRefreshStart = null
        systemTrash = originalTrash
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `the refresh after clearing a local entry starts on the UI thread, once`() {
        journey = Journey.start("clear-local-refresh")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val (manga, files) = addLocalEntry()
        val folder = files.first().parentFile
        // The journey must not trash anything on the machine running it
        systemTrash = { true }
        val starts: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        // The UI thread is the one Dispatchers.Main runs on: the Swing thread in the app, the emulated main looper here
        val uiThread = runBlocking { withContext(Dispatchers.Main) { Thread.currentThread() } }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())
                onRefreshStart = { thread -> starts += thread }

                step("Clear the local files and confirm: the entry refreshes") {
                    openClearManga()
                    waitFor(hasText("Local files in ${folder.name}"))
                    onNodeWithText("Local files in ${folder.name}").performClick()
                    onNode(hasText("OK") and isEnabled()).performClick()
                    waitFor(hasText("Move to trash"))
                    onNodeWithText("Move to trash").performClick()
                    waitOrDump("for the refresh to start", timeout = 10_000) { starts.isNotEmpty() }
                    waitForIdle()
                    Thread.sleep(1_000)
                }
            }
        }

        // One refresh, started on the UI thread
        starts shouldBe listOf(uiThread)
    }
}
