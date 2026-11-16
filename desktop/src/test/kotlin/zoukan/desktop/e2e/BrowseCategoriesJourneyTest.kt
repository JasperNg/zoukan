package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.ExtensionStoresScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Browse through the real UI, offline: the source categories on the Sources tab (with the local
 * source in one), the source filter screen, the Extensions tab and the Extension stores screen
 * (Browse keeps the open tab), hiding the Feed tab, and the local source's feed.
 *
 *   ./gradlew :desktop:journeyTest --tests "*BrowseCategoriesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class BrowseCategoriesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun sourceCategoriesFilterAndFeed() {
        journey = Journey.start("browse-categories")
        val preferences = Injekt.get<DesktopPreferences>()
        val sources = Injekt.get<SourcePreferences>()
        val ui = Injekt.get<UiPreferences>()
        // A source category with the local source in it, as Android stores them
        sources.sourcesTabCategories().set(setOf("Comics"))
        sources.sourcesTabSourcesInCategories().set(setOf("${LocalSource.ID}|Comics"))
        // Journeys open a source on its grid; the app opens its feed ("Replace latest button", on by default)
        ui.useNewSourceNavigation().set(true)
        var navigator: Navigator? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Browse: source categories, the source filter and the feed tab") {
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText("Comics"))
                    shot("categories")
                    byDescription("Filter sources").performClick()
                    waitFor(hasText("Filter"))
                    shot("sources-filter")
                    runOnUiThread { navigator?.pop() }
                    // Coming back to Browse keeps the open tab
                    onAllNodesWithText("Extensions", substring = true).onFirst().performClick()
                    waitOrDump("for the extensions", 10_000) { onAllNodesWithTag("extension-search").fetchSemanticsNodes().isNotEmpty() }
                    runOnUiThread { navigator?.push(ExtensionStoresScreen) }
                    waitFor(hasText("Extension stores"))
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the extensions tab to stay open", 10_000) { onAllNodesWithTag("extension-search").fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText("Sources").onFirst().performClick()
                    // Hiding the feed tab, as Android
                    waitFor(hasText("Feed"))
                    ui.hideFeedTab().set(true)
                    waitOrDump("for the Feed tab to go", 10_000) { onAllNodesWithText("Feed").fetchSemanticsNodes().isEmpty() }
                    ui.hideFeedTab().set(false)
                    // "Replace latest button" (on by default) opens the source's feed
                    waitFor(hasText("Local source"))
                    onAllNodesWithText("Local source").onFirst().performClick()
                    waitFor(hasText("This source's latest, popular and saved searches"))
                    shot("source-feed")
                    runOnUiThread { navigator?.popUntilRoot() }
                }
            }
        }
    }
}
