package zoukan.desktop.e2e

import android.app.Application
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.utils.MdUtil
import exh.source.EnhancedHttpSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.discord.DiscordPresence
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * One setting on each settings page that only the smoke test opens: Reader, Security, E-Hentai,
 * Tracking, Connections, MangaDex and About. Each step changes the setting through its control,
 * checks the stored preference and, where one exists offline, the effect elsewhere in the app,
 * then puts the preference back. Nothing here reaches a network: no update check, no tracker
 * login, no Discord, and E-Hentai is only listed, never opened.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SettingsPagesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPagesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun oneSettingPerPageHasItsEffect() {
        journey = Journey.start("settings-pages")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                /**
                 * Opens Settings as a screen of its own: the one open before is closed first, so
                 * its page list (the MangaDex page is listed only when a source was there as it
                 * opened) is composed afresh.
                 */
                fun openSettings() {
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(localized(MR.strings.action_search_settings)).fetchSemanticsNodes().isEmpty() }
                    runOnUiThread { HomeScreen.openSettings(navigator!!) }
                }

                /** Scrolls the open settings [list] to the row with [text] and clicks that text. */
                fun ComposeUiTest.clickRow(list: SemanticsNodeInteraction, text: String) {
                    list.performScrollToNode(hasText(text))
                    onAllNodesWithText(text).onFirst().performClick()
                }

                step("Reader: the default reading mode Webtoon opens a chapter in the webtoon reader") {
                    openSettings()
                    val list = settingsPage(SettingsCategory.Reader)
                    clickRow(list, localized(MR.strings.pref_viewer_type))
                    waitFor(hasText(ReadingModes.label(ReadingModes.WEBTOON)))
                    onAllNodesWithText(ReadingModes.label(ReadingModes.WEBTOON)).onFirst().performClick()
                    waitOrDump("for the preference") { preferences.defaultReadingMode().get() == ReadingModes.WEBTOON }
                    shot("1-reader-setting")

                    // The tap zones overlay a webtoon's first open and tint its pages
                    preferences.showNavigationOverlayOnStart().set(false)
                    preferences.showNavigationOverlayNewUser().set(false)
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodesWithText("Start").onFirst().performClick()
                    // The reader names its mode for a moment when it opens
                    waitFor(hasText(ReadingModes.label(ReadingModes.WEBTOON)), timeout = 60_000)
                    waitOrDump("for the first page", timeout = 60_000) { pageDrew(FixtureCatalog.pages("jpeg").first()) }
                    shot("1-reader-webtoon")
                    runOnUiThread { while (navigator?.canPop == true) navigator.pop() }
                    preferences.defaultReadingMode().set(ReadingModes.RIGHT_TO_LEFT)
                    preferences.showNavigationOverlayOnStart().set(false)
                    preferences.showNavigationOverlayNewUser().set(true)
                }

                step("Security: incognito mode in Settings shows the banner") {
                    openSettings()
                    val list = settingsPage(SettingsCategory.Security)
                    val title = localized(MR.strings.pref_incognito_mode)
                    val incognito = Injekt.get<Incognito>()
                    incognito.mode().get() shouldBe false
                    // The page itself says "Incognito mode" more than once; the banner is one more
                    list.performScrollToNode(hasText(localized(DMR.strings.reading_keeps_no_history_progress_or)))
                    val without = onAllNodesWithText(title).fetchSemanticsNodes().size
                    clickRow(list, localized(DMR.strings.reading_keeps_no_history_progress_or))
                    waitOrDump("for incognito mode") { incognito.mode().get() }
                    // The banner above every screen and the row itself
                    waitOrDump("for the incognito banner") { onAllNodesWithText(title).fetchSemanticsNodes().size == without + 1 }
                    shot("2-security-incognito")
                    clickRow(list, localized(DMR.strings.reading_keeps_no_history_progress_or))
                    waitOrDump("for incognito mode off") { !incognito.mode().get() }
                    waitOrDump("for the banner to go") { onAllNodesWithText(title).fetchSemanticsNodes().size == without }
                }

                step("E-Hentai: enabling it lists the E-Hentai source in Browse") {
                    val exh = Injekt.get<exh.source.ExhPreferences>()
                    exh.isHentaiEnabled().get() shouldBe false
                    openSettings()
                    val list = settingsPage(SettingsCategory.EHentai)
                    clickRow(list, localized(DMR.strings.adds_the_built_in_e_hentai))
                    waitOrDump("for E-Hentai on") { exh.isHentaiEnabled().get() }
                    shot("3-ehentai-setting")
                    runOnUiThread { while (navigator?.canPop == true) navigator.pop() }
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    // Listed only: opening it would fetch e-hentai.org
                    waitFor(hasText("E-Hentai"))
                    shot("3-ehentai-listed")
                    exh.isHentaiEnabled().set(false)
                }

                step("Tracking: an option changes and no tracker is signed in") {
                    val track = Injekt.get<eu.kanade.domain.track.service.TrackPreferences>()
                    val before = track.trackOnAddingToLibrary().get()
                    openSettings()
                    val list = settingsPage(SettingsCategory.Tracking)
                    clickRow(list, localized(DMR.strings.track_when_adding_to_the_library))
                    waitOrDump("for the option to flip") { track.trackOnAddingToLibrary().get() == !before }
                    waitFor(hasText(localized(DMR.strings.not_signed_in)))
                    shot("4-tracking")
                    track.trackOnAddingToLibrary().set(before)
                }

                step("Connections: Discord Rich Presence is off, with no option rows") {
                    openSettings()
                    settingsPage(SettingsCategory.Connections)
                    waitFor(hasText(localized(KMR.strings.pref_discord_rpc)))
                    // Turning it on would reach for a Discord app on this machine: left to a manual check
                    Injekt.get<DiscordPresence>().enabled().get() shouldBe false
                    onAllNodesWithText(localized(DMR.strings.hide_what_i_read)).fetchSemanticsNodes().size shouldBe 0
                    shot("5-connections")
                }

                step("MangaDex: not signed in, and the sync dialog opens and cancels without syncing") {
                    // The page lists its rows only with a MangaDex source: the extension's stand-in
                    // inside the app's enhanced MangaDex, beside the fixture source, as in the smoke test
                    val tests = Injekt.get<BuiltInSources>().testSources
                    val fixtureSources = tests.value
                    val extension = eu.kanade.tachiyomi.extension.all.mangadex.MangaDex()
                    tests.value = fixtureSources + EnhancedHttpSource(extension, MangaDex(extension, Injekt.get<Application>()))
                    try {
                        waitOrDump("for the MangaDex source") { MdUtil.getEnabledMangaDexs(Injekt.get()).isNotEmpty() }
                        openSettings()
                        val list = settingsPage(SettingsCategory.MangaDex)
                        waitFor(hasText(localized(DMR.strings.not_signed_in)))
                        clickRow(list, localized(SYMR.strings.mangadex_sync_follows_to_library))
                        // The dialog's title is the row's title again
                        waitOrDump("for the dialog") { onAllNodesWithText(localized(SYMR.strings.mangadex_sync_follows_to_library)).fetchSemanticsNodes().size == 2 }
                        shot("6-mangadex-sync")
                        // A follow status is a setting of the dialog, but nothing is confirmed: that would call MangaDex
                        onAllNodesWithText(localized(MR.strings.action_cancel)).onFirst().performClick()
                        waitOrDump("for the dialog to close") { onAllNodesWithText(localized(MR.strings.action_cancel)).fetchSemanticsNodes().isEmpty() }
                        toasts.none { it.contains("MangaDex", ignoreCase = true) } shouldBe true
                        runOnUiThread { navigator?.popUntilRoot() }
                    } finally {
                        tests.value = fixtureSources
                    }
                }

                step("About: include test releases changes; automatic update checks stay off") {
                    openSettings()
                    val list = settingsPage(SettingsCategory.About)
                    val before = Updates.includePrereleases().get()
                    clickRow(list, localized(DMR.strings.include_test_releases))
                    waitOrDump("for the option to flip") { Updates.includePrereleases().get() == !before }
                    // Not "check for updates" and not What's new: both reach GitHub
                    Updates.autoCheck().get() shouldBe false
                    shot("7-about")
                    Updates.includePrereleases().set(before)
                }
            }
        }
    }
}
