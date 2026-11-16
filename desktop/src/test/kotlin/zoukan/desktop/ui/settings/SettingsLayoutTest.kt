package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/** Settings keeps its page readable in the smallest window. */
@OptIn(ExperimentalTestApi::class)
class SettingsLayoutTest {

    // The smallest window is 800dp wide; the navigation rail takes about 80dp of it
    private val smallest = 720.dp

    @Test
    fun `at the smallest window the settings page keeps most of the width`() {
        (smallest - settingsListWidth(smallest)) shouldBeGreaterThanOrEqualTo 440.dp
    }

    @Test
    fun `wide windows keep the list at its full width`() {
        settingsListWidth(1200.dp) shouldBe 450.dp
    }

    @Test
    fun `a tracker row's title stays on one line at the smallest window`() = runDesktopComposeUiTest(width = 800, height = 560) {
        val title = "MyAnimeList"
        setContent {
            Box(Modifier.width(smallest - settingsListWidth(smallest))) {
                // The shape of a tracker row: a title and two buttons
                SettingRow(title, "Not signed in") {
                    Row {
                        TextButton(onClick = {}) { Text(localized(DMR.strings.other_browser)) }
                        OutlinedButton(onClick = {}) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
                    }
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        results.single().lineCount shouldBe 1
    }
}
