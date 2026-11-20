package zoukan.desktop.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.web_dav
import tachiyomi.i18n.none
import tachiyomi.i18n.pref_app_theme
import tachiyomi.i18n.pref_crop_borders
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.mangadex_sync_follows_to_library
import tachiyomi.i18n.sy.pref_google_drive_sign_in
import tachiyomi.i18n.theme_nord
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.lock_on_these_days
import zoukan.i18n.desktop.other_browser
import zoukan.i18n.desktop.sync_service
import zoukan.i18n.desktop.use_japanese_titles
import java.io.File

/**
 * Each settings widget stores what it shows: the value shown comes from the preference, and a
 * change writes exactly that value (and nothing on Cancel). Edge values included: the ends of a
 * slider, a value that is none of the choices, empty sets and members the widget does not list.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsWidgetsTest {

    /** A preference whose changes reach Compose, and which records every write. */
    private class TestPreference<T>(private val key: String, private val default: T, initial: T = default) : Preference<T> {
        val writes = mutableListOf<T>()
        private val state = MutableStateFlow(initial)
        override fun key(): String = key
        override fun get(): T = state.value
        override fun set(value: T) {
            writes += value
            state.value = value
        }
        override fun isSet(): Boolean = writes.isNotEmpty()
        override fun delete() {
            state.value = default
        }
        override fun defaultValue(): T = default
        override fun changes(): Flow<T> = state
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
    }

    private val ok get() = localized(MR.strings.action_ok)
    private val cancel get() = localized(MR.strings.action_cancel)

    private fun ComposeUiTest.switchRow(title: String) = onNode(isToggleable() and hasText(title))

    // --- SwitchPreference ---

    @Test
    fun `a switch shows its preference and a click writes the other value`() = runDesktopComposeUiTest {
        val preference = TestPreference("switch", default = false, initial = true)
        setContent { SwitchPreference("Switch", preference) }
        switchRow("Switch").assertIsOn()

        switchRow("Switch").performClick()
        waitForIdle()
        preference.writes shouldBe listOf(false)
        switchRow("Switch").assertIsOff()

        switchRow("Switch").performClick()
        waitForIdle()
        preference.writes shouldBe listOf(false, true)
        switchRow("Switch").assertIsOn()
    }

    @Test
    fun `a switch follows a change made elsewhere`() = runDesktopComposeUiTest {
        val preference = TestPreference("switch", default = false)
        setContent { SwitchPreference("Switch", preference) }
        switchRow("Switch").assertIsOff()
        preference.set(true)
        waitForIdle()
        switchRow("Switch").assertIsOn()
    }

    // --- ChoicePreference ---

    private val choices = listOf(0 to "Zero", 1 to "One", 2 to "Two")

    @Test
    fun `a choice shows its label and choosing writes exactly that value`() = runDesktopComposeUiTest {
        val preference = TestPreference("choice", default = 0, initial = 1)
        setContent { ChoicePreference("Choice", preference, choices) }
        onNodeWithText("One").assertExists()

        onNodeWithText("Choice").performClick()
        onNodeWithText("Two").performClick()
        waitForIdle()
        preference.writes shouldBe listOf(2)
        // The dialog closed and the row shows the new choice
        onNodeWithText("One").assertDoesNotExist()
        onNodeWithText("Two").assertExists()
    }

    @Test
    fun `cancelling a choice writes nothing`() = runDesktopComposeUiTest {
        val preference = TestPreference("choice", default = 0)
        setContent { ChoicePreference("Choice", preference, choices) }
        onNodeWithText("Choice").performClick()
        onNodeWithText(cancel).performClick()
        waitForIdle()
        preference.writes shouldBe emptyList()
        onNodeWithText("Zero").assertExists()
    }

    @Test
    fun `choosing the current value writes it again`() = runDesktopComposeUiTest {
        val preference = TestPreference("choice", default = 0)
        setContent { ChoicePreference("Choice", preference, choices) }
        onNodeWithText("Choice").performClick()
        // The row's summary and the dialog's choice both read "Zero": the dialog's comes last
        onAllNodesWithText("Zero").fetchSemanticsNodes().size shouldBe 2
        onAllNodesWithText("Zero")[1].performClick()
        waitForIdle()
        preference.writes shouldBe listOf(0)
    }

    @Test
    fun `a value that is none of the choices shows no summary until one is chosen`() = runDesktopComposeUiTest {
        val preference = TestPreference("choice", default = 0, initial = 7)
        setContent { ChoicePreference("Choice", preference, choices) }
        choices.forEach { (_, label) -> onNodeWithText(label).assertDoesNotExist() }
        onNodeWithText("Choice").performClick()
        onNodeWithText("Zero").performClick()
        waitForIdle()
        preference.writes shouldBe listOf(0)
    }

    // --- SliderPreference ---

    @Test
    fun `a slider shows its value and a move writes the whole number it lands on`() = runDesktopComposeUiTest {
        val preference = TestPreference("slider", default = 5, initial = 3)
        setContent { SliderPreference("Slider", preference, 0..10) { "Value $it" } }
        onNodeWithText("Value 3").assertExists()

        onNodeWithContentDescription("Slider").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        waitForIdle()
        preference.writes shouldBe listOf(7)
        onNodeWithText("Value 7").assertExists()
    }

    @Test
    fun `a slider reaches both ends of its range`() = runDesktopComposeUiTest {
        val preference = TestPreference("slider", default = 5)
        setContent { SliderPreference("Slider", preference, 1..10) { "Value $it" } }
        onNodeWithContentDescription("Slider").performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        waitForIdle()
        onNodeWithContentDescription("Slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        // Past the ends, it stops at them
        onNodeWithContentDescription("Slider").performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        waitForIdle()
        preference.writes shouldBe listOf(10, 1, 10)
        onNodeWithText("Value 10").assertExists()
    }

    @Test
    fun `a saved value outside the range shows as it is and is not written back`() = runDesktopComposeUiTest {
        val preference = TestPreference("slider", default = 5, initial = 42)
        setContent { SliderPreference("Slider", preference, 0..10) { "Value $it" } }
        onNodeWithText("Value 42").assertExists()
        waitForIdle()
        preference.writes shouldBe emptyList()
    }

    @Test
    fun `a slider's steps are one per whole number`() {
        sliderSteps(0..10) shouldBe 9
        sliderSteps(1..2) shouldBe 0
        sliderSteps(3..3) shouldBe 0
        // An empty range has no steps rather than a negative count
        sliderSteps(5..1) shouldBe 0
    }

    // --- SetMemberPreference ---

    @Test
    fun `a set member switch adds and removes only its member`() = runDesktopComposeUiTest {
        val preference = TestPreference("set", default = emptySet(), initial = setOf("other"))
        setContent { SetMemberPreference("Member", preference, "wifi") }
        switchRow("Member").assertIsOff()

        switchRow("Member").performClick()
        waitForIdle()
        preference.writes shouldBe listOf(setOf("other", "wifi"))
        switchRow("Member").assertIsOn()

        switchRow("Member").performClick()
        waitForIdle()
        preference.writes.last() shouldBe setOf("other")
        switchRow("Member").assertIsOff()
    }

    @Test
    fun `adding or removing a member keeps the others`() {
        withMember(emptySet(), "a", on = true) shouldBe setOf("a")
        withMember(setOf("a"), "a", on = true) shouldBe setOf("a")
        withMember(setOf("a", "b"), "a", on = false) shouldBe setOf("b")
        withMember(setOf("b"), "a", on = false) shouldBe setOf("b")
    }

    // --- MultiSelectPreference ---

    private val multi = listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma")

    @Test
    fun `a multi-select shows the chosen labels and OK writes exactly the ticked set`() = runDesktopComposeUiTest {
        val preference = TestPreference("multi", default = emptySet(), initial = setOf("c", "a"))
        setContent { MultiSelectPreference("Multi", preference, multi) }
        // In the order of the choices, not of the set
        onNodeWithText("Alpha, Gamma").assertExists()

        onNodeWithText("Multi").performClick()
        onNodeWithText("Beta").performClick()
        onNodeWithText("Alpha").performClick()
        onNodeWithText(ok).performClick()
        waitForIdle()
        preference.writes shouldBe listOf(setOf("b", "c"))
        onNodeWithText("Beta, Gamma").assertExists()
    }

    @Test
    fun `cancelling a multi-select writes nothing`() = runDesktopComposeUiTest {
        val preference = TestPreference("multi", default = emptySet(), initial = setOf("a"))
        setContent { MultiSelectPreference("Multi", preference, multi) }
        onNodeWithText("Multi").performClick()
        onNodeWithText("Beta").performClick()
        onNodeWithText(cancel).performClick()
        waitForIdle()
        preference.writes shouldBe emptyList()
        onNodeWithText("Alpha").assertExists()
    }

    @Test
    fun `an empty multi-select reads None and can be emptied`() = runDesktopComposeUiTest {
        val preference = TestPreference("multi", default = emptySet(), initial = setOf("a"))
        setContent { MultiSelectPreference("Multi", preference, multi) }
        onNodeWithText("Multi").performClick()
        // "Alpha" is both the summary and the dialog's box: the box comes last
        onAllNodesWithText("Alpha")[1].performClick()
        onNodeWithText(ok).performClick()
        waitForIdle()
        preference.writes shouldBe listOf(emptySet())
        onNodeWithText(localized(MR.strings.none)).assertExists()
    }

    @Test
    fun `members the dialog does not list are kept`() = runDesktopComposeUiTest {
        val preference = TestPreference("multi", default = emptySet(), initial = setOf("gone", "a"))
        setContent { MultiSelectPreference("Multi", preference, multi) }
        // The summary names only the listed ones
        onNodeWithText("Alpha").assertExists()
        onNodeWithText("Multi").performClick()
        onNodeWithText("Beta").performClick()
        onNodeWithText(ok).performClick()
        waitForIdle()
        preference.writes shouldBe listOf(setOf("gone", "a", "b"))
    }

    @Test
    fun `the multi-select summary`() {
        multiSelectSummary(emptySet(), multi) shouldBe localized(MR.strings.none)
        multiSelectSummary(setOf("gone"), multi) shouldBe localized(MR.strings.none)
        multiSelectSummary(setOf("b", "a"), multi) shouldBe "Alpha, Beta"
        multiSelectSummary(setOf("a"), emptyList()) shouldBe localized(MR.strings.none)
        choiceSummary(1, choices) shouldBe "One"
        choiceSummary(9, choices) shouldBe null
        choiceSummary(1, emptyList()) shouldBe null
    }

    // --- CategoriesIncludeExcludePreference ---

    private fun category(id: Long, name: String, order: Long) = Category(id = id, name = name, order = order, flags = 0, hidden = false)

    private val categories = listOf(category(1, "Reading", 1), category(2, "Done", 2))

    @Test
    fun `a click cycles a category through included, excluded and neither`() {
        categoryState("1", emptySet(), emptySet()) shouldBe ToggleableState.Off
        categoryState("1", setOf("1"), emptySet()) shouldBe ToggleableState.On
        categoryState("1", emptySet(), setOf("1")) shouldBe ToggleableState.Indeterminate
        // A category in both sets reads as included
        categoryState("1", setOf("1"), setOf("1")) shouldBe ToggleableState.On

        var sets = emptySet<String>() to emptySet<String>()
        fun click() {
            sets = cycleCategory(categoryState("1", sets.first, sets.second), "1", sets.first, sets.second)
        }
        click()
        sets shouldBe (setOf("1") to emptySet())
        click()
        sets shouldBe (emptySet<String>() to setOf("1"))
        click()
        sets shouldBe (emptySet<String>() to emptySet())
        // Other categories are left alone
        cycleCategory(ToggleableState.Off, "1", setOf("2"), setOf("3")) shouldBe (setOf("2", "1") to setOf("3"))
    }

    @Test
    fun `the categories dialog writes both sets on OK and nothing on Cancel`() = runDesktopComposeUiTest {
        val include = TestPreference<Set<String>>("include", default = emptySet())
        val exclude = TestPreference("exclude", default = emptySet(), initial = setOf("2"))
        setContent {
            Column { CategoriesIncludeExcludePreference("Categories", "Pick", categories, include, exclude) }
        }
        onNodeWithText(categoriesLabel(categories, emptySet(), setOf("2"))).assertExists()

        onNodeWithText("Categories").performClick()
        onNodeWithText(cancel).performClick()
        waitForIdle()
        include.writes shouldBe emptyList()
        exclude.writes shouldBe emptyList()

        onNodeWithText("Categories").performClick()
        // Reading: neither to included. Done: excluded to neither
        onNodeWithText("Reading").performClick()
        onNodeWithText("Done").performClick()
        onNodeWithText(ok).performClick()
        waitForIdle()
        include.writes shouldBe listOf(setOf("1"))
        exclude.writes shouldBe listOf(emptySet())
        onNodeWithText(categoriesLabel(categories, setOf("1"), emptySet())).assertExists()
    }
}

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

