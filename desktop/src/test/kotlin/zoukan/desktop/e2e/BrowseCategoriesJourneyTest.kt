package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.ExtensionStoresScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Browse through the real UI, offline: the source categories on the Sources tab (with the local
 * source in one), the source filter screen, the Extensions tab and the Extension stores screen
 * (Browse keeps the open tab), hiding the Feed tab, the local source's feed, and a source's menu:
 * set its categories, then disable it.
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

                step("A source's menu: set its categories, then disable it") {
                    fun rows() = onAllNodesWithText(source.name).fetchSemanticsNodes()
                    runOnUiThread { HomeScreen.selectTab(HomeTab.Browse) }
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(source.name))
                    rows() shouldHaveSize 1
                    // Android's long press: a right-click on the row opens its menu
                    onAllNodesWithText(source.name).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Set categories"))
                    onAllNodesWithText("Set categories").onFirst().performClick()
                    waitFor(hasText("OK"))
                    // The dialog's row, after the section header on the page
                    val comics = onAllNodesWithText("Comics")
                    comics[comics.fetchSemanticsNodes().size - 1].performClick()
                    shot("source-set-categories")
                    onAllNodesWithText("OK").onFirst().performClick()
                    waitOrDump("for the stored source category", 10_000) {
                        "${source.id}|Comics" in sources.sourcesTabSourcesInCategories().get()
                    }
                    sources.sourcesTabSourcesInCategories().get() shouldBe setOf("${LocalSource.ID}|Comics", "${source.id}|Comics")
                    // Listed under Comics and still under its language (Android's "Filter sources in categories" is off)
                    waitOrDump("for the source under Comics too", 10_000) { rows().size == 2 }
                    shot("source-in-category")

                    onAllNodesWithText(source.name).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Disable"))
                    onAllNodesWithText("Disable").onFirst().performClick()
                    waitOrDump("for the stored disabled source", 10_000) { source.id.toString() in sources.disabledSources().get() }
                    waitOrDump("for the source to leave every section", 10_000) { rows().isEmpty() }
                    // The local source keeps its category
                    waitFor(hasText("Local source"))
                    shot("source-disabled")
                }
            }
        }
    }
}
