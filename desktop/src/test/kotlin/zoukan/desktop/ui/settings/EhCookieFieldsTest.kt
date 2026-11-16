package zoukan.desktop.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class EhCookieFieldsTest {
    @Test
    fun `the pass hash cookie field is masked`() = runDesktopComposeUiTest {
        setContent {
            EhCookieFields("123", {}, "abcdef123", {}, "", {})
        }

        // A masked field is marked as a password; its semantics text is the typed value, so the
        // mark is what the test can check
        onNode(hasSetTextAction() and hasText("ipb_pass_hash"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }
}
