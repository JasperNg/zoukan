package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.time.Duration.Companion.minutes

/**
 * Settings > Browse, Data and storage, and Advanced through the real UI, offline: the source
 * categories screen, the storage figures and the Library List export dialog, the DNS over HTTPS
 * choices, and Clear database with an entry outside the library (last, as it deletes it).
 *
 *   ./gradlew :desktop:journeyTest --tests "*SettingsBrowseDataAdvancedJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SettingsBrowseDataAdvancedJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun browseDataAndAdvancedSettings() {
        journey = Journey.start("settings-browse-data-advanced")
        val preferences = Injekt.get<DesktopPreferences>()
        val sources = Injekt.get<SourcePreferences>()
        val favorites = LocalFixtures.seedParityLibrary(Injekt.get<ZoukanStorage>())
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                runOnUiThread { HomeScreen.selectTab(HomeTab.Library) }
                waitOrDump("for the library", 30_000) { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }

                step("Settings: browse, data and storage, and advanced") {
                    var list = settingsPage(SettingsCategory.Browse)
                    list.performScrollToNode(hasText("Replace latest button"))
                    shot("browse")
                    // A source category with the local source in it, as Android stores them
                    sources.sourcesTabCategories().set(setOf("Comics"))
                    sources.sourcesTabSourcesInCategories().set(setOf("${LocalSource.ID}|Comics"))
                    onAllNodesWithText("Edit categories").onFirst().performClick()
                    waitFor(hasText("Comics"))
                    shot("source-categories")
                    runOnUiThread { navigator?.pop() }

                    list = settingsPage(SettingsCategory.Data)
                    list.performScrollToNode(hasText("Clear chapter cache"))
                    waitFor(hasText("Available: ", substring = true))
                    shot("data")
                    list.performScrollToNode(hasText("Library List"))
                    onAllNodesWithText("Library List").onFirst().performClick()
                    waitFor(hasText("Select data to include"))
                    shotWithPopups("export")
                    onAllNodesWithText("Cancel").onFirst().performClick()

                    list = settingsPage(SettingsCategory.Advanced)
                    list.performScrollToNode(hasText("DNS over HTTPS (DoH)"))
                    onAllNodesWithText("DNS over HTTPS (DoH)").onFirst().performClick()
                    waitFor(hasText("Cloudflare"))
                    shotWithPopups("doh")
                    onAllNodesWithText("Cancel").onFirst().performClick()

                    // Clear database, last: an entry outside the library, then deleted
                    runBlocking {
                        val delta = SManga.create().apply {
                            title = "Delta"
                            url = "Delta"
                        }
                        Injekt.get<NetworkToLocalManga>()(listOf(delta.toDomainManga(LocalSource.ID)))
                    }
                    list.performScrollToNode(hasText("Clear database"))
                    onAllNodesWithText("Clear database").onFirst().performClick()
                    waitFor(hasText("1 non-library entries in database"))
                    shot("clear-database")
                    byDescription("Select all").performClick()
                    byDescription("Delete").performClick()
                    waitFor(hasText("Keep entries with read chapters"))
                    onAllNodesWithText("OK").onFirst().performClick()
                    waitFor(hasText("Nothing to clear"))
                    runBlocking { Injekt.get<GetManga>().await("Delta", LocalSource.ID) } shouldBe null
                    // Only entries outside the library go: the library keeps all three
                    runBlocking { favorites.map { Injekt.get<GetManga>().await(it.id)?.favorite } } shouldBe listOf(true, true, true)
                    runOnUiThread { navigator?.pop() }
                }
            }
        }
    }
}
