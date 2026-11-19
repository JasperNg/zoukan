package zoukan.desktop.ui.onboarding

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ThemeMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.Executors

/**
 * Isolated Compose UI test for [OnboardingScreen] and [FirstRun]:
 * theme choice, storage folder validation (unwritable holds Next back; writable enables Next
 * without creating the folder), completing the guides step, and skipping onboarding when a
 * library already exists.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingBehaviorTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var temp: File

    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var store: InMemoryPreferenceStore
    private lateinit var desktopPrefs: DesktopPreferences
    private lateinit var uiPrefs: UiPreferences
    private lateinit var storage: ZoukanStorage

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        store = InMemoryPreferenceStore()
        desktopPrefs = DesktopPreferences(store)
        uiPrefs = UiPreferences(store)
        val dirs = ZoukanDirs(temp)
        storage = ZoukanStorage(dirs, store)

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(desktopPrefs)
        Injekt.addSingleton(uiPrefs)
        Injekt.addSingleton(dirs)
        Injekt.addSingleton(storage)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    @Test
    fun `onboarding walks theme, storage readiness without creating folder, and finish`() {
        val next = hasText(localized(MR.strings.nav_zone_next)) and hasClickAction()
        val finish = hasText(localized(MR.strings.onboarding_action_finish)) and hasClickAction()
        val back = hasText(localized(MR.strings.action_webview_back)) and hasClickAction()

        val blocker = File(temp, "blocker.txt").apply { writeText("file, not folder") }
        val unwritable = File(blocker, "storage")
        val chosen = File(temp, "chosen-storage")
        var completed = 0

        runDesktopComposeUiTest {
            setContent {
                OnboardingScreen(
                    onComplete = { completed++ },
                    onOpenSettings = {},
                )
            }
            onNodeWithText(localized(MR.strings.onboarding_heading)).assertExists()

            // Theme step: pick Dark
            onNodeWithText(localized(MR.strings.theme_dark)).performClick()
            waitForIdle()
            uiPrefs.themeMode().get() shouldBe ThemeMode.DARK

            // Storage step: unwritable disables Next
            storage.folderPreference.set(unwritable.path)
            onAllNodes(next).onFirst().performClick()
            waitForIdle()
            onNodeWithText(localized(MR.strings.pref_storage_location)).assertExists()
            onAllNodes(next).onFirst().assertIsNotEnabled()

            // Valid folder enables Next without creating the folder yet
            storage.folderPreference.set(chosen.path)
            onAllNodes(back).onFirst().performClick()
            waitForIdle()
            onAllNodes(next).onFirst().performClick()
            waitForIdle()
            onAllNodes(next).onFirst().assertIsEnabled()
            chosen.exists() shouldBe false

            // Guides step -> Finish completes onboarding
            onAllNodes(next).onFirst().performClick()
            waitForIdle()
            onNodeWithText(localized(MR.strings.getting_started_guide)).assertExists()
            completed shouldBe 0
            onAllNodes(finish).onFirst().performClick()
            waitForIdle()
            completed shouldBe 1
        }
    }

    @Test
    fun `first run skips onboarding and marks it shown when a library already exists`() {
        desktopPrefs.shownOnboardingFlow().get() shouldBe false
        Injekt.addSingleton(mockk<GetFavorites> { coEvery { await() } returns listOf(Manga.create().copy(id = 1L, favorite = true)) })

        runDesktopComposeUiTest {
            setContent { FirstRun(desktopPrefs, navigator = { null }) }
            Await.until("shownOnboardingFlow marked done") { desktopPrefs.shownOnboardingFlow().get() }
            waitForIdle()
            onAllNodesWithTag("onboarding").fetchSemanticsNodes() shouldBe emptyList()
        }
    }
}
