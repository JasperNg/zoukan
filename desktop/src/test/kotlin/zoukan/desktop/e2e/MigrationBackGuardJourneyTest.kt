package zoukan.desktop.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.migration.MigrationListScreen
import kotlin.time.Duration.Companion.minutes

/**
 * The migration list asks "stop migrating?" before it closes, and every way out must ask it:
 * Escape, Ctrl+1 and Alt+Left leave the list on top with the question showing, not close it and
 * lose the searches. (The mouse back button cannot be sent by the test API; ShortcutsBackGuardTest
 * covers it.)
 *
 *   ./gradlew :desktop:journeyTest --tests "*MigrationBackGuardJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MigrationBackGuardJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun everyWayOutOfTheMigrationListAsksFirst() {
        journey = Journey.start("migration-back-guard")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MigrationListScreen(listOf(manga.id), null)) }
                waitFor(hasText(FixtureCatalog.FORMATS_TITLE))

                val exitTitle = localized(MR.strings.migrationListScreen_exitDialogTitle)
                val ways = listOf<Pair<String, ComposeUiTest.() -> Unit>>(
                    "Escape" to { root().performKeyInput { pressKey(Key.Escape) } },
                    "Ctrl+1" to { chord(Key.One) },
                    "Alt+Left" to { chord(Key.DirectionLeft, listOf(Key.AltLeft)) },
                )
                ways.forEach { (name, press) ->
                    step("$name asks before it leaves the migration list") {
                        press()
                        waitOrDump("for the exit question after $name") {
                            onAllNodesWithText(exitTitle).fetchSemanticsNodes().isNotEmpty()
                        }
                        (navigator?.lastItem is MigrationListScreen) shouldBe true
                        shot("exit-question-${name.lowercase().replace("+", "-")}")
                        onAllNodes(hasText(localized(MR.strings.action_cancel)) and hasAnyAncestor(isDialog())).onFirst().performClick()
                        waitOrDump("for the question to close") { onAllNodesWithText(exitTitle).fetchSemanticsNodes().isEmpty() }
                    }
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
}
