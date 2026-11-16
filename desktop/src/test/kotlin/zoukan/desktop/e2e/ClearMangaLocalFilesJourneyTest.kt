package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
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
import zoukan.desktop.ui.manga.systemTrash
import java.io.File
import java.util.Collections
import kotlin.time.Duration.Companion.minutes

/**
 * The user's own local files go only when the user picks them under their own label and
 * confirms a second question, and then to the trash, not deleted.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ClearMangaLocalFilesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ClearMangaLocalFilesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun `local files are removed only after the explicit local files confirmation`() {
        journey = Journey.start("clear-local-files")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val (manga, files) = addLocalEntry()
        val folder = files.first().parentFile
        val before = files.associateWith { fingerprint(it) }
        // The journey must not trash anything on the machine running it: the hook records the folder
        val trashed: MutableList<File> = Collections.synchronizedList(mutableListOf())
        systemTrash = {
            trashed += it
            true
        }

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())

                step("Tick the local files and confirm the first dialog: the second question appears") {
                    openClearManga()
                    waitFor(hasText("Local files in ${folder.name}"))
                    onNodeWithText("Local files in ${folder.name}").performClick()
                    onNode(hasText("OK") and isEnabled()).performClick()
                    waitFor(hasText("Move your local files to the trash?"))
                }
                step("Cancel the second question: nothing moves") {
                    onNodeWithText("Cancel").performClick()
                    waitForIdle()
                    Thread.sleep(1_000)
                    trashed.shouldBeEmpty()
                    files.forEach { fingerprint(it) shouldBe before.getValue(it) }
                }
                step("Ask again and confirm: the folder goes to the trash") {
                    openClearManga()
                    waitFor(hasText("Local files in ${folder.name}"))
                    onNodeWithText("Local files in ${folder.name}").performClick()
                    onNode(hasText("OK") and isEnabled()).performClick()
                    waitFor(hasText("Move to trash"))
                    onNodeWithText("Move to trash").performClick()
                    waitOrDump("for the folder to reach the trash", timeout = 10_000) { trashed.isNotEmpty() }
                }
            }
        }

        trashed.map { it.canonicalFile } shouldBe listOf(folder.canonicalFile)
    }
}
