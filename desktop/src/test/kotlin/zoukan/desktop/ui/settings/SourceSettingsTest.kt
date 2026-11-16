package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR
import java.io.File

/** A source's own settings, drawn from the androidx.preference objects the extension builds. */
@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class SourceSettingsTest {

    @TempDir
    lateinit var root: File

    @AfterEach
    fun waitForWriters() {
        // A click saves with apply(), which writes in the background: let those writers finish
        // before JUnit deletes root
        JavaSharedPreferences.awaitPendingSaves()
    }

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

    private fun textPreference(key: String, title: String, value: String): EditTextPreference {
        val prefs = JavaSharedPreferences("source_text_test_${System.nanoTime()}")
        prefs.edit().putString(key, value).apply()
        return EditTextPreference(null).apply {
            this.key = key
            this.title = title
            sharedPreferences = prefs
        }
    }

    @Test
    fun `a text setting does not show its stored value as its summary`() = withRoot {
        runDesktopComposeUiTest {
            val pref = textPreference("api_key", "API key", "s3cr3t-value")
            setContent { PreferenceItem(pref, 0, onChanged = {}, onOpen = {}) }

            onNodeWithText("API key").assertExists()
            onAllNodesWithText("s3cr3t-value", substring = true).fetchSemanticsNodes().isEmpty() shouldBe true
        }
    }

    @Test
    fun `a password-like text setting is masked in its edit dialog`() = withRoot {
        runDesktopComposeUiTest {
            val pref = textPreference("password", "Password", "s3cr3t-value")
            setContent { PreferenceDialog(pref, onDismiss = {}, onSave = {}) }

            // A masked field is marked as a password; its semantics text is the typed value, so the
            // mark is what the test can check
            onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

            // The eye button reveals it
            onNodeWithContentDescription(localized(DMR.strings.show_value)).performClick()
            waitForIdle()
            onNodeWithContentDescription(localized(DMR.strings.hide_value)).assertExists()
        }
    }

    @Test
    fun `a text setting is masked when its key or title names a secret`() {
        looksSecret("api_key", null) shouldBe true
        looksSecret(null, "Password") shouldBe true
        looksSecret("pref_cookie", "Session") shouldBe true
        looksSecret("server_url", "Server address") shouldBe false
        looksSecret(null, null) shouldBe false
    }

    private fun withRoot(block: () -> Unit) {
        val property = "$CONFIG_PREFIX.server.rootDir"
        val previous = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    /** A screen as an extension builds one with a category, a dependency and a seek bar. */
    private fun categorisedScreen(): PreferenceScreen {
        val screen = PreferenceScreen(null).apply { sharedPreferences = JavaSharedPreferences("source_category_test_${System.nanoTime()}") }
        val login = SwitchPreferenceCompat(null).apply {
            key = "login"
            title = "Use login"
            setDefaultValue(false)
        }
        screen.addPreference(login)
        val category = PreferenceCategory(null).apply { title = "Account" }
        screen.addPreference(category)
        category.addPreference(
            EditTextPreference(null).apply {
                key = "user"
                title = "Username"
                dependency = "login"
            },
        )
        category.addPreference(
            SeekBarPreference(null).apply {
                key = "pages"
                title = "Pages to preload"
                min = 1
                max = 5
                setDefaultValue(2)
                showSeekBarValue = true
            },
        )
        category.addPreference(
            SwitchPreferenceCompat(null).apply {
                key = "hidden"
                title = "Hidden"
                isVisible = false
            },
        )
        return screen
    }

    @Test
    fun `a category is flattened into its title row followed by its preferences`() = withRoot {
        val rows = flattenPreferences(categorisedScreen())
        rows.map { it.title?.toString() } shouldBe listOf("Use login", "Account", "Username", "Pages to preload")
        rows[1].shouldBeInstanceOf<PreferenceCategory>()
        snapSeekBar(2.6f, 1, 5, 1) shouldBe 3
        snapSeekBar(9f, 0, 10, 4) shouldBe 8
        snapSeekBar(-3f, 1, 5, 1) shouldBe 1
    }

    @Test
    fun `categories, seek bars and dependencies draw as on Android`() = withRoot {
        runDesktopComposeUiTest {
            val screen = categorisedScreen()
            setContent {
                var version by remember { mutableIntStateOf(0) }
                Column {
                    flattenPreferences(screen).forEach { pref ->
                        PreferenceItem(pref, version, onChanged = { version++ }, onOpen = {})
                    }
                }
            }
            // The category's title is a header
            onNodeWithText("Account").assertExists()
            // The seek bar is a slider showing its value
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().size shouldBe 1
            onNodeWithText("2").assertExists()

            // The username depends on the login switch, which is off
            val user = screen.findPreference<EditTextPreference>("user")!!
            user.isEnabled shouldBe false
            onNodeWithText("Use login").performClick()
            waitForIdle()
            user.isEnabled shouldBe true
            screen.sharedPreferences.getBoolean("login", false) shouldBe true
        }
    }
}