@OptIn(ExperimentalTestApi::class)
class SettingsTabTest {

    private fun Color.luminanceSum() = red + green + blue

    @Test
    fun `the settings list is darker than a dark surface and lighter than a light one`() {
        val dark = Color(0xFF1B1B1F)
        val light = Color(0xFFFEFBFF)
        palerSurface(dark).luminanceSum() shouldBeLessThan dark.luminanceSum()
        palerSurface(light).luminanceSum() shouldBeGreaterThan Color(0xFFF0F0F0).luminanceSum()
        palerSurface(Color.White) shouldBe Color.White
    }

    @Test
    fun `a disabled setting row reports that it is disabled`() = runDesktopComposeUiTest {
        setContent {
            Column {
                SettingRow("Locked", "Why", enabled = false, onClick = {})
                SettingRow("Plain", "Why", enabled = false, onClick = null)
                SettingRow("Open", "Why", enabled = true, onClick = {})
            }
        }
        onNodeWithText("Locked").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithText("Plain").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        onNodeWithText("Open").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
    }

    @Test
    fun `a disabled setting row's title is drawn at the disabled alpha`() {
        // A colour keeps its alpha in 8 bits, so 0.38 comes back a little off
        settingTitleColor(false, lightColorScheme()).alpha shouldBe (0.38f plusOrMinus 0.01f)
        settingTitleColor(true, lightColorScheme()) shouldBe lightColorScheme().onSurface
    }

