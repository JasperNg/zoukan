package zoukan.desktop.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

@OptIn(ExperimentalTestApi::class)
class MangaDexSettingsTest {
    private val rowTitle get() = localized(SYMR.strings.mangadex_push_favorites_to_mangadex)

    @Test
    fun `following the library on MangaDex asks first`() = runDesktopComposeUiTest {
        var followed = 0
        setContent { FollowLibraryRow(enabled = true, count = { 3 }) { followed++ } }

        onNodeWithText(rowTitle).performClick()
        waitForIdle()

        followed shouldBe 0
        onNodeWithText(localized(DMR.strings.follow_library_on_mangadex_warning, 3), substring = true).assertExists()

        onNodeWithText(localized(DMR.strings.follow_on_mangadex)).performClick()
        waitForIdle()
        followed shouldBe 1
    }

    @Test
    fun `cancelling the MangaDex follow changes nothing`() = runDesktopComposeUiTest {
        var followed = 0
        setContent { FollowLibraryRow(enabled = true, count = { 3 }) { followed++ } }

        onNodeWithText(rowTitle).performClick()
        waitForIdle()
        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()

        followed shouldBe 0
        onNodeWithText(localized(DMR.strings.follow_on_mangadex)).assertDoesNotExist()
    }

    @Test
    fun `cancelling the sync follows dialog confirms nothing`() = runDesktopComposeUiTest {
        var confirmed: Set<String>? = null
        setContent { SyncFollowsDialog(onDismiss = {}, onConfirm = { confirmed = it }) }

        onNodeWithText(localized(SYMR.strings.mangadex_sync_follows_to_library)).assertExists()
        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        confirmed shouldBe null
    }
}
