package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized

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
