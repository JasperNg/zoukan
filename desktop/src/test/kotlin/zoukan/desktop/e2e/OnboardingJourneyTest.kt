package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The first-run setup on a new install: it walks the theme, the storage location (a folder that
 * cannot be written holds Next back) and the guides, and finishing it is remembered. Someone with
 * a library already is not asked.
 *
 * The setup is turned on by `ZoukanContent(firstRun = true)`, as the window does at launch.
 *
 *   ./gradlew :desktop:journeyTest --tests "*OnboardingJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun firstRunWalksTheStepsAndALibrarySkipsIt() {
        journey = Journey.start("onboarding")
        val preferences = Injekt.get<DesktopPreferences>()
        val ui = Injekt.get<UiPreferences>()
        val storage = Injekt.get<ZoukanStorage>()
        val onboarding = hasTestTag("onboarding")
        val next = hasText("Next") and hasClickAction()

        // A fresh data folder: nothing has been shown yet
        preferences.shownOnboardingFlow().get() shouldBe false

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences, firstRun = true) }

                step("The setup covers a new install") {
                    waitFor(onboarding)
                    waitFor(hasText("Welcome!"))
                    shot("1-theme")
                }

                step("Theme: pick Dark") {
                    ui.themeMode().get() shouldBe ThemeMode.SYSTEM
                    onAllNodesWithText("Dark").onFirst().performClick()
                    waitOrDump("for the dark theme") { ui.themeMode().get() == ThemeMode.DARK }
                }

                // The folder is chosen with a native dialog, so the preference is set as the
                // dialog does: a path under a file cannot be made into a folder
                val blocker = File(dirs.root, "blocker.txt").apply { writeText("a file, not a folder") }
                val unwritable = File(blocker, "storage")

                step("Storage: a folder that cannot be written holds Next back") {
                    storage.folderPreference.set(unwritable.path)
                    onAllNodes(next).onFirst().performClick()
                    waitFor(hasText("Storage location"))
                    onAllNodes(next).onFirst().assertIsNotEnabled()
                    shot("2-storage-invalid")
                }

                val chosen = File(dirs.root, "chosen-storage")

                step("Storage: a folder Zoukan can write to lets Next through") {
                    storage.folderPreference.set(chosen.path)
                    storage.root shouldBe chosen
                    // Back and forward again, so the step asks whether it is complete again
                    onAllNodes(hasText("Back") and hasClickAction()).onFirst().performClick()
                    waitFor(hasText("Welcome!"))
                    onAllNodes(next).onFirst().performClick()
                    waitFor(hasText("Storage location"))
                    onAllNodes(next).onFirst().assertIsEnabled()
                    chosen.isDirectory shouldBe true
                    storage.folderPreference.get() shouldBe chosen.path
                    shot("3-storage-valid")
                }

                step("Guides, then finish") {
                    onAllNodes(next).onFirst().performClick()
                    waitFor(hasText("Getting started guide"))
                    waitFor(hasText("Get started") and hasClickAction())
                    shot("4-guides")
                    preferences.shownOnboardingFlow().get() shouldBe false
                    onAllNodes(hasText("Get started") and hasClickAction()).onFirst().performClick()
                    waitOrDump("for the setup to close") { onAllNodes(onboarding).fetchSemanticsNodes().isEmpty() }
                    waitFor(hasText("Library"))
                    preferences.shownOnboardingFlow().get() shouldBe true
                    ui.themeMode().get() shouldBe ThemeMode.DARK
                    storage.root shouldBe chosen
                    shot("5-finished")
                }
            }

            // Someone who already has a library is not asked, and is marked as done
            step("A library skips the setup") {
                storage.folderPreference.set("")
                preferences.shownOnboardingFlow().set(false)
                addToLibrary(FixtureCatalog.FORMATS_TITLE)
                runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                    setContent { ZoukanContent(preferences, firstRun = true) }
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    waitOrDump("for the first run check to mark the setup done") { preferences.shownOnboardingFlow().get() }
                    waitForIdle()
                    onAllNodes(onboarding).fetchSemanticsNodes().size shouldBe 0
                    shot("6-library-skips")
                }
            }
        }
    }
}