    @Test
    fun `settings pages are in the Android app's order`() {
        SettingsCategory.entries.map { it.title }.take(4) shouldBe listOf("Appearance", "Library", "Reader", "Downloads")
        SettingsCategory.entries.last() shouldBe SettingsCategory.About
    }
}

/** A list scope that records the keys the page builders give their items, and never runs their content. */
private class KeyRecorder : LazyListScope {
    val keys = mutableListOf<Any?>()

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
    }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        repeat(count) { keys += key?.invoke(it) }
    }

    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
    }
}

private fun page(build: LazyListScope.() -> Unit): List<Any> = KeyRecorder().apply(build).keys.filterNotNull()

class SettingsGroupKeysTest {
    // The pages as SettingsTab puts them together. MangaDex and Tracking are left out: they read
    // Injekt while they build the list, not inside an item.
    private val pages: Map<String, List<Any>> = mapOf(
        "Appearance" to page { appearanceSettings() },
        "Library" to page { librarySettings(emptyList()) },
        "Downloads" to page { downloadBehaviorSettings(emptyList()) },
        "Browse" to page { browseSettings() },
        "Data" to page {
            dataSettings()
            syncSettings()
            dataSaverSettings()
        },
        "Security" to page { securitySettings() },
        "EHentai" to page { ehSettings() },
        "Advanced" to page {
            shortcutSettings()
            advancedSettings()
        },
        "About" to page { aboutSettings() },
    )

