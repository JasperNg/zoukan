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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.ui.common.Toasts
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

    @Test
    fun `a change listener or a summary that throws shows an error instead of crashing`() {
        val property = "$CONFIG_PREFIX.server.rootDir"
        val previous = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
        try {
            throwingExtension()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun throwingExtension() = runDesktopComposeUiTest {
        val prefs = JavaSharedPreferences("source_throwing_test_${System.nanoTime()}")
        // An extension whose summary and change listener throw: an uncaught exception in the
        // window would end the app
        val pref = object : SwitchPreferenceCompat(null) {
            override fun getSummary(): CharSequence = throw IllegalStateException("summary bug")
        }.apply {
            key = "broken"
            title = "Broken switch"
            sharedPreferences = prefs
            setOnPreferenceChangeListener { _, _ -> throw IllegalStateException("listener bug") }
        }
        val toasts = mutableListOf<String>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
        try {
            setContent {
                var version by remember { mutableIntStateOf(0) }
                PreferenceItem(pref, version, onChanged = { version++ }, onOpen = {})
            }
            onNodeWithText("Broken switch").assertExists()
            onNodeWithText("Broken switch").performClick()
            waitForIdle()

            onNode(isToggleable(), useUnmergedTree = true).assertIsOff()
            prefs.getBoolean("broken", false) shouldBe false
            toasts.any { "listener bug" in it } shouldBe true
            toasts.any { "summary bug" in it } shouldBe true
        } finally {
            collector.cancel()
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
