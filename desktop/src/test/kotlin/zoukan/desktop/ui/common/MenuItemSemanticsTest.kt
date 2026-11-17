package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/** A menu item with a checkbox or radio button in it tells screen readers its state. */
@OptIn(ExperimentalTestApi::class)
class MenuItemSemanticsTest {
    @Test
    fun `a checked menu item is announced on or off, and its click toggles it`() = runDesktopComposeUiTest {
        var on by mutableStateOf(false)
        setContent {
            Column {
                DropdownMenuItem(
                    text = { Text("English") },
                    leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                    modifier = Modifier.checkedMenuItem(on),
                    onClick = { on = !on },
                )
            }
        }
        onNodeWithText("English").assertIsOff()
        onNodeWithText("English").performClick()
        onNodeWithText("English").assertIsOn()
    }

    @Test
    fun `a radio menu item is announced selected or not`() = runDesktopComposeUiTest {
        setContent {
            Column {
                listOf("Left to right", "Right to left").forEach { label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = { RadioButton(selected = label == "Right to left", onClick = null) },
                        modifier = Modifier.selectedMenuItem(label == "Right to left"),
                        onClick = {},
                    )
                }
            }
        }
        onNodeWithText("Right to left").assertIsSelected()
        onNodeWithText("Left to right").assertIsNotSelected()
    }

    @Test
    fun `every menu item with a checkbox or radio button says its state`() {
        val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }
        val leading = Regex("""leadingIcon = \{ (Checkbox|RadioButton|TriStateCheckbox)\(""")
        val missing = root.walk().filter { it.extension == "kt" }.flatMap { f ->
            val lines = f.readLines()
            lines.indices.filter { leading.containsMatchIn(lines[it]) }
                .filter { i -> lines.subList(maxOf(0, i - 4), minOf(lines.size, i + 5)).none { "checkedMenuItem" in it || "selectedMenuItem" in it } }
                .map { "${f.path}:${it + 1}" }
        }.toList()
        missing.shouldBeEmpty()
    }
}
