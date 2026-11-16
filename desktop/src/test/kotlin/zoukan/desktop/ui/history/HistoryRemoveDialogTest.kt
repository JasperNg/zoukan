package zoukan.desktop.ui.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/** History's remove dialog: this chapter only, or every chapter of the entry when the box is ticked. */
@OptIn(ExperimentalTestApi::class)
class HistoryRemoveDialogTest {

    private val row: HistoryWithRelations = mockk(relaxed = true) {
        every { id } returns 1L
        every { chapterId } returns 1L
        every { title } returns "Alpha"
    }

    @Test
    fun `remove takes this chapter only, or the whole entry when the box is ticked`() = runDesktopComposeUiTest {
        val answers = mutableListOf<Boolean>()
        var dismissed = 0
        val shown = mutableStateOf(true)
        setContent {
            if (shown.value) {
                RemoveDialog(
                    items = listOf(row),
                    onRemove = {
                        answers += it
                        shown.value = false
                    },
                    onDismiss = { dismissed++ },
                )
            }
        }
        val remove = localized(MR.strings.action_remove)

        onNodeWithText("Alpha").assertExists()
        onNodeWithText(remove).performClick()
        answers shouldBe listOf(false)

        shown.value = true
        waitForIdle()
        onNodeWithText(localized(DMR.strings.remove_every_chapter_of_this_entry)).performClick()
        onNodeWithText(remove).performClick()
        answers shouldBe listOf(false, true)

        shown.value = true
        waitForIdle()
        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        answers shouldBe listOf(false, true)
        dismissed shouldBe 1
    }
}