    @Test
    fun `settings group headers are keyed without padding`() {
        for ((name, keys) in pages) {
            val padded = keys.filterIsInstance<String>().filter { it.startsWith(GROUP_KEY_PREFIX) && it != it.trim() }
            check(padded.isEmpty()) { "$name has padded group keys: $padded" }
        }
    }

    @Test
    fun `every settings page has unique list keys`() {
        for ((name, keys) in pages) {
            val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            check(duplicates.isEmpty()) { "$name has duplicate list keys: $duplicates" }
        }
        pages.size shouldBe 9
    }
}

/** Keeps user-visible letters in Settings coming from string resources. */
class SettingsLiteralsTest {

    private val dir = File("src/main/kotlin/zoukan/desktop/ui/settings")

    /** Every source line outside comments, with its file name and 1-based number. */
    private fun codeLines(): List<Triple<String, Int, String>> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f.name, i + 1, line)
            }
        }
        .toList()

    // Technical texts kept on purpose: cookie names and URL-shaped hints are not sentences
    private val allowed = listOf("ipb_member_id", "ipb_pass_hash", "komikku://", "https://")

    @Test
    fun `settings text comes from string resources`() {
        val literal = Regex("""Text\("(\p{L}[^"]*)""")
        val hits = codeLines().filter { (_, _, line) ->
            literal.findAll(line).any { m -> allowed.none { m.groupValues[1].startsWith(it) } }
        }.map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }

    @Test
    fun `the date format list labels its default from a resource`() {
        val hits = codeLines().filter { (_, _, line) -> line.contains("\"Default\"") }
            .map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }

    @Test
    fun `sign-in errors do not show raw exception text`() {
        val raw = Regex("""error\s*=\s*(e|it)\.message""")
        val hits = codeLines().filter { (_, _, line) -> raw.containsMatchIn(line) }
            .map { (f, n, line) -> "$f:$n: ${line.trim()}" }
        hits shouldBe emptyList()
    }
}

