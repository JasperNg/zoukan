package zoukan.desktop.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class LibraryDeleteDownloadsConfirmTest {

    @Test
    fun `deleting the downloads of two entries asks first and deletes only on confirm`() = runDesktopComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        val shown = mutableStateOf(true)
        setContent {
            if (shown.value) {
                LibraryDeleteDownloadsDialog(
                    entryCount = 2,
                    onConfirm = { confirmed++ },
                    onDismiss = {
                        dismissed++
                        shown.value = false
                    },
                )
            }
        }
        onNodeWithText("2 entries", substring = true).assertExists()

        onNodeWithText("Cancel").performClick()
        confirmed shouldBe 0
        dismissed shouldBe 1

        shown.value = true
        waitForIdle()
        onNodeWithText("Delete downloads").performClick()
        confirmed shouldBe 1
    }
}
