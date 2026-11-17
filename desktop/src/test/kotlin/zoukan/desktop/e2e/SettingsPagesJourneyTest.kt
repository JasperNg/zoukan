package zoukan.desktop.e2e

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.utils.MdUtil
import exh.source.EnhancedHttpSource
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.discord.DiscordPresence
import zoukan.desktop.exh.BuiltInSources
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.KeepAwake
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import zoukan.desktop.update.TestReleaseKey
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.UpdateStates
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import java.awt.Window
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

/**
 * Settings on the pages that only the smoke test opens: Reader, Security, E-Hentai, Tracking,
 * Connections, MangaDex and About. Each step changes a setting through its control, checks the
 * stored preference (in Android's format where backups carry it) and, where one exists offline,
 * the effect elsewhere in the app, then puts the preference back. Nothing here reaches a real
 * site: the update check reads a release feed on the fixture server, there is no tracker login,
 * no Discord app (only the app window starts Rich Presence), and E-Hentai is only listed.
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

                step("Reader: with Show reading mode off the reader opens without naming its mode") {
                    preferences.showReadingMode().get() shouldBe true
                    openSettings()
                    val list = settingsPage(SettingsCategory.Reader)
                    clickRow(list, localized(MR.strings.pref_show_reading_mode))
                    waitOrDump("for the preference") { !preferences.showReadingMode().get() }
                    preferences.showNavigationOverlayOnStart().set(false)
                    preferences.showNavigationOverlayNewUser().set(false)
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 2 (PNG)"))
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performClick()
                    // With the setting on, the first step saw the mode's name as the reader opened; it
                    // shows for 1.5 s from then, so watch from the click until well past that
                    val labels = ReadingModes.all.map(ReadingModes::label)
                    var named = false
                    fun look() {
                        if (labels.any { textOnScreen(it) }) named = true
                    }
                    waitOrDump("for the first page", timeout = 60_000) {
                        look()
                        pageDrew(FixtureCatalog.pages("png").first())
                    }
                    val until = System.currentTimeMillis() + 2_500
                    while (System.currentTimeMillis() < until) {
                        look()
                        Thread.sleep(100)
                    }
                    named shouldBe false
                    shot("1-reader-no-mode-name")
                    runOnUiThread { while (navigator?.canPop == true) navigator.pop() }
                    preferences.showReadingMode().set(true)
                    preferences.showNavigationOverlayNewUser().set(true)
                }

                step("Reader: scale, background, tap zones, keep screen on and page transitions set in Settings reach the reader") {
                    openSettings()
                    val list = settingsPage(SettingsCategory.Reader)
                    fun choose(row: String, option: String, done: () -> Boolean) {
                        clickRow(list, row)
                        waitFor(hasText(option))
                        onAllNodesWithText(option).onFirst().performClick()
                        waitOrDump("for $option") { done() }
                        waitForIdle()
                    }
                    // Each row is the first with its title: the pager section comes before the webtoon one
                    choose(localized(DMR.strings.page_scaling_paged_modes), FitModes.label(FitModes.ORIGINAL)) { preferences.imageScaleType().get() == FitModes.ORIGINAL }
                    choose(localized(DMR.strings.background), localized(MR.strings.white_background)) { preferences.readerTheme().get() == 0 }
                    choose(localized(MR.strings.pref_viewer_nav), localized(MR.strings.kindlish_nav)) { preferences.navigationModePager().get() == ReaderNavigation.KINDLISH }
                    clickRow(list, localized(MR.strings.pref_keep_screen_on))
                    waitOrDump("for keep screen on") { preferences.keepScreenOn().get() }
                    clickRow(list, localized(MR.strings.pref_page_transitions))
                    waitOrDump("for page transitions off") { !preferences.pageTransitionsPager().get() }
                    shot("1-reader-settings-page")

                    preferences.showNavigationOverlayOnStart().set(false)
                    preferences.showNavigationOverlayNewUser().set(false)
                    KeepAwake.readerHolds.get() shouldBe 0
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 3 (WEBP)"))
                    onAllNodesWithText("Chapter 3 (WEBP)").onFirst().performClick()
                    val pages = FixtureCatalog.pages("webp")
                    waitOrDump("for the first page", timeout = 60_000) { pageDrew(pages.first()) }
                    waitFor(hasText("1 / ${pages.size}"))
                    // Keep screen on: the open reader holds the display awake
                    KeepAwake.readerHolds.get() shouldBe 1
                    // Original size: the 600 x 900 page is not fitted to the window's height
                    val window = root().getBoundsInRoot()
                    val page = onAllNodesWithTag("page-image").onFirst().getBoundsInRoot()
                    ((page.bottom - page.top) - (window.bottom - window.top)).value.let { kotlin.math.abs(it) > 20f } shouldBe true
                    (page.right - page.left < window.right - window.left) shouldBe true
                    // The white background beside the page
                    val pixels = root().captureToImage().toPixelMap()
                    pixels[4, pixels.height / 2].let { it.red > 0.95f && it.green > 0.95f && it.blue > 0.95f } shouldBe true
                    shot("1-reader-from-settings")
                    // Kindle-ish tap zones: the lower right of the middle turns the page, where the
                    // default zones (right and left) open the menu. Page transitions are off, so it
                    // turns without the slide (the slide's own choice is ReaderReducedMotionTest's)
                    root().performMouseInput { click(Offset(width * 0.6f, height * 0.7f)) }
                    waitFor(hasText("2 / ${pages.size}"), 10_000)
                    waitOrDump("for the second page", timeout = 30_000) { pageDrew(pages[1]) }
                    runOnUiThread { while (navigator?.canPop == true) navigator.pop() }
                    waitOrDump("for the reader to let the screen sleep") { KeepAwake.readerHolds.get() == 0 }
                    preferences.imageScaleType().set(FitModes.FIT_SCREEN)
                    preferences.readerTheme().set(1)
                    preferences.navigationModePager().set(ReaderNavigation.DEFAULT)
                    preferences.keepScreenOn().set(false)
                    preferences.pageTransitionsPager().set(true)
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

                step("E-Hentai: language filtering and front page categories keep Android's format") {
                    val exh = Injekt.get<ExhPreferences>()
                    val defaultLanguages = exh.exhSettingsLanguages().get()
                    val defaultCategories = exh.exhEnabledCategories().get()
                    // The site settings show for a signed-in account; nothing here reaches the site
                    exh.isHentaiEnabled().set(true)
                    exh.enableExhentai().set(true)
                    try {
                        openSettings()
                        val list = settingsPage(SettingsCategory.EHentai)

                        // Japanese has no "original" box: English's translated is the 4th, Chinese's rewrite the 8th
                        clickRow(list, localized(DMR.strings.language_filtering))
                        waitFor(hasText(localized(DMR.strings.vietnamese)))
                        dialogBoxes(LANGUAGE_BOXES, 3).performClick()
                        dialogBoxes(LANGUAGE_BOXES, 7).performClick()
                        shot("3-ehentai-languages")
                        onAllNodesWithText(localized(MR.strings.action_ok)).onFirst().performClick()
                        waitOrDump("for the languages setting") { exh.exhSettingsLanguages().get() != defaultLanguages }
                        val lines = exh.exhSettingsLanguages().get().split("\n")
                        lines.size shouldBe 17
                        lines[1] shouldBe "false*true*false"
                        lines[2] shouldBe "false*false*true"
                        lines.filterIndexed { i, _ -> i != 1 && i != 2 }.all { it == "false*false*false" } shouldBe true

                        // Cancel keeps it; the dialog opens again on the stored choice
                        val stored = exh.exhSettingsLanguages().get()
                        clickRow(list, localized(DMR.strings.language_filtering))
                        waitFor(hasText(localized(DMR.strings.vietnamese)))
                        dialogBoxes(LANGUAGE_BOXES, 3).assertIsOn()
                        dialogBoxes(LANGUAGE_BOXES, 0).performClick()
                        onAllNodesWithText(localized(MR.strings.action_cancel)).onFirst().performClick()
                        waitForIdle()
                        exh.exhSettingsLanguages().get() shouldBe stored

                        // Stored as "is hidden" per category: Doujinshi and Misc hidden
                        clickRow(list, localized(DMR.strings.front_page_categories))
                        waitFor(hasText(localized(SYMR.strings.misc)))
                        dialogBoxes(CATEGORY_BOXES, 0).performClick()
                        dialogBoxes(CATEGORY_BOXES, 9).performClick()
                        onAllNodesWithText(localized(MR.strings.action_ok)).onFirst().performClick()
                        waitOrDump("for the categories setting") { exh.exhEnabledCategories().get() != defaultCategories }
                        exh.exhEnabledCategories().get() shouldBe "true,false,false,false,false,false,false,false,false,true"
                        shot("3-ehentai-categories")
                    } finally {
                        exh.exhSettingsLanguages().set(defaultLanguages)
                        exh.exhEnabledCategories().set(defaultCategories)
                        exh.enableExhentai().set(false)
                        exh.isHentaiEnabled().set(false)
                    }
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

                step("Connections: Discord's hidden categories are stored as category ids and hide what is read") {
                    // Only the app window starts Rich Presence (ZoukanContent does not), so turning it
                    // on here shows the option rows and reaches no Discord app
                    val presence = Injekt.get<DiscordPresence>()
                    presence.enabled().set(true)
                    val shelf = runBlocking {
                        Injekt.get<CreateCategoryWithName>().await(HIDDEN_SHELF)
                        Injekt.get<GetCategories>().await().single { it.name == HIDDEN_SHELF }
                            .also { Injekt.get<SetMangaCategories>().await(manga.id, listOf(it.id)) }
                    }
                    fun details() = runBlocking { presence.activity() }["details"]!!.jsonPrimitive.content
                    try {
                        presence.setReading(DiscordPresence.Reading(manga.id, manga.title, "Chapter 1", 1.0, null, manga.source))
                        details() shouldBe manga.title
                        openSettings()
                        val list = settingsPage(SettingsCategory.Connections)
                        clickRow(list, localized(DMR.strings.hide_these_categories))
                        waitFor(hasText(HIDDEN_SHELF))
                        // Default, then the shelf: the shelf's box is the dialog's last
                        val boxes = onAllNodes(isToggleable())
                        boxes[boxes.fetchSemanticsNodes().size - 1].performClick()
                        shot("5-discord-hidden-categories")
                        onAllNodesWithText(localized(MR.strings.action_ok)).onFirst().performClick()
                        waitOrDump("for the hidden categories") { presence.hiddenCategories().get().isNotEmpty() }
                        // Android's format: the category ids as strings
                        presence.hiddenCategories().get() shouldBe setOf(shelf.id.toString())
                        // Opening Settings set the presence's screen, which ends the reading: read again
                        presence.setReading(DiscordPresence.Reading(manga.id, manga.title, "Chapter 1", 1.0, null, manga.source))
                        details() shouldBe DiscordPresence.Screen.MANGA.details
                    } finally {
                        presence.clearReading()
                        presence.hiddenCategories().delete()
                        presence.enabled().set(false)
                        runBlocking { Injekt.get<SetMangaCategories>().await(manga.id, emptyList()) }
                    }
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

                step("About: the GitHub token goes to the secret store, never to the preferences, and Remove deletes it") {
                    val secrets = Injekt.get<SecretStore>()
                    secrets.get(UpdateChecker.TOKEN_KEY) shouldBe null
                    openSettings()
                    val list = settingsPage(SettingsCategory.About)
                    clickRow(list, localized(DMR.strings.github_token))
                    waitFor(hasText(localized(MR.strings.action_save)))
                    // The page's search field has a text action too: the dialog's is the last one
                    onAllNodes(hasSetTextAction()).onLast().performTextInput(GITHUB_TOKEN)
                    onAllNodesWithText(localized(MR.strings.action_save)).onFirst().performClick()
                    waitOrDump("for the token in the secret store") { secrets.get(UpdateChecker.TOKEN_KEY) == GITHUB_TOKEN }
                    waitFor(hasText(localized(DMR.strings.github_token_set)))
                    Injekt.get<PreferenceStore>().getAll().values.none { GITHUB_TOKEN in it.toString() } shouldBe true
                    // Nor in plain text in any other file of the data folder: preferences, database, logs.
                    // Journeys keep secrets in files (FileSecretStore), so the secret's own file is left out
                    val leaked = dirs.root.walkTopDown().filter { it.isFile && it.name != UpdateChecker.TOKEN_KEY }
                        .filter { file -> runCatching { GITHUB_TOKEN.toByteArray() in file.readBytes() }.getOrDefault(false) }
                        .toList()
                    leaked shouldBe emptyList()
                    shot("7-about-token")

                    clickRow(list, localized(DMR.strings.github_token_set))
                    waitFor(hasText(localized(MR.strings.action_remove)))
                    onAllNodesWithText(localized(MR.strings.action_remove)).onFirst().performClick()
                    waitOrDump("for the token to go") { secrets.get(UpdateChecker.TOKEN_KEY) == null }
                    waitFor(hasText(localized(DMR.strings.github_token)))
                }

                step("About: Check for updates offers a test release only with include test releases on") {
                    // A release feed on the fixture server in place of GitHub's: a stable release no
                    // newer than this build and a newer test release. Downloads are not tried: they
                    // need GitHub's API host over https and the release signature
                    val stable = AppVersion.version.toString()
                    val next = Version(AppVersion.version.parts.mapIndexed { i, part -> if (i == 0) part + 1 else 0 }).toString()
                    val feedPath = "/repos/${AppVersion.releaseRepo}/releases"
                    val feed: (com.sun.net.httpserver.HttpExchange, List<String>) -> Boolean = { exchange, _ ->
                        if (exchange.requestURI.path != feedPath) {
                            false
                        } else {
                            val body = """[
                                {"tag_name": "${AppVersion.TAG_PREFIX}$next", "name": "Zoukan $next beta", "draft": false, "prerelease": true, "html_url": "", "assets": []},
                                {"tag_name": "${AppVersion.TAG_PREFIX}$stable", "name": "Zoukan $stable", "draft": false, "prerelease": false, "html_url": "", "assets": []}
                            ]""".toByteArray()
                            exchange.responseHeaders.add("Content-Type", "application/json")
                            exchange.sendResponseHeaders(200, body.size.toLong())
                            exchange.responseBody.use { it.write(body) }
                            true
                        }
                    }
                    server.extraRoutes += feed
                    Updates.checkerForTests = UpdateChecker(Injekt.get<NetworkHelper>().client, Injekt.get(), apiUrl = server.baseUrl)
                    val before = Updates.includePrereleases().get()
                    try {
                        Updates.includePrereleases().set(false)
                        openSettings()
                        val list = settingsPage(SettingsCategory.About)
                        clickRow(list, localized(MR.strings.check_for_updates))
                        waitFor(hasText(localized(DMR.strings.zoukan_is_the_newest_version, AppVersion.name)))
                        server.requests.count { it == feedPath } shouldBe 1

                        clickRow(list, localized(DMR.strings.include_test_releases))
                        waitOrDump("for include test releases") { Updates.includePrereleases().get() }
                        clickRow(list, localized(MR.strings.check_for_updates))
                        waitFor(hasText(localized(DMR.strings.is_available, "Zoukan $next beta"), substring = true))
                        server.requests.count { it == feedPath } shouldBe 2
                        (Updates.state.value as UpdateStates.State.Result).result
                            .shouldBeInstanceOf<UpdateChecker.Result.Available>().release.version.toString() shouldBe next
                        shot("7-about-test-release")
                    } finally {
                        Updates.checkerForTests = null
                        server.extraRoutes -= feed
                        Updates.includePrereleases().set(before)
                    }
                }

                step("About: What's new shows the release notes from the fixture; only web links open, through the system") {
                    // The release by tag on the fixture server, at the GitHub API address the app uses
                    val releases = FixtureReleases(server)
                    val recorder = RecordingEffects()
                    val tag = AppVersion.TAG_PREFIX + AppVersion.version
                    releases.publish(
                        releases.release(
                            tag,
                            "Zoukan ${AppVersion.name}",
                            "## Fixed\n\n- Reading **offline** works again\n\n[Read the full notes](https://example.com/zoukan/notes)\n\n[Not a web page](file:///etc/hosts)\n",
                        ),
                    )
                    Updates.checkerForTests = UpdateChecker(releases.client(Injekt.get<NetworkHelper>().client), Injekt.get())
                    SystemEffects.current = recorder
                    try {
                        openSettings()
                        val list = settingsPage(SettingsCategory.About)
                        clickRow(list, localized(MR.strings.whats_new))
                        waitFor(hasText("Reading offline works again"))
                        waitFor(hasText("Fixed"))
                        server.requests.count { it == releases.tagPath(tag) } shouldBe 1
                        shot("8-whats-new")
                        // A link to a file stays plain text: clicking it opens nothing
                        onAllNodesWithText("Not a web page").onFirst().performClick()
                        onAllNodesWithText("Read the full notes").onFirst().performClick()
                        waitOrDump("for the link to open") { recorder.browsed.isNotEmpty() }
                        onAllNodesWithText(localized(MR.strings.update_check_open)).onFirst().performClick()
                        waitOrDump("for the release page to open") { recorder.browsed.size == 2 }
                        recorder.browsed.map { it.toString() } shouldBe listOf(
                            "https://example.com/zoukan/notes",
                            "https://github.com/${AppVersion.releaseRepo}/releases/tag/$tag",
                        )
                        recorder.other shouldBe emptyList()
                        runOnUiThread { navigator?.popUntilRoot() }
                    } finally {
                        SystemEffects.current = SystemEffects.Native
                        Updates.checkerForTests = null
                        releases.close()
                    }
                }

                step("About: an update downloads from the fixture, passes a test release key, and Install hands the checked file to the system") {
                    // The installer and its .sig at GitHub API addresses on the fixture server; the
                    // checker keeps its https and API-host rules, the client alone reaches the fixture.
                    // A Windows installer, so the update is offered on every system the tests run on
                    val releases = FixtureReleases(server)
                    val key = TestReleaseKey()
                    val recorder = RecordingEffects()
                    val next = Version(AppVersion.version.parts.mapIndexed { i, part -> if (i == 0) part + 1 else 0 }).toString()
                    val name = "Zoukan-$next.msi"
                    val installer = ByteArray(256_000) { (it % 239).toByte() }
                    releases.publish(
                        releases.release(
                            AppVersion.TAG_PREFIX + next,
                            "Zoukan $next",
                            "Faster pages",
                            assets = listOf(
                                FixtureReleases.Asset(name, installer),
                                FixtureReleases.Asset("$name.sig", key.sign(name, installer), sha256 = null),
                            ),
                        ),
                    )
                    Updates.checkerForTests = UpdateChecker(releases.client(Injekt.get<NetworkHelper>().client), Injekt.get(), platform = Platform.WINDOWS)
                    Updates.releaseSignatureForTests = key.verifier
                    SystemEffects.current = recorder
                    val before = Updates.includePrereleases().get()
                    try {
                        Updates.includePrereleases().set(false)
                        openSettings()
                        val list = settingsPage(SettingsCategory.About)
                        clickRow(list, localized(MR.strings.check_for_updates))
                        waitFor(hasText(localized(DMR.strings.is_available, "Zoukan $next"), substring = true))
                        onAllNodes(hasText(localized(MR.strings.action_download)) and hasClickAction()).onFirst().performClick()
                        waitOrDump("for the download", timeout = 60_000) { Updates.state.value is UpdateStates.State.Downloaded }
                        val file = (Updates.state.value as UpdateStates.State.Downloaded).file
                        file.parentFile.canonicalFile shouldBe dirs.updates.canonicalFile
                        file.readBytes().contentEquals(installer) shouldBe true
                        server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 2
                        waitFor(hasText(localized(MR.strings.action_install)))
                        shot("9-update-downloaded")
                        onAllNodes(hasText(localized(MR.strings.action_install)) and hasClickAction()).onFirst().performClick()
                        waitOrDump("for the installer to be handed over") { recorder.installed.isNotEmpty() }
                        // The downloaded, checked file and nothing else: no process started, no exit
                        recorder.installed.toList() shouldBe listOf(file)
                        recorder.other shouldBe emptyList()
                        toasts.none { it.contains(localized(DMR.strings.could_not_open_the_installer)) } shouldBe true
                    } finally {
                        SystemEffects.current = SystemEffects.Native
                        Updates.checkerForTests = null
                        Updates.releaseSignatureForTests = null
                        Updates.includePrereleases().set(before)
                        releases.close()
                    }
                }
            }
        }
    }

    /** The dialog's checkboxes, which are the last [count] toggleable nodes on screen, and the [index]th of them. */
    private fun ComposeUiTest.dialogBoxes(count: Int, index: Int): SemanticsNodeInteraction {
        val boxes = onAllNodes(isToggleable())
        return boxes[boxes.fetchSemanticsNodes().size - count + index]
    }

    /**
     * The system's side in place of [SystemEffects.Native]: links and installers are recorded,
     * nothing opens, starts or exits. Anything else the steps do not expect goes to [other].
     */
    private class RecordingEffects : SystemEffects {
        val browsed = ConcurrentLinkedQueue<URI>()
        val installed = ConcurrentLinkedQueue<File>()
        val other = ConcurrentLinkedQueue<String>()

        override fun browse(uri: URI) {
            browsed += uri
        }

        override fun openFolder(folder: File) {
            other += "folder:$folder"
        }

        override fun pickFile(request: FileRequest): File? = null.also { other += "pickFile" }

        override fun pickFolder(window: Window?, title: String, start: File): File? = null.also { other += "pickFolder" }

        override fun copy(contents: Transferable) {
            other += "copy"
        }

        override fun installUpdate(file: File, quit: () -> Unit) {
            installed += file
        }

        override fun exit(code: Int) {
            other += "exit:$code"
        }
    }

    private companion object {
        /** Japanese has two boxes (no "original"), the 16 other languages three each. */
        const val LANGUAGE_BOXES = 2 + 16 * 3
        const val CATEGORY_BOXES = 10
        const val HIDDEN_SHELF = "Hidden From Discord"
        const val GITHUB_TOKEN = "ghp_journeyFixtureToken0123456789"
    }
}

private operator fun ByteArray.contains(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
