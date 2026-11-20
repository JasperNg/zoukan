package zoukan.desktop.ui.settings

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.ToastSink
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.app.Toasts
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.compat.localized
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.ui.settings.MissingExtensionsInstall.State
import zoukan.extension.ExtensionException
import zoukan.extension.LoadResult
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.hide_value
import zoukan.i18n.desktop.show_value
import java.io.File
import java.util.Properties

/**
 * Source settings screen end-to-end against [SourceSettingsScreen] and [JavaSharedPreferences]:
 * list choices, multi-select sets, non-secret text edits, listener-side `setValueIndex` + Android
 * [Toast], failing change listeners, `OnSharedPreferenceChangeListener`, and XML persistence.
 */
@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class SourceSettingsScreenTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null
    private var previousSink: java.util.function.Consumer<String>? = null

    @BeforeEach
    fun setUp() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
        previousSink = ToastSink.get()
        ToastSink.set(Toasts::show)
    }

    @AfterEach
    fun tearDown() {
        ToastSink.set(previousSink)
        JavaSharedPreferences.awaitPendingSaves()
        if (previousRoot == null) System.clearProperty(property) else System.setProperty(property, previousRoot)
    }

    private class FakeConfigurableSource(
        override val id: Long,
        private val setup: (PreferenceScreen) -> Unit,
    ) : ConfigurableSource {
        override val name = "Fake Source"
        override val lang = "en"
        override val supportsLatest = false
        override fun setupPreferenceScreen(screen: PreferenceScreen) = setup(screen)
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter) = emptyList<eu.kanade.tachiyomi.source.model.Page>()
    }

    private fun registerSource(prefsKey: String, setup: (PreferenceScreen) -> Unit): JavaSharedPreferences {
        val prefs = JavaSharedPreferences(prefsKey)
        val app = mockk<Application>(relaxed = true) {
            every { getSharedPreferences(any<String>(), any<Int>()) } returns prefs
        }
        val source = FakeConfigurableSource(SOURCE_ID, setup)
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(SOURCE_ID) } returns source
        }
        Injekt.addSingleton(app)
        Injekt.addSingleton(sourceManager)
        return prefs
    }

    @Test
    fun `list, multi-select, text, setValueIndex listener, failing listener, and XML save work together`() {
        val prefsKey = "source_${SOURCE_ID}_${System.nanoTime()}"
        val prefs = registerSource(prefsKey) { screen ->
            val context = screen.context
            screen.addPreference(
                ListPreference(context).apply {
                    key = "order"
                    title = "Sort order"
                    entries = arrayOf("A to Z", "Z to A")
                    entryValues = arrayOf("az", "za")
                    setDefaultValue("az")
                    summary = "%s"
                },
            )
            screen.addPreference(
                MultiSelectListPreference(context).apply {
                    key = "hidden_genres"
                    title = "Hidden genres"
                    entries = arrayOf("Action", "Comedy", "Drama")
                    entryValues = arrayOf("action", "comedy", "drama")
                    setDefaultValue(emptySet<String>())
                },
            )
            screen.addPreference(
                EditTextPreference(context).apply {
                    key = "title_prefix"
                    title = "Title prefix"
                    setDefaultValue("")
                },
            )
            screen.addPreference(
                SwitchPreferenceCompat(context).apply {
                    key = "experimental"
                    title = "Experimental"
                    setDefaultValue(false)
                    setOnPreferenceChangeListener { _, _ -> throw IllegalStateException("refused by source") }
                },
            )
            screen.addPreference(
                ListPreference(context).apply {
                    key = "quality"
                    title = "Quality"
                    entries = arrayOf("Auto", "High", "Low")
                    entryValues = arrayOf("auto", "high", "low")
                    setDefaultValue("low")
                    summary = "%s"
                    setOnPreferenceChangeListener { _, newValue ->
                        if (newValue == "auto") {
                            setValueIndex(1)
                            Toast.makeText(context, "Auto chose High (was $value)", Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            Toast.makeText(context, "Chose $newValue (was $value)", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                },
            )
        }

        val changedKeys = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changedKeys += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        val toasts = mutableListOf<String>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
        try {
            runDesktopComposeUiTest {
                setContent { Navigator(SourceSettingsScreen(SOURCE_ID)) }

                // ListPreference
                onNodeWithText("Sort order").performClick()
                onNodeWithText("Z to A").performClick()
                waitForIdle()
                onNodeWithText("Z to A").assertExists()
                onNodeWithText("A to Z").assertDoesNotExist()
                prefs.getString("order", null) shouldBe "za"

                // MultiSelectListPreference
                onNodeWithText("Hidden genres").performClick()
                onNodeWithText("Comedy").performClick()
                onNodeWithText("Drama").performClick()
                onNodeWithText("Save").performClick()
                waitForIdle()
                prefs.getStringSet("hidden_genres", null)!!.toList() shouldContainAll listOf("comedy", "drama")
                onNodeWithText("Comedy, Drama").assertExists()

                // EditTextPreference (non-secret)
                onNodeWithText("Title prefix").performClick()
                onNode(hasSetTextAction()).performTextInput("Hot ")
                onNodeWithText("Save").performClick()
                waitForIdle()
                prefs.getString("title_prefix", null) shouldBe "Hot "
                onNodeWithText("Hot ").assertExists()

                // Throwing listener: shows error toast, stores nothing, notifies no pref listener
                onNodeWithText("Experimental").performClick()
                Await.until("experimental failure toast") { toasts.any { "Experimental: refused by source" in it } }
                prefs.contains("experimental") shouldBe false
                changedKeys shouldNotContain "experimental"

                // ListPreference listener using setValueIndex + returning false + Toast
                onNodeWithText("Quality").performClick()
                onNodeWithText("Auto").performClick()
                Await.until("auto toast") { toasts.any { "Auto chose High (was high)" in it } }
                waitForIdle()
                prefs.getString("quality", null) shouldBe "high"
                onNodeWithText("High").assertExists()

                // Next pick reads the High stored by setValueIndex
                onNodeWithText("Quality").performClick()
                onAllNodesWithText("Low").onFirst().performClick()
                Await.until("low toast") { toasts.any { "Chose low (was high)" in it } }
                prefs.getString("quality", null) shouldBe "low"

                changedKeys shouldContainAll listOf("order", "hidden_genres", "title_prefix", "quality")
                changedKeys.count { it == "quality" } shouldBe 2
            }

            // Verify settings were persisted to the preferences XML file on disk
            JavaSharedPreferences.awaitPendingSaves()
            val prefsFile = root.walkTopDown().first { it.isFile && prefsKey in it.name }
            val properties = Properties().apply { prefsFile.inputStream().use { loadFromXML(it) } }
            properties.getProperty("order") shouldBe "za"
            properties.getProperty("title_prefix") shouldBe "Hot "
            properties.getProperty("quality") shouldBe "low"
            properties.getProperty("hidden_genres.size") shouldBe "2"
            properties.keys.map { it.toString() }.filter { "experimental" in it } shouldBe emptyList()
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            collector.cancel()
        }
    }

    private companion object {
        const val SOURCE_ID = 987654L
    }
}

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
    fun `a secret setting whose summary repeats its value does not show it`() = withRoot {
        runDesktopComposeUiTest {
            // Extensions often set the summary to the value they just saved
            val pref = textPreference("api_key", "API key", "s3cr3t-value").apply { summary = "Key: s3cr3t-value" }
            val described = textPreference("token", "Token", "s3cr3t-value").apply { summary = "From your account page" }
            setContent {
                PreferenceItem(pref, 0, onChanged = {}, onOpen = {})
                PreferenceItem(described, 0, onChanged = {}, onOpen = {})
            }

            onAllNodesWithText("s3cr3t-value", substring = true).fetchSemanticsNodes().isEmpty() shouldBe true
            onNodeWithText("From your account page").assertExists()
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
        looksSecret("hidden_authors", "Authors to hide") shouldBe false
        looksSecret("auth_code", null) shouldBe true
        looksSecret(null, null) shouldBe false
    }

    @Test
    fun `a preference whose visibility throws is skipped with an error`() = withRoot {
        val prefs = JavaSharedPreferences("source_visible_test_${System.nanoTime()}")
        val screen = PreferenceScreen(null).apply { sharedPreferences = prefs }
        screen.addPreference(
            SwitchPreferenceCompat(null).apply {
                key = "fine"
                title = "Fine"
            },
        )
        screen.addPreference(
            object : SwitchPreferenceCompat(null) {
                override fun isVisible(): Boolean = throw IllegalStateException("visible bug")
            }.apply {
                key = "broken"
                title = "Broken"
            },
        )
        val toasts = mutableListOf<String>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
        try {
            flattenPreferences(screen).map { it.title?.toString() } shouldBe listOf("Fine")
            toasts.any { "visible bug" in it } shouldBe true
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a preference whose title throws still draws`() = withRoot {
        runDesktopComposeUiTest {
            val prefs = JavaSharedPreferences("source_title_test_${System.nanoTime()}")
            val pref = object : SwitchPreferenceCompat(null) {
                override fun getTitle(): CharSequence = throw IllegalStateException("title bug")
            }.apply {
                key = "untitled"
                sharedPreferences = prefs
            }
            val toasts = mutableListOf<String>()
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
            try {
                setContent { PreferenceItem(pref, 0, onChanged = {}, onOpen = {}) }
                waitForIdle()
                toasts.any { "title bug" in it } shouldBe true
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `a dialog for an unknown preference type is closed after composition`() = withRoot {
        runDesktopComposeUiTest {
            var dismissed = 0
            val pref = androidx.preference.Preference(null).apply { key = "plain" }
            setContent { PreferenceDialog(pref, onDismiss = { dismissed++ }, onSave = {}) }
            waitForIdle()
            dismissed shouldBe 1
        }
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

class MissingExtensionsTest {

    private fun store(name: String, key: String = "key-$name") = ExtensionStore(
        indexUrl = "https://$name.example/index.min.json",
        name = name,
        badgeLabel = name,
        signingKey = key,
        contact = ExtensionStore.Contact(website = "https://$name.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun available(
        pkg: String,
        vararg sourceIds: Long,
        store: ExtensionStore = store("A"),
        versionCode: Long = 1,
        nsfw: Boolean = false,
    ) = Extension.Available(
        name = pkg.substringAfterLast('.'),
        pkgName = pkg,
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "en",
        isNsfw = nsfw,
        signatureHash = store.signingKey,
        storeName = store.name,
        sources = sourceIds.map { Extension.Available.Source(it, "en", "Source $it", "https://s$it.example") },
        apkUrl = "https://example/$pkg.apk",
        iconUrl = "https://example/$pkg.png",
        store = store,
    )

    private fun untrusted(pkg: String, key: String) = Extension.Untrusted(
        name = pkg.substringAfterLast('.'),
        pkgName = pkg,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        signatureHash = key,
    )

    private fun missing(vararg ids: Long) = ids.map { MissingSource(it, "Source $it") }

    @Test
    fun `several missing sources of one extension install it once`() {
        val ext = available("ext.multi", 1, 2, 3)
        val plan = MissingExtensionsPlan.of(missing(1, 2), listOf(ext), emptyMap(), emptyMap())

        plan.toInstall.size shouldBe 1
        plan.toInstall.single().extension shouldBe ext
        plan.toInstall.single().sources.map { it.id } shouldBe listOf(1L, 2L)
        plan.needsTrust.shouldBeEmpty()
        plan.unavailable.shouldBeEmpty()
    }

    @Test
    fun `a source no store offers is listed as unavailable`() {
        val plan = MissingExtensionsPlan.of(missing(1, 99), listOf(available("ext.one", 1)), emptyMap(), emptyMap())

        plan.toInstall.map { it.extension.pkgName } shouldBe listOf("ext.one")
        plan.unavailable shouldBe listOf(MissingSource(99, "Source 99"))
    }

    @Test
    fun `an installed but untrusted extension is left for the user to trust`() {
        val ext = available("ext.unknown", 5, store = store("A", key = "k"))
        val plan = MissingExtensionsPlan.of(
            missing(5),
            listOf(ext),
            installed = emptyMap(),
            untrusted = mapOf("ext.unknown" to untrusted("ext.unknown", "k")),
        )

        plan.toInstall.shouldBeEmpty()
        plan.needsTrust.map { it.extension.pkgName } shouldBe listOf("ext.unknown")
        plan.unavailable.shouldBeEmpty()
    }

    @Test
    fun `an extension in two stores comes from the store the Browse screen lists`() {
        val first = available("ext.both", 7, store = store("First"))
        val second = available("ext.both", 7, store = store("Second"), versionCode = 2)
        val plan = MissingExtensionsPlan.of(missing(7), listOf(first, second), emptyMap(), emptyMap())

        // Not installed: the first store's, in the order the stores were added (chooseOffer)
        plan.toInstall.single().extension.store.name shouldBe "First"
    }

    @Test
    fun `an untrusted extension in two stores is matched to its signer's store`() {
        val other = available("ext.both", 7, store = store("Other", key = "x"))
        val own = available("ext.both", 7, store = store("Own", key = "k"))
        val plan = MissingExtensionsPlan.of(
            missing(7),
            listOf(other, own),
            installed = emptyMap(),
            untrusted = mapOf("ext.both" to untrusted("ext.both", "k")),
        )

        plan.needsTrust.single().extension.store.name shouldBe "Own"
    }

    @Test
    fun `one failure does not stop the other installs`(): Unit = runBlocking {
        val plan = MissingExtensionsPlan.of(
            missing(1, 2, 3, 4),
            listOf(available("ext.a", 1), available("ext.b", 2), available("ext.c", 3)),
            emptyMap(),
            emptyMap(),
        )
        val progress = mutableListOf<Int>()
        val summary = installMissingExtensions(
            plan,
            install = { ext ->
                when (ext.pkgName) {
                    "ext.a" -> throw IllegalStateException("network down")
                    "ext.b" -> LoadResult.Untrusted(untrusted("ext.b", "k"))
                    else -> LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "bad signature"))
                }
            },
            onProgress = { done, _, _ -> progress += done },
        )

        summary.installed.shouldBeEmpty()
        summary.needsTrust shouldBe listOf("b")
        summary.failed shouldBe listOf("a" to "network down", "c" to "bad signature")
        summary.unavailable shouldBe listOf("Source 4")
        progress shouldBe listOf(0, 1, 2, 3)
    }

    @Test
    fun `cancellation stops the run`() {
        val plan = MissingExtensionsPlan.of(missing(1, 2), listOf(available("ext.a", 1), available("ext.b", 2)), emptyMap(), emptyMap())
        val tried = mutableListOf<String>()
        shouldThrow<CancellationException> {
            runBlocking {
                installMissingExtensions(plan, install = {
                    tried += it.pkgName
                    throw CancellationException("left")
                })
            }
        }
        tried shouldBe listOf("ext.a")
    }

    @Test
    fun `an untrusted extension no store offers gets its sources by name, not unavailable`() {
        val plan = MissingExtensionsPlan.of(
            missing = listOf(
                MissingSource(1, "MangaDex (EN)", "MangaDex"),
                MissingSource(2, "Other (EN)", "Other"),
            ),
            offers = emptyList(),
            installed = emptyMap(),
            untrusted = mapOf(
                "ext.mangadex" to untrusted("ext.mangadex", "own").copy(name = "Tachiyomi: MangaDex"),
                "ext.misc" to untrusted("ext.misc", "own").copy(name = "Tachiyomi: Misc"),
            ),
        )
        plan.untrustedInstalled.map { it.extension.pkgName to it.sources.map(MissingSource::id) } shouldBe listOf(
            "ext.mangadex" to listOf(1L),
            "ext.misc" to emptyList(),
        )
        plan.unavailable.map { it.id } shouldBe listOf(2L)
        plan.needsTrustNames shouldBe listOf("Tachiyomi: MangaDex", "Tachiyomi: Misc")
        plan.isEmpty shouldBe false
    }

    @Test
    fun `an untrusted extension a store offers is not listed twice`() {
        val plan = MissingExtensionsPlan.of(
            missing = missing(1),
            offers = listOf(available("ext.a", 1)),
            installed = emptyMap(),
            untrusted = mapOf("ext.a" to untrusted("ext.a", "key-A")),
        )
        plan.needsTrust.map { it.extension.pkgName } shouldBe listOf("ext.a")
        plan.untrustedInstalled.shouldBeEmpty()
    }

    @Test
    fun `extension names match source names without their prefix`() {
        MissingExtensionsPlan.matchesName("Tachiyomi: MangaDex", "mangadex") shouldBe true
        MissingExtensionsPlan.matchesName("MangaDex", "MangaDex") shouldBe true
        MissingExtensionsPlan.matchesName("Tachiyomi: Manga", "MangaDex") shouldBe false
    }

    @Test
    fun `the summary lists untrusted extensions no store offers as needing trust`() {
        val plan = MissingExtensionsPlan(
            toInstall = emptyList(),
            needsTrust = emptyList(),
            unavailable = emptyList(),
            untrustedInstalled = listOf(MissingExtensionsPlan.UntrustedCandidate(untrusted("ext.u", "own"), emptyList())),
        )
        runBlocking { installMissingExtensions(plan, { error("nothing to install") }) }.needsTrust shouldBe listOf("u")
    }
}

/** A second restore's missing extensions are found even while the first restore's install runs. */
class MissingExtensionsInstallTest {

    private val store = ExtensionStore(
        indexUrl = "https://a.example/index.min.json",
        name = "A",
        badgeLabel = "A",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://a.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun available(pkg: String, sourceId: Long) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        signatureHash = store.signingKey,
        storeName = store.name,
        sources = listOf(Extension.Available.Source(sourceId, "en", "Source $sourceId", "https://s.example")),
        apkUrl = "https://example/$pkg.apk",
        iconUrl = "https://example/$pkg.png",
        store = store,
    )

    private fun plan(pkg: String, sourceId: Long) = MissingExtensionsPlan.of(
        listOf(MissingSource(sourceId, "Source $sourceId")),
        listOf(available(pkg, sourceId)),
        emptyMap(),
        emptyMap(),
    )

    private val originalPlanner = MissingExtensionsInstall.planner

    @AfterEach
    fun tearDown() {
        MissingExtensionsInstall.planner = originalPlanner
        MissingExtensionsInstall.installer = null
        MissingExtensionsInstall.dismiss()
    }

    private fun waitUntil(condition: () -> Boolean) =
        Await.until("the install state", 5_000, { MissingExtensionsInstall.state.value }, condition)

    @Test
    fun `a find during another restore's install runs when the install ends`() {
        val restoreA = Any()
        val restoreB = Any()
        var nextPlan = plan("ext.a", 1)
        MissingExtensionsInstall.planner = { nextPlan }
        val installGate = CompletableDeferred<Unit>()
        MissingExtensionsInstall.installer = { ext ->
            installGate.await()
            LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "test"))
        }

        MissingExtensionsInstall.find(restoreA)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restoreA) is State.Ready }
        MissingExtensionsInstall.install(restoreA)
        MissingExtensionsInstall.state.value.forRestore(restoreA).shouldBeInstanceOf<State.Installing>()

        // Restore B ends while A's install runs: B never shows A's progress, and its find waits
        nextPlan = plan("ext.b", 2)
        MissingExtensionsInstall.dismiss()
        MissingExtensionsInstall.find(restoreB)
        MissingExtensionsInstall.state.value.forRestore(restoreB) shouldBe State.Finding

        installGate.complete(Unit)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restoreB) is State.Ready }
        val ready = MissingExtensionsInstall.state.value.forRestore(restoreB) as State.Ready
        ready.plan.toInstall.single().extension.pkgName shouldBe "ext.b"
        // A's results are no longer shown anywhere
        MissingExtensionsInstall.state.value.forRestore(restoreA) shouldBe State.Idle
    }

    @Test
    fun `the Settings screen shows a running install while its find waits`() {
        val restore = Any()
        val settings = Any()
        MissingExtensionsInstall.planner = { plan("ext.a", 1) }
        val installGate = CompletableDeferred<Unit>()
        MissingExtensionsInstall.installer = { ext ->
            installGate.await()
            LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "test"))
        }

        MissingExtensionsInstall.find(restore)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restore) is State.Ready }
        MissingExtensionsInstall.install(restore)

        // The user leaves the restore screen and opens Settings > Install missing extensions
        MissingExtensionsInstall.dismiss()
        MissingExtensionsInstall.find(settings)
        MissingExtensionsInstall.state.value.forSettings(settings).shouldBeInstanceOf<State.Installing>()
        // A restore screen still never shows another run's progress
        MissingExtensionsInstall.state.value.forRestore(settings) shouldBe State.Finding

        installGate.complete(Unit)
        waitUntil { MissingExtensionsInstall.state.value.forSettings(settings) is State.Ready }
    }

    @Test
    fun `a find waits for a running restore before working out the plan`() {
        val token = Any()
        var planned = false
        var plannedWhileRestoring = false
        MissingExtensionsInstall.planner = {
            planned = true
            plannedWhileRestoring = RestoreGate.isRestoring
            plan("ext.a", 1)
        }
        val restoreGate = CompletableDeferred<Unit>()
        val restoring = CompletableDeferred<Unit>()
        val restore = Thread {
            runBlocking {
                RestoreGate.whileRestoring {
                    restoring.complete(Unit)
                    runBlocking { restoreGate.await() }
                }
            }
        }.apply { start() }
        runBlocking { restoring.await() }

        MissingExtensionsInstall.find(token)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(token) == State.WaitingForRestore }
        planned shouldBe false

        restoreGate.complete(Unit)
        restore.join()
        waitUntil { MissingExtensionsInstall.state.value.forRestore(token) is State.Ready }
        planned shouldBe true
        // The plan was worked out only after the restore ended, however long the find waited
        plannedWhileRestoring shouldBe false
    }

    @Test
    fun `running out of memory while finding leaves the panel idle, not finding for good`() {
        val token = Any()
        val planned = CompletableDeferred<Unit>()
        MissingExtensionsInstall.planner = {
            planned.complete(Unit)
            throw OutOfMemoryError("test")
        }
        MissingExtensionsInstall.find(token)
        runBlocking { planned.await() }
        waitUntil { MissingExtensionsInstall.state.value.state == State.Idle }
        MissingExtensionsInstall.state.value.forRestore(token) shouldBe State.Idle
    }
}
