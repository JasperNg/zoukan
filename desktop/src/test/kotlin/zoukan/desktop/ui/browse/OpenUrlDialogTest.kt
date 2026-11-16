package zoukan.desktop.ui.browse

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.kmk.KMR
import zoukan.desktop.compat.localized

@OptIn(ExperimentalTestApi::class)
class OpenUrlDialogTest {
    private val address = "https://example.org/manga/1"

    @Test
    fun `text that is not an address cannot be opened`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlDialogContent(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput("one piece")
        onNodeWithText(localized(KMR.strings.open)).assertIsNotEnabled()
        onNodeWithTag("open-url-field").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitForIdle()
        opened shouldBe emptyList()
    }

    @Test
    fun `one Enter press opens the address once`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlDialogContent(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput(address)
        onNodeWithTag("open-url-field").performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        waitForIdle()
        opened shouldBe listOf(address)
    }

    @Test
    fun `an address can be opened with the button`() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        setContent { OpenUrlDialogContent(onOpen = { opened += it }, onDismiss = {}) }

        onNodeWithTag("open-url-field").performTextInput(address)
        onNodeWithText(localized(KMR.strings.open)).assertIsEnabled().performClick()
        waitForIdle()
        opened shouldBe listOf(address)
    }
}
