package zoukan.desktop.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

@OptIn(ExperimentalTestApi::class)
class SelectionBarTest {
    @Test
    fun `the selection bar shows the count and calls clear, select all and invert`() = runDesktopComposeUiTest {
        val calls = mutableListOf<String>()
        setContent {
            SelectionBar(3, onClear = { calls += "clear" }, onSelectAll = { calls += "all" }, onInvert = { calls += "invert" }) {
                TooltipIconButton(Icons.Outlined.Delete, "Action", onClick = { calls += "action" })
            }
        }
        onNodeWithText(localized(DMR.strings.selected, 3)).assertExists()
        onNodeWithContentDescription(localized(DMR.strings.clear_selection)).performClick()
        onNodeWithContentDescription(localized(MR.strings.action_select_all)).performClick()
        onNodeWithContentDescription(localized(DMR.strings.invert_selection)).performClick()
        onNodeWithContentDescription("Action").performClick()
        calls shouldBe listOf("clear", "all", "invert", "action")
    }
}
