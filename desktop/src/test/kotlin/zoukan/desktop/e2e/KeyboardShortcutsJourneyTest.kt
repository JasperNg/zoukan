package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.i18n.desktop.DMR
import kotlin.time.Duration.Companion.minutes

/**
 * Every global keyboard shortcut, pressed on the window content with nothing clicked first:
 * Ctrl+1 to 5 open the tabs, Ctrl+comma Settings, Ctrl+Shift+U a library update, Ctrl+A selects
 * the library, Escape clears the selection and then goes back, Ctrl+F focuses the search box,
 * Alt+Left goes back, Escape in the reader pops once, and none of it works behind the app lock.
 *
 *   ./gradlew :desktop:journeyTest --tests "*KeyboardShortcutsJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardShortcutsJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun everyGlobalShortcutWorks() {
        journey = Journey.start("shortcuts")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        journey.addToLibrary("Fixture Second")

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                fun libraryShown() = onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2
                val tabs = listOf(
                    Key.Two to localized(DMR.strings.new_chapters_in_your_library_from),
                    Key.Three to localized(DMR.strings.search_history),
                    Key.Four to "Sources",
                    Key.Five to "Settings",
                )

                step("Ctrl+1 to 5 open the five tabs, with nothing clicked first") {
                    waitOrDump("for the library") { libraryShown() }
                    tabs.forEach { (key, text) ->
                        chord(key)
                        waitFor(hasText(text))
                    }
                    chord(Key.One)
                    waitOrDump("for the library again") { libraryShown() }
                    shot("1-library")
                }

                step("Ctrl+comma opens Settings, Escape goes back") {
                    chord(Key.Comma)
                    waitFor(hasText("Search settings"))
                    shot("2-settings")
                    root().performKeyInput { pressKey(Key.Escape) }
                    waitOrDump("for the library after Escape") { libraryShown() }
                }

                step("Ctrl+Shift+U starts a library update") {
                    chord(Key.U, listOf(Key.CtrlLeft, Key.ShiftLeft))
                    waitOrDump("for the update message") { toasts.contains(localized(DMR.strings.updating_the_library)) }
                }

                step("Ctrl+A selects the library, Escape clears the selection and stays on the library") {
                    chord(Key.A)
                    waitFor(hasText(localized(DMR.strings.selected, 2)))
                    shot("3-selected")
                    root().performKeyInput { pressKey(Key.Escape) }
                    waitOrDump("for the selection to clear") {
                        onAllNodesWithText(localized(DMR.strings.selected, 2)).fetchSemanticsNodes().isEmpty()
                    }
                    // Escape went to the screen, not back: still the library
                    libraryShown() shouldBe true
                }

                step("Ctrl+F focuses the library search: typing narrows the list") {
                    chord(Key.F)
                    waitOrDump("for the search box to take the focus") { onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
                    onAllNodes(isFocused()).onFirst().performTextInput("Second")
                    waitOrDump("for the list to narrow") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    shot("4-search")
                    onAllNodes(isFocused()).onFirst().performTextClearance()
                    waitOrDump("for the whole library") { libraryShown() }
                }

                step("Alt+Left goes back from an entry") {
                    onAllNodesWithTag("manga-item").onFirst().performClick()
                    waitFor(hasText("Start") or hasText("Resume"))
                    chord(Key.DirectionLeft, listOf(Key.AltLeft))
                    waitOrDump("for the library after Alt+Left") { libraryShown() }
                }

                step("Escape in the reader pops once: back on the entry, not the library") {
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    waitFor(hasText("Start"))
                    onAllNodesWithText("Start").onFirst().performClick()
                    waitFor(hasTestTag("page-image"))
                    root().performKeyInput { pressKey(Key.Escape) }
                    waitFor(hasText("Chapter 1 (JPEG)"))
                    waitForIdle()
                    // One pop: the entry is still there
                    onAllNodesWithTag("manga-item").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Chapter 1 (JPEG)").fetchSemanticsNodes().isNotEmpty() shouldBe true
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitOrDump("for the library") { libraryShown() }
                }

                step("Behind the app lock no shortcut works; after unlocking the tab is unchanged") {
                    val lock = Injekt.get<AppLock>()
                    val security = Injekt.get<SecurityPreferences>()
                    lock.setPassword(PASSWORD)
                    security.useAuthenticator().set(true)
                    security.lockAppAfter().set(0)
                    lock.onBackground()
                    lock.onForeground()
                    waitOrDump("for the lock") { lock.locked.value }
                    chord(Key.Four)
                    waitForIdle()
                    shot("5-locked")
                    lock.locked.value shouldBe true
                    lock.unlock(PASSWORD) shouldBe true
                    waitOrDump("for the library after unlocking") { libraryShown() }
                    onAllNodesWithText("Sources").fetchSemanticsNodes().isEmpty() shouldBe true
                    security.useAuthenticator().set(false)
                }
            }
        }
    }

    /** Presses [key] with [modifiers] held, on the window content. */
    private fun ComposeUiTest.chord(key: Key, modifiers: List<Key> = listOf(Key.CtrlLeft)) = with(journey) {
        root().performKeyInput {
            modifiers.forEach { keyDown(it) }
            pressKey(key)
            modifiers.reversed().forEach { keyUp(it) }
        }
    }

    private companion object {
        const val PASSWORD = "journey-secret"
    }
}