class SettingsSearchIndexTest {

    private class RecordedKeys : LazyListScope {
        val keys = mutableSetOf<Any>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            if (key != null) keys += key
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            repeat(count) { i -> key?.invoke(i)?.let { keys += it } }
        }

        @ExperimentalFoundationApi
        override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            if (key != null) keys += key
        }
    }

    private fun keysFor(build: LazyListScope.() -> Unit): Set<Any> = RecordedKeys().apply(build).keys

    @Test
    fun `declarative index covers every category and omits MangaDex only when disabled`() {
        val full = SettingsSearch.index(includeMangaDex = true, trackers = listOf(1L to "MyAnimeList"))
        full.map { it.category }.toSet() shouldContainAll SettingsCategory.entries
        full.all { it.title.isNotBlank() && it.breadcrumb.isNotBlank() && it.itemKey != null } shouldBe true

        val withoutMdex = SettingsSearch.index(includeMangaDex = false)
        withoutMdex.map { it.category }.toSet() shouldNotContain SettingsCategory.MangaDex
    }

    @Test
    fun `every indexed itemKey exists on its target settings page`() {
        val index = SettingsSearch.index(includeMangaDex = true)
        val readerKeys = keysFor { readerBehaviourSettings(DesktopPreferences(TestPreferenceStore())) } + setOf(
            "reader-default-mode",
            "reader-image-scale-type",
            "reader-theme",
            "reader-webtoon-side-padding",
            "reader-show-page-number",
        )
        val downloadsKeys = keysFor { downloadBehaviorSettings(emptyList()) } + setOf(
            "downloads-cbz",
            "downloads-split-tall",
            "downloads-concurrent-sources",
            "downloads-concurrent-pages",
            "downloads-password-protect",
            "downloads-set-password",
            "downloads-encryption",
        )
        val advancedKeys = keysFor {
            shortcutSettings()
            advancedSettings()
        } + "advanced-tray"
        val pageKeys = mapOf(
            SettingsCategory.Appearance to keysFor { appearanceSettings() },
            SettingsCategory.Library to (keysFor { librarySettings(emptyList()) } + "library-edit-categories"),
            SettingsCategory.Reader to readerKeys,
            SettingsCategory.Downloads to downloadsKeys,
            SettingsCategory.Browse to keysFor { browseSettings() },
            SettingsCategory.Data to keysFor {
                dataSettings()
                syncSettings()
                dataSaverSettings()
            },
            SettingsCategory.Security to keysFor { securitySettings() },
            SettingsCategory.EHentai to keysFor { ehSettings() },
            SettingsCategory.Advanced to advancedKeys,
            SettingsCategory.About to keysFor { aboutSettings() },
        )

        pageKeys.forEach { (category, keys) ->
            val indexedKeys = index.filter { it.category == category }.mapNotNull { it.itemKey }
            indexedKeys.shouldNotBeEmpty()
            indexedKeys.filterNot { it in keys } shouldBe emptyList()
        }
    }

    @Test
    fun `search matches titles, summaries, choice keywords, reader settings, and trackers`() {
        val index = SettingsSearch.index(
            includeMangaDex = true,
            trackers = listOf(2L to "AniList"),
        )

        SettingsSearch.search("", index).shouldBeEmpty()
        SettingsSearch.search("   ", index).shouldBeEmpty()
        SettingsSearch.search("xyznonexistent999", index).shouldBeEmpty()

        // Choice keyword
        val quad9 = SettingsSearch.search("quad9", index)
        quad9.map { it.itemKey } shouldContain "advanced-doh"

        // Multi-word across title + summary, ignoring case
        val cbz = SettingsSearch.search("save cbz", index)
        cbz.map { it.itemKey } shouldContain "downloads-cbz"

        // Reader behavior setting from ReaderSettingRows
        val cropBorders = SettingsSearch.search(localized(MR.strings.pref_crop_borders), index)
        cropBorders.map { it.category } shouldContain SettingsCategory.Reader
        cropBorders.map { it.itemKey } shouldContain "reader-crop_borders"

        // Dynamic tracker
        val anilist = SettingsSearch.search("anilist", index)
        anilist.map { it.category } shouldContain SettingsCategory.Tracking
        anilist.map { it.itemKey } shouldContain "tracker-2"

        // Category filtering
        val disabledMdex = SettingsSearch.search(
            localized(SYMR.strings.mangadex_sync_follows_to_library),
            index,
            enabledCategories = setOf(SettingsCategory.Appearance, SettingsCategory.Library),
        )
        disabledMdex.shouldBeEmpty()
    }

    @Test
    fun `SettingsScreenModel scopes navigation, search, and jump-to-page highlight per instance`() {
        val first = SettingsScreenModel(
            isMangaDexInstalled = { false },
            trackersProvider = { listOf(2L to "AniList") },
        )
        val second = SettingsScreenModel(
            isMangaDexInstalled = { true },
            trackersProvider = { emptyList() },
        )

        first.state.value.selectedCategory shouldBe SettingsCategory.Appearance
        first.state.value.pages shouldNotContain SettingsCategory.MangaDex
        second.state.value.pages shouldContain SettingsCategory.MangaDex

        // Searching and jumping to a page in first does not leak into second
        first.setSearchQuery(localized(KMR.strings.web_dav))
        first.state.value.searchResults.shouldNotBeEmpty()
        second.state.value.searchQuery shouldBe ""
        second.state.value.searchResults.shouldBeEmpty()

        val webDavResult = first.state.value.searchResults.first { it.category == SettingsCategory.Data }
        first.openSearchResult(webDavResult)

        first.state.value.selectedCategory shouldBe SettingsCategory.Data
        first.state.value.searchQuery shouldBe ""
        first.state.value.searchResults.shouldBeEmpty()
        first.state.value.highlightedItem shouldBe (SettingsCategory.Data to webDavResult.itemKey!!)
        second.state.value.selectedCategory shouldBe SettingsCategory.Appearance
        second.state.value.highlightedItem shouldBe null

        first.clearHighlight()
        first.state.value.highlightedItem shouldBe null

        // Selecting a category clears an active search
        first.setSearchQuery(localized(DMR.strings.use_japanese_titles))
        first.state.value.searchResults.shouldNotBeEmpty()
        first.selectCategory(SettingsCategory.About)
        first.state.value.selectedCategory shouldBe SettingsCategory.About
        first.state.value.searchQuery shouldBe ""
        first.state.value.searchResults.shouldBeEmpty()
    }

    @Test
    fun `openSettingsCategory updates an active SettingsScreenModel or queues one-shot pending category`() {
        val pendingTarget = SettingsScreenModel(isMangaDexInstalled = { false })
        openSettingsCategory(SettingsCategory.Security)
        // Consumed by the next model created
        val opened = SettingsScreenModel(isMangaDexInstalled = { false })
        opened.state.value.selectedCategory shouldBe SettingsCategory.Security
        // Not leaked to subsequent models
        val next = SettingsScreenModel(isMangaDexInstalled = { false })
        next.state.value.selectedCategory shouldBe SettingsCategory.Appearance

        // Active model receives openSettingsCategory directly
        SettingsScreenModel.register(pendingTarget)
        try {
            openSettingsCategory(SettingsCategory.Advanced)
            pendingTarget.state.value.selectedCategory shouldBe SettingsCategory.Advanced
        } finally {
            SettingsScreenModel.unregister(pendingTarget)
        }
    }
}

