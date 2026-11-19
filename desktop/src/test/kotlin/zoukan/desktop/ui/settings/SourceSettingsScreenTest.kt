package zoukan.desktop.ui.settings

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.app.Toasts
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
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

    @BeforeEach
    fun setUp() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun tearDown() {
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
                Await.until("auto toast") { toasts.any { "Auto chose High (was low)" in it } }
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
