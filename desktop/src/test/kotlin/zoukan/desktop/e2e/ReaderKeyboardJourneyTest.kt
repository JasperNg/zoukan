package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * The reader's keys go to a focused control first: Enter on a focused menu button presses that
 * button instead of hiding the menu, and the page area tells a screen reader which page it shows.
 *
 *   ./gradlew :desktop:journeyTest --tests "*ReaderKeyboardJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class ReaderKeyboardJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    // One journey for both checks: a second journey in the same JVM cannot reopen the database
    @Test
    fun `a focused menu button gets Enter and the menu stays open`() = readFirstChapter("reader-keyboard") {
        val settings = localized(DMR.strings.settings_s)
        with(journey) {
            // A page has a description with its number
            val described = runCatching {
                waitOrDump("for the page description", 10_000) {
                    onAllNodes(hasContentDescription("1 / ", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            root().performKeyInput { pressKey(Key.M) }
            waitFor(hasContentDescription(settings))
            onNode(hasContentDescription(settings)).performSemanticsAction(SemanticsActions.RequestFocus)
            waitForIdle()
            onNode(hasContentDescription(settings)).performKeyInput { pressKey(Key.Enter) }
            // The settings dialog opened, and Enter did not hide the menu on the way
            waitFor(hasText(localized(MR.strings.pref_category_general)), 10_000)
            onAllNodes(hasContentDescription(settings)).fetchSemanticsNodes().isNotEmpty() shouldBe true
            described.getOrThrow()
        }
    }

    private fun readFirstChapter(name: String, body: ComposeUiTest.() -> Unit) {
        journey = Journey.start(name)
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val chapter1 = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.first { it.name == "Chapter 1 (JPEG)" }
        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(ReaderScreen(manga.id, chapter1.id)) }
                waitOrDump("for the first page", 30_000) { onAllNodesWithTag("page-image").fetchSemanticsNodes().isNotEmpty() }
                body()
            }
        }
    }
}
