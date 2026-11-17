package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.source.service.SourcePreferences
import exh.source.LOCAL_SOURCE_PACKAGE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import zoukan.desktop.security.Incognito
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * Global incognito mode (More > Incognito mode): a chapter read with it on leaves no history and
 * no read progress; with it off again the same reading is recorded. Then per-extension incognito
 * (Settings > Security > Incognito extensions): the Local source picked there reads without
 * history while the global switch stays off. An installed extension's package is matched to its
 * sources in IncognitoTest.
 *
 *   ./gradlew :desktop:journeyTest --tests "*IncognitoJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class IncognitoJourneyTest {

    private lateinit var journey: Journey

    private companion object {
        const val LOCAL_TITLE = "Delta"
    }

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun incognitoReadingLeavesNoTrace() {
        journey = Journey.start("incognito")
        val preferences = Injekt.get<DesktopPreferences>()
        val incognito = Injekt.get<Incognito>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        // A local entry, for the per-extension incognito of the Local source
        LocalFixtures.localEntry(Injekt.get<ZoukanStorage>(), LOCAL_TITLE, chapters = 1)
        val local = LocalFixtures.addLocalToLibrary().single { it.title == LOCAL_TITLE }
        fun chapters() = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }
        fun history() = runBlocking { Injekt.get<GetHistory>().subscribe("", null, null, null).first() }
        val title = localized(MR.strings.pref_incognito_mode)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                fun home() = runOnUiThread { while (navigator?.canPop == true) navigator?.pop() }
                fun titleNodes() = onAllNodesWithText(title).fetchSemanticsNodes().size

                step("Turn incognito on: the banner shows") {
                    onAllNodesWithText(localized(MR.strings.label_more)).onFirst().performClick()
                    // Before the switch the words are the switch's own title only
                    waitOrDump("for the More tab") { titleNodes() == 1 }
                    onAllNodesWithText(title).onFirst().performClick()
                    waitOrDump("for the incognito banner") { titleNodes() == 2 }
                    incognito.mode().get() shouldBe true
                    shot("1-on")
                }

                step("Read chapter 1 to the end") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodesWithText("Start").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("jpeg"), "2-incognito")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText("In library"))
                }

                step("No history, no progress, and History shows nothing") {
                    history().shouldBeEmpty()
                    chapters().count { it.read } shouldBe 0
                    chapters().all { it.lastPageRead == 0L } shouldBe true
                    home()
                    onAllNodesWithText(localized(MR.strings.history)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.nothing_read_yet)))
                    shot("3-empty-history")
                }

                step("Turn incognito off: the banner goes") {
                    onAllNodesWithText(localized(MR.strings.label_more)).onFirst().performClick()
                    waitOrDump("for the incognito banner") { titleNodes() == 2 }
                    // The banner above is the first match, the switch's own title the last
                    onAllNodesWithText(title).onLast().performClick()
                    waitOrDump("for the banner to go") { titleNodes() == 1 }
                    incognito.mode().get() shouldBe false
                }

                step("The same reading is recorded now") {
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Chapter 2 (PNG)"))
                    onAllNodesWithText("Chapter 2 (PNG)").onFirst().performClick()
                    readThrough(FixtureCatalog.pages("png"), "4-normal")
                    runOnUiThread { navigator?.pop() }
                    waitOrDump("for the history of chapter 2") { history().isNotEmpty() }
                    val second = chapters()[1]
                    history().map { it.chapterId } shouldContainExactly listOf(second.id)
                    waitOrDump("for chapter 2 to be read") { chapters()[1].read }
                    // Chapter 1, read in incognito, stays untouched
                    chapters()[0].read shouldBe false
                    home()
                    onAllNodesWithText(localized(MR.strings.history)).onFirst().performClick()
                    waitFor(hasText(FixtureCatalog.FORMATS_TITLE))
                    shot("5-history")
                }

                step("Per extension: the Local source set incognito in Security keeps no history; the global switch stays off") {
                    val recorded = history().map { it.chapterId }
                    runOnUiThread { HomeScreen.openSettings(navigator!!) }
                    val list = settingsPage(SettingsCategory.Security)
                    val row = localized(DMR.strings.incognito_extensions)
                    list.performScrollToNode(hasText(row))
                    onAllNodesWithText(row).onFirst().performClick()
                    waitFor(hasText(localized(MR.strings.action_display_local_badge)))
                    // The dialog lists the Local source, then E-Hentai (no extension is installed):
                    // its boxes are the last two toggleable nodes
                    val boxes = onAllNodes(isToggleable())
                    boxes[boxes.fetchSemanticsNodes().size - 2].performClick()
                    waitOrDump("for the Local source to be incognito") {
                        LOCAL_SOURCE_PACKAGE in Injekt.get<SourcePreferences>().incognitoExtensions().get()
                    }
                    shot("6-incognito-extensions")
                    onAllNodesWithText(localized(DMR.strings.done)).onFirst().performClick()
                    Injekt.get<SourcePreferences>().incognitoExtensions().get() shouldBe setOf(LOCAL_SOURCE_PACKAGE)
                    incognito.mode().get() shouldBe false
                    incognito.isOn(LocalSource.ID) shouldBe true
                    incognito.isOn(manga.source) shouldBe false
                    home()

                    runOnUiThread { navigator?.push(MangaScreen(local.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodesWithText("Start").onFirst().performClick()
                    readThrough(LocalFixtures.pages("$LOCAL_TITLE 1", 4), "7-local-incognito")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText("In library"))
                    history().map { it.chapterId } shouldContainExactly recorded
                    val localChapters = runBlocking { Injekt.get<GetChaptersByMangaId>().await(local.id) }
                    localChapters.none { it.read } shouldBe true
                    localChapters.all { it.lastPageRead == 0L } shouldBe true
                }
            }
        }
    }
}
