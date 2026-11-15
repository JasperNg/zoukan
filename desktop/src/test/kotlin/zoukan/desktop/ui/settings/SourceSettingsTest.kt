package zoukan.desktop.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.preference.SwitchPreferenceCompat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/** A source's own settings, drawn from the androidx.preference objects the extension builds. */
@OptIn(ExperimentalTestApi::class)
class SourceSettingsTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `a switch shows its new state after each click`() {
        val property = "$CONFIG_PREFIX.server.rootDir"
        val previous = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
        try {
            switchRedraws()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun switchRedraws() = runDesktopComposeUiTest {
        val prefs = JavaSharedPreferences("source_switch_test")
        // As Atsumaru's adult mode switch: summaries for on and off, and no default value of its own
        val pref = SwitchPreferenceCompat(null).apply {
            key = "pref_18_mode"
            title = "Toggle adult mode"
            summaryOff = "Safe (default)"
            summaryOn = "+18"
            sharedPreferences = prefs
        }
        setContent {
            // As SourceSettingsScreen: the preference values are not Compose state
            var version by remember { mutableIntStateOf(0) }
            PreferenceItem(pref, version, onChanged = { version++ }, onOpen = {})
        }
        val switch = onNode(isToggleable(), useUnmergedTree = true)

        switch.assertIsOff()
        onNodeWithText("Safe (default)").assertExists()

        onNodeWithText("Toggle adult mode").performClick()
        switch.assertIsOn()
        onNodeWithText("+18").assertExists()
        prefs.getBoolean("pref_18_mode", false) shouldBe true

        // Off again: the click must see the new state, not the one first drawn
        onNodeWithText("Toggle adult mode").performClick()
        switch.assertIsOff()
        onNodeWithText("Safe (default)").assertExists()
        prefs.getBoolean("pref_18_mode", true) shouldBe false
    }
}
