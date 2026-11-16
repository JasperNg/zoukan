package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * Settings > Appearance, Library and Downloads through the real UI, offline: the color scheme,
 * dark and pure black, the custom color and a palette, library dynamic category updates, the
 * default category and Smart update dialogs, the excluded categories, and hiding History from the
 * navigation. Each setting is checked by its preference and by what it does on screen.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SettingsAppearanceLibraryJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SettingsAppearanceLibraryJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun appearanceLibraryAndDownloadSettings() {
        journey = Journey.start("settings-appearance-library")
        val preferences = Injekt.get<DesktopPreferences>()
        val ui = Injekt.get<UiPreferences>()
        val libraryPreferences = Injekt.get<LibraryPreferences>()
        LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }

                step("Settings: appearance, library and downloads") {
                    runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }
                    settingsPage(SettingsCategory.Appearance).performScrollToNode(hasText("App theme"))
                    shot("appearance")
                    // An Android color scheme, dark and pure black, with a date format
                    ui.appTheme().set(AppTheme.NORD)
                    ui.themeMode().set(ThemeMode.DARK)
                    ui.themeDarkAmoled().set(true)
                    ui.dateFormat().set("yyyy-MM-dd")
                    waitForIdle()
                    ui.appTheme().get() shouldBe AppTheme.NORD
                    ui.themeDarkAmoled().get() shouldBe true
                    shot("nord-dark")
                    ui.themeDarkAmoled().set(false)
                    ui.appTheme().set(AppTheme.CUSTOM)
                    waitFor(hasText("Custom color"))
                    onAllNodesWithText("Custom color").onFirst().performClick()
                    waitFor(hasText("Hex"))
                    shotWithPopups("custom-color")
                    // KMK's palettes: Sunset's first color, then Confirm Color sets it
                    waitFor(hasText("Sunset"))
                    onNodeWithTag("theme-palette-FF3E9CBF").performScrollTo().performClick()
                    onAllNodesWithText("Confirm Color").onFirst().performClick()
                    // The picker keeps hue, saturation and brightness, so a channel may round by one
                    waitOrDump("for the custom color", 5_000) {
                        val picked = ui.colorTheme().get()
                        listOf(16, 8, 0).all { shift -> abs((picked shr shift and 0xFF) - (0x3E9CBF shr shift and 0xFF)) <= 1 }
                    }

                    var list = settingsPage(SettingsCategory.Library)
                    // SY's Library dynamic category updates
                    list.performScrollToNode(hasText("Library dynamic category updates"))
                    onAllNodesWithText("Library dynamic category updates").onFirst().performClick()
                    waitFor(hasText("Launch category updates all the time"))
                    onAllNodesWithText("Launch category updates all the time").onFirst().performClick()
                    waitOrDump("for the dynamic updates", 5_000) { libraryPreferences.groupLibraryUpdateType().get() == GroupLibraryMode.ALL }
                    libraryPreferences.groupLibraryUpdateType().set(GroupLibraryMode.GLOBAL)
                    list.performScrollToNode(hasText("Default category"))
                    onAllNodesWithText("Default category").onFirst().performClick()
                    waitFor(hasText("Always ask"))
                    shotWithPopups("default-category")
                    onAllNodesWithText("Always ask").onFirst().performClick()
                    waitOrDump("for Always ask", 5_000) { libraryPreferences.defaultCategory().get() == -1 }

                    // To the row below Smart update, so a toast at the window's bottom does not cover it
                    list.performScrollToNode(hasText("Show unread count on Updates icon"))
                    onAllNodesWithText("Smart update").onFirst().performClick()
                    waitFor(hasText("Predict next release time"))
                    shotWithPopups("smart-update")
                    onAllNodesWithText("Cancel").onFirst().performClick()

                    list = settingsPage(SettingsCategory.Downloads)
                    list.performScrollToNode(hasText("After reading automatically delete"))
                    shot("downloads")
                    list.performScrollToNode(hasText("Excluded categories"))
                    onAllNodesWithText("Excluded categories").onFirst().performClick()
                    waitFor(hasText("OK"))
                    shotWithPopups("delete-excluded")
                    onAllNodesWithText("Cancel").onFirst().performClick()

                    // The navigation options show or hide History, as on Android: back on the Library
                    // tab (More lists History too) the navigation has it only while the option is on
                    runOnUiThread {
                        navigator?.popUntilRoot()
                        HomeScreen.selectTab(HomeTab.Library)
                    }
                    ui.showNavHistory().set(false)
                    waitOrDump("for History to leave the navigation", 10_000) { onAllNodesWithText("History").fetchSemanticsNodes().isEmpty() }
                    ui.showNavHistory().set(true)
                    waitFor(hasText("History"))
                    ui.showNavHistory().set(false)
                    waitOrDump("for History to leave the navigation again", 10_000) { onAllNodesWithText("History").fetchSemanticsNodes().isEmpty() }
                    ui.appTheme().set(AppTheme.DEFAULT)
                    ui.themeMode().set(ThemeMode.SYSTEM)
                }
            }
        }
    }
}
