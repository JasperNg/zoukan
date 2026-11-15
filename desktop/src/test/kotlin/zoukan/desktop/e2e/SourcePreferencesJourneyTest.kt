package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.e2eEffectContext
import kotlin.time.Duration.Companion.minutes

/**
 * Changes a source's own settings from Browse, as a reader does to tune an extension: turns a
 * switch on and picks from a list, checks the source acts on them (its catalogue changes), that
 * they are written to the source's settings file and that they show again when the screen
 * reopens.
 *
 *   ./gradlew :desktop:journeyTest --tests "*SourcePreferencesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class SourcePreferencesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun changeSourceSettingsAndSeeThemPersist() {
        journey = Journey.start("source-prefs")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val switch = hasText(FixtureSource.SHOW_BONUS_TITLE)
        // The row's switch is its own node, inside the row with the title
        val switchOn = isOn() and hasAnyAncestor(switch)
        val switchOff = isOff() and hasAnyAncestor(switch)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Open the source's settings from Browse") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodes(hasContentDescription("Source settings")).onFirst().performScrollTo().performClick()
                    waitFor(switchOff)
                    waitFor(hasText(FixtureSource.ORDER_A_TO_Z))
                    shot("1-defaults")
                }

                step("Turn on the bonus title and order Z to A") {
                    onAllNodes(switch).onFirst().performClick()
                    waitFor(switchOn)
                    onAllNodesWithText(FixtureSource.ORDER_TITLE).onFirst().performClick()
                    waitFor(hasText(FixtureSource.ORDER_Z_TO_A))
                    onAllNodesWithText(FixtureSource.ORDER_Z_TO_A).onFirst().performClick()
                    // The dialog closes and the summary names the new choice
                    waitOrDump("for the summary to read Z to A") {
                        onAllNodesWithText(FixtureSource.ORDER_A_TO_Z).fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText(FixtureSource.ORDER_Z_TO_A).fetchSemanticsNodes().size == 1
                    }
                    shot("2-changed")
                }

                step("The settings are saved to the source's settings file") {
                    val prefs = source.getSourcePreferences()
                    prefs.getBoolean(FixtureSource.SHOW_BONUS, false) shouldBe true
                    prefs.getString(FixtureSource.ORDER, null) shouldBe "za"
                    // Read the file as the next start of the app would: decrypted, then parsed. The
                    // app saves in the background, so wait for the last change to reach it.
                    val cipher = FileCipher.fromStore(Injekt.get<SecretStore>())
                    fun saved(): java.util.Properties? = runCatching {
                        val stored = sourcePreferencesFile.readBytes()
                        val plain = if (cipher.isEncoded(stored)) cipher.decode(stored) else stored
                        java.util.Properties().apply { plain.inputStream().use { loadFromXML(it) } }
                    }.getOrNull()
                    waitOrDump("for both settings in ${sourcePreferencesFile.name}") {
                        saved()?.let { it.getProperty(FixtureSource.SHOW_BONUS) == "true" && it.getProperty(FixtureSource.ORDER) == "za" } == true
                    }
                }

                step("The source lists the bonus title, Z to A") {
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for four titles") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 4 }
                    // Z to A: Fixture Third comes before Fixture Formats in the grid
                    val third = itemPosition("Fixture Third")
                    val formats = itemPosition(FixtureCatalog.FORMATS_TITLE)
                    (third < formats) shouldBe true
                    waitFor(hasText(FixtureCatalog.BONUS_TITLE))
                    shot("3-catalogue")
                }

                step("Reopening the settings shows the saved values") {
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodes(hasContentDescription("Source settings")).onFirst().performScrollTo().performClick()
                    waitFor(switchOn)
                    waitFor(hasText(FixtureSource.ORDER_Z_TO_A))
                    shot("4-reopened")
                }

                step("Turning the switch off again hides the bonus title") {
                    onAllNodes(switch).onFirst().performClick()
                    waitFor(switchOff)
                    source.getSourcePreferences().getBoolean(FixtureSource.SHOW_BONUS, true) shouldBe false
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for three titles") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                }
            }
        }
    }

    /** Where a title sits in the grid, in reading order: row, then column. */
    private fun androidx.compose.ui.test.ComposeUiTest.itemPosition(title: String): Float {
        val node = onAllNodes(hasTestTag("manga-item") and hasAnyDescendant(hasText(title)))
            .fetchSemanticsNodes().firstOrNull()
            ?: onAllNodesWithText(title).fetchSemanticsNodes().first()
        return node.boundsInRoot.top * 10_000 + node.boundsInRoot.left
    }
}
