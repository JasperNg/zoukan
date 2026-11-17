package zoukan.desktop.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

/**
 * Changes a source's own settings from Browse, as a reader does to tune an extension: turns a
 * switch on and picks from a list, checks the source acts on them (its catalogue changes), that
 * they are written to the source's settings file and that they show again when the screen
 * reopens.
 *
 * Then the kinds of source settings extensions build beyond a switch and a list, the way
 * extensions use them: a multi-select (hidden genres) and an edit text (a title prefix) the source
 * acts on in its catalogue, a switch whose change listener throws, and a list whose listener reads
 * the stored value, stores another one with setValueIndex and shows an Android toast. Checks the
 * app survives the throwing listener with an error toast (and stores nothing), that the
 * extension's toast shows, that the values reach the source's settings file and show again when
 * the screen reopens, that the source's own OnSharedPreferenceChangeListener heard each change, and
 * that the catalogue leaves out the hidden genres and carries the prefix.
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
        // The row is the switch: one node with the title and the on/off state
        val switchOn = isOn() and switch
        val switchOff = isOff() and switch
        val experimentalOff = isOff() and hasText(FixtureSource.EXPERIMENTAL_TITLE)
        val failureToast = "${FixtureSource.EXPERIMENTAL_TITLE}: ${FixtureSource.EXPERIMENTAL_FAILURE}"
        val autoToast = FixtureSource.qualityToast("${FixtureSource.QUALITY_AUTO} chose ${FixtureSource.QUALITY_HIGH}", FixtureSource.QUALITY_LOW)
        val lowToast = FixtureSource.qualityToast(FixtureSource.QUALITY_LOW, FixtureSource.QUALITY_HIGH)

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
                    fun saved(): Properties? = runCatching {
                        val stored = sourcePreferencesFile.readBytes()
                        val plain = if (cipher.isEncoded(stored)) cipher.decode(stored) else stored
                        Properties().apply { plain.inputStream().use { loadFromXML(it) } }
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

                step("Reopen the source's settings: the other kinds of settings are at their defaults") {
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodes(hasContentDescription("Source settings")).onFirst().performScrollTo().performClick()
                    waitFor(hasText(FixtureSource.QUALITY_TITLE))
                    waitFor(experimentalOff)
                    // The list's summary names the default
                    waitFor(hasText(FixtureSource.QUALITY_LOW))
                    shot("5-other-defaults")
                }

                step("Hide two genres in the multi-select") {
                    onAllNodesWithText(FixtureSource.HIDDEN_GENRES_TITLE).onFirst().performScrollTo().performClick()
                    waitFor(hasText(FixtureSource.GENRE_DRAMA))
                    onAllNodesWithText(FixtureSource.GENRE_COMEDY).onFirst().performClick()
                    onAllNodesWithText(FixtureSource.GENRE_DRAMA).onFirst().performClick()
                    shot("6-genres-dialog")
                    onAllNodesWithText("Save").onFirst().performClick()
                    // The dialog closes and the summary names both, in any order
                    waitOrDump("for the summary to name Comedy and Drama") {
                        onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() &&
                            texts().any { FixtureSource.GENRE_COMEDY in it && FixtureSource.GENRE_DRAMA in it }
                    }
                }

                step("Type a title prefix in the edit text") {
                    onAllNodesWithText(FixtureSource.TITLE_PREFIX_TITLE).onFirst().performScrollTo().performClick()
                    waitFor(hasSetTextAction())
                    onAllNodes(hasSetTextAction()).onFirst().performTextInput(PREFIX)
                    onAllNodesWithText("Save").onFirst().performClick()
                    waitOrDump("for the summary to show the prefix") {
                        onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() && onAllNodesWithText(PREFIX).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("7-genres-and-prefix")
                }

                step("Turn on the switch whose listener throws: an error toast, the app goes on") {
                    onAllNodesWithText(FixtureSource.EXPERIMENTAL_TITLE).onFirst().performScrollTo().performClick()
                    waitOrDump("for the error toast") { failureToast in toasts }
                    waitFor(hasText(failureToast, substring = true))
                    shot("8-listener-failed")
                    // The listener refused the change by failing: nothing is stored, the switch stays off
                    waitFor(experimentalOff)
                    source.getSourcePreferences().contains(FixtureSource.EXPERIMENTAL) shouldBe false
                }

                step("Pick Auto in the list: its listener stores High instead and shows a toast") {
                    onAllNodesWithText(FixtureSource.QUALITY_TITLE).onFirst().performScrollTo().performClick()
                    waitFor(hasText(FixtureSource.QUALITY_AUTO))
                    onAllNodesWithText(FixtureSource.QUALITY_AUTO).onFirst().performClick()
                    waitOrDump("for the extension's toast") { autoToast in toasts }
                    waitFor(hasText(autoToast, substring = true))
                    waitOrDump("for the summary to read High") {
                        onAllNodesWithText(FixtureSource.QUALITY_AUTO).fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText(FixtureSource.QUALITY_HIGH).fetchSemanticsNodes().isNotEmpty()
                    }
                    source.getSourcePreferences().getString(FixtureSource.QUALITY, null) shouldBe "high"
                    shot("9-quality-auto")
                }

                step("Pick Low: the listener reads the High that setValueIndex stored") {
                    onAllNodesWithText(FixtureSource.QUALITY_TITLE).onFirst().performScrollTo().performClick()
                    waitFor(hasText(FixtureSource.QUALITY_LOW))
                    onAllNodesWithText(FixtureSource.QUALITY_LOW).onFirst().performClick()
                    waitOrDump("for the extension's toast") { lowToast in toasts }
                    waitOrDump("for the summary to read Low") {
                        onAllNodesWithText(FixtureSource.QUALITY_AUTO).fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText(FixtureSource.QUALITY_LOW).fetchSemanticsNodes().isNotEmpty()
                    }
                    shot("10-quality-low")
                }

                step("The source's settings listener heard every stored change") {
                    waitOrDump("for the listener to hear genres, prefix and quality") {
                        source.changedKeys.containsAll(listOf(FixtureSource.HIDDEN_GENRES, FixtureSource.TITLE_PREFIX, FixtureSource.QUALITY))
                    }
                    println("Journey $name: the source's listener heard ${source.changedKeys}")
                    source.changedKeys shouldNotContain FixtureSource.EXPERIMENTAL
                    // High through setValueIndex, then Low through the dialog
                    source.changedKeys.count { it == FixtureSource.QUALITY } shouldBe 2
                }

                step("The other values are saved to the source's settings file") {
                    val prefs = source.getSourcePreferences()
                    prefs.getStringSet(FixtureSource.HIDDEN_GENRES, null)!!.toList() shouldContainAll
                        listOf(FixtureCatalog.GENRE_COMEDY, FixtureCatalog.GENRE_DRAMA)
                    prefs.getStringSet(FixtureSource.HIDDEN_GENRES, null)!!.size shouldBe 2
                    prefs.getString(FixtureSource.TITLE_PREFIX, null) shouldBe PREFIX
                    prefs.getString(FixtureSource.QUALITY, null) shouldBe "low"
                    val cipher = FileCipher.fromStore(Injekt.get<SecretStore>())
                    fun saved(): Properties? = runCatching {
                        val stored = sourcePreferencesFile.readBytes()
                        val plain = if (cipher.isEncoded(stored)) cipher.decode(stored) else stored
                        Properties().apply { plain.inputStream().use { loadFromXML(it) } }
                    }.getOrNull()
                    waitOrDump("for the settings in ${sourcePreferencesFile.name}") {
                        saved()?.let {
                            it.getProperty(FixtureSource.TITLE_PREFIX) == PREFIX &&
                                it.getProperty(FixtureSource.QUALITY) == "low" &&
                                storedSet(it, FixtureSource.HIDDEN_GENRES) == setOf(FixtureCatalog.GENRE_COMEDY, FixtureCatalog.GENRE_DRAMA)
                        } == true
                    }
                    saved()!!.keys.map { it.toString() }.filter { FixtureSource.EXPERIMENTAL in it } shouldBe emptyList()
                }

                step("Reopening the settings shows the other saved values") {
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodes(hasContentDescription("Source settings")).onFirst().performScrollTo().performClick()
                    waitFor(hasText(FixtureSource.QUALITY_TITLE))
                    waitFor(hasText(PREFIX))
                    waitFor(hasText(FixtureSource.QUALITY_LOW))
                    waitFor(experimentalOff)
                    waitOrDump("for the genres summary") {
                        texts().any { FixtureSource.GENRE_COMEDY in it && FixtureSource.GENRE_DRAMA in it }
                    }
                    shot("11-reopened-others")
                }

                step("The catalogue leaves out the hidden genres and carries the prefix") {
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    // Fixture Second is drama and Fixture Third comedy (the bonus title is off): only Fixture Formats stays
                    waitOrDump("for one title") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 1 }
                    waitFor(hasText(PREFIX + FixtureCatalog.FORMATS_TITLE, substring = true))
                    onAllNodes(hasTestTag("manga-item") and hasText("Fixture Second", substring = true), useUnmergedTree = true)
                        .fetchSemanticsNodes().size shouldBe 0
                    shot("12-catalogue-filtered")
                }
            }
        }
    }

    /** A string set as the settings file stores it: key.0, key.1 and so on, and key.size. */
    private fun storedSet(properties: Properties, key: String): Set<String> {
        val size = properties.getProperty("$key.size")?.toIntOrNull() ?: return emptySet()
        return (0 until size).mapNotNull { properties.getProperty("$key.$it") }.toSet()
    }

    /** Every text on screen. */
    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** Where a title sits in the grid, in reading order: row, then column. */
    private fun ComposeUiTest.itemPosition(title: String): Float {
        val node = onAllNodes(hasTestTag("manga-item") and hasAnyDescendant(hasText(title)))
            .fetchSemanticsNodes().firstOrNull()
            ?: onAllNodesWithText(title).fetchSemanticsNodes().first()
        return node.boundsInRoot.top * 10_000 + node.boundsInRoot.left
    }

    private companion object {
        const val PREFIX = "Hot "
    }
}
