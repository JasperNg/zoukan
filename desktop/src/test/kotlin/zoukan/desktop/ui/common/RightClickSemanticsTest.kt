package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.io.File

/** What only a right-click opens is also a "More actions" custom action, for screen readers, keys and the crawl. */
@OptIn(ExperimentalTestApi::class)
class RightClickSemanticsTest {
    @Test
    fun `more actions is a custom action that runs the right-click's action`() = runDesktopComposeUiTest {
        var opened = 0
        setContent { Box(Modifier.size(50.dp).testTag("page").moreActions { opened++ }) }
        val actions = onNodeWithTag("page").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.map { it.label } shouldBe listOf(localized(DMR.strings.more_actions))
        runOnUiThread { actions.single().action() }
        opened shouldBe 1
    }

    @Test
    fun `every element that handles a right-click itself also has the custom action`() {
        // The readers handle the right-click themselves (before their tap zones), so they can't
        // use onRightClick; each must then say moreActions
        val root = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }
        val missing = root.walk().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { "isSecondaryPressed" in it || "PointerButton.Secondary" in it } }
            .filter { f -> f.readText().let { "moreActions" !in it && "onRightClick" !in it } }
            .map { it.path }.toList()
        missing.shouldBeEmpty()
    }
}
