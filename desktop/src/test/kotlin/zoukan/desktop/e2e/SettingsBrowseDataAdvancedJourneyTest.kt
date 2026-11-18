package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.domain.manga.model.toDomainManga
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.HomeTab
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.onboarding.Onboarding
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.time.Duration.Companion.minutes

/**
 * Settings > Browse, Data and storage, and Advanced through the real UI, offline: the source
 * categories screen, the storage figures and the Library List export dialog, the DNS over HTTPS
 * choices, Clear database with an entry outside the library, then the user agent (stored, and
 * sent by the network client to the fixture server) and the onboarding guide opened again.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SettingsBrowseDataAdvancedJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SettingsBrowseDataAdvancedJourneyTest {

    private lateinit var journey: Journey

    private companion object {
        const val AGENT = "ZoukanJourney/1.0 (fixture)"
    }

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    /** Asks the fixture server for a path with the app's network client, and returns the User-Agent headers it sent. */
    private fun sentUserAgent(): List<String> {
        val path = "/user-agent-probe-${System.nanoTime()}"
        Injekt.get<NetworkHelper>().client.newCall(Request.Builder().url(journey.server.baseUrl + path).build()).execute().close()
        return journey.server.requestLog.last { it.path == path }.userAgents
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

                step("Advanced: the user agent is stored and the network client sends it; invalid, blank and reset as on Android") {
                    val network = Injekt.get<NetworkPreferences>()
                    val userAgent = network.defaultUserAgent()
                    val default = userAgent.defaultValue()
                    userAgent.get() shouldBe default
                    sentUserAgent() shouldBe listOf(default)
                    runOnUiThread { navigator?.let { HomeScreen.openSettings(it) } }
                    val list = settingsPage(SettingsCategory.Advanced)
                    val title = localized(MR.strings.pref_user_agent_string)
                    val restart = localized(MR.strings.requires_app_restart)

                    // Scrolls a row to the top of the list by its key. Scrolling by text only brings a row
                    // just into view, and a row clipped at the page's edge misses a click on its centre.
                    fun showRow(key: String) = list.performScrollToKey(key)

                    fun edit(value: String) {
                        showRow("advanced-user-agent")
                        onAllNodesWithText(title).onFirst().performClick()
                        waitFor(hasText(localized(MR.strings.action_ok)))
                        // The page's search field has a text action too: the dialog's is the last one
                        onAllNodes(hasSetTextAction()).onLast().performTextReplacement(value)
                        onAllNodesWithText(localized(MR.strings.action_ok)).onFirst().performClick()
                        waitForIdle()
                    }

                    edit(AGENT)
                    waitOrDump("for the user agent") { userAgent.get() == AGENT }
                    waitOrDump("for the restart message") { restart in toasts }
                    // The client's interceptor reads the setting for each request
                    sentUserAgent() shouldBe listOf(AGENT)
                    shot("user-agent")

                    // A value OkHttp refuses in a header is refused, and the dialog stays open
                    edit("Bad\u0007Agent")
                    waitOrDump("for the invalid message") { localized(MR.strings.error_user_agent_string_invalid) in toasts }
                    userAgent.get() shouldBe AGENT
                    onAllNodesWithText(localized(MR.strings.action_cancel)).onFirst().performClick()
                    waitForIdle()

                    // Blank is a valid header value, so it is kept as on Android, and sent as it is
                    edit("")
                    waitOrDump("for the blank user agent") { userAgent.get() == "" }
                    sentUserAgent() shouldBe listOf("")

                    val reset = localized(MR.strings.pref_reset_user_agent_string)
                    showRow("advanced-reset-user-agent")
                    onAllNodesWithText(reset).onFirst().performClick()
                    waitOrDump("for the default user agent") { userAgent.get() == default }
                    userAgent.isSet() shouldBe false
                    sentUserAgent() shouldBe listOf(default)
                }

                step("Advanced: the onboarding guide opens over the app again and closes without changing the first run") {
                    val shown = preferences.shownOnboardingFlow().get()
                    val list = settingsPage(SettingsCategory.Advanced)
                    val guide = localized(MR.strings.pref_onboarding_guide)
                    list.performScrollToNode(hasText(guide))
                    onAllNodesWithText(guide).onFirst().performClick()
                    waitFor(hasTestTag("onboarding"))
                    waitFor(hasText("Welcome!"))
                    Onboarding.reopen.value shouldBe true
                    // A message while the guide is open (the user agent's "requires restart" a moment
                    // ago) shows above the guide's buttons, not over them: Close and Next work at once
                    Toasts.show(localized(MR.strings.requires_app_restart))
                    waitFor(hasText(localized(MR.strings.requires_app_restart)))
                    val message = onNodeWithTag("app-snackbar").getBoundsInRoot()
                    val close = onAllNodes(hasText(localized(MR.strings.action_close)) and hasClickAction()).onFirst()
                    val next = onAllNodes(hasText(localized(MR.strings.nav_zone_next)) and hasClickAction()).onFirst()
                    for (button in listOf(close, next)) {
                        val bounds = button.getBoundsInRoot()
                        // Checked on height alone: the centred snackbar is as wide as the space between the buttons
                        (message.top >= bounds.bottom || message.bottom <= bounds.top) shouldBe true
                    }
                    shot("onboarding-reopened")
                    close.performClick()
                    waitOrDump("for the guide to close") { onAllNodes(hasTestTag("onboarding")).fetchSemanticsNodes().isEmpty() }
                    Onboarding.reopen.value shouldBe false
                    preferences.shownOnboardingFlow().get() shouldBe shown
                    waitFor(hasText(guide))
                }
            }
        }
    }
}