/** What the declarative settings search index can find: choice labels and settings behind toggles. */
class SettingsSearchCoverageTest {

    @Test
    fun `a choice setting is found by any of its choices`() {
        val index = SettingsSearch.index()
        val nord = SettingsSearch.search(localized(MR.strings.theme_nord), index)
        nord.shouldNotBeEmpty()
        nord.map { it.title } shouldContain localized(MR.strings.pref_app_theme)

        val webDav = SettingsSearch.search(localized(KMR.strings.web_dav), index)
        webDav.shouldNotBeEmpty()
        webDav.map { it.title } shouldContain localized(DMR.strings.sync_service)
    }

    @Test
    fun `settings behind conditional toggles remain searchable in the declarative index`() {
        val index = SettingsSearch.index()
        val japaneseTitles = SettingsSearch.search(localized(DMR.strings.use_japanese_titles), index)
        japaneseTitles.map { it.category } shouldContain SettingsCategory.EHentai

        val lockDays = SettingsSearch.search(localized(DMR.strings.lock_on_these_days), index)
        lockDays.map { it.category } shouldContain SettingsCategory.Security
    }
}

class SettingsSearchTest {
    @Test
    fun `every word of the search is in the title or summary, ignoring case`() {
        val texts = listOf("Create backup", "Backs up the library, categories and settings")
        SettingsSearch.matches("backup", texts) shouldBe true
        SettingsSearch.matches("CREATE library", texts) shouldBe true
        SettingsSearch.matches("  library   create ", texts) shouldBe true
        SettingsSearch.matches("restore", texts) shouldBe false
        SettingsSearch.matches("create restore", texts) shouldBe false
    }

    @Test
    fun `a blank search matches nothing`() {
        SettingsSearch.matches("  ", listOf("Anything")) shouldBe false
        SettingsSearch.matches("theme", emptyList()) shouldBe false
    }

    @Test
    fun `items without a key are found by their place, the same in search and on the page`() {
        SettingsSearch.itemId("data-export", 3) shouldBe "data-export"
        SettingsSearch.itemId(null, 3) shouldBe "#3"
    }
}
