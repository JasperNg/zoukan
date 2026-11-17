package zoukan.desktop.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager.CleanupItem
import zoukan.desktop.download.DownloadManager.CleanupPlan
import zoukan.desktop.download.DownloadManager.CleanupReason
import zoukan.i18n.desktop.DMR
import java.io.File

/** The clean-up preview: what goes, with orphaned folders unticked until the user ticks them. */
@OptIn(ExperimentalTestApi::class)
class CleanupPreviewDialogTest {

    private val orphan = File("/downloads/A (EN)/Nobody")
    private val plan = CleanupPlan(
        listOf(
            CleanupItem(File("/downloads/A (EN)/Entry/Chapter 1_tmp"), CleanupReason.UNFINISHED, setOf(1), 2_000),
            CleanupItem(File("/downloads/A (EN)/Entry/Chapter 2.cbz"), CleanupReason.READ, setOf(1), 3_000),
            CleanupItem(orphan, CleanupReason.ORPHAN_FOLDER, emptySet(), 5_000),
        ),
    )

    @Test
    fun `shows the counts and deletes no orphaned folder unless it is ticked`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<Set<File>>()
        setContent { CleanupPreviewDialog(plan, canTrash = true, onDismiss = {}) { confirmed += it } }

        onNodeWithText(localized(DMR.strings.cleanup_preview_unfinished, 1, formatSize(2_000))).assertExists()
        onNodeWithText(localized(DMR.strings.cleanup_preview_read, 1, formatSize(3_000))).assertExists()
        onNodeWithText(localized(DMR.strings.cleanup_preview_trash)).assertExists()
        onNodeWithText(localized(MR.strings.action_delete)).performClick()
        confirmed shouldBe listOf(emptySet())

        onNodeWithText("A (EN)/Nobody (${formatSize(5_000)})").performClick()
        onNodeWithText(localized(MR.strings.action_delete)).performClick()
        confirmed.last() shouldBe setOf(orphan)
    }

    @Test
    fun `says when deleted files cannot be recovered, and deletes nothing when only orphans are listed`() = runDesktopComposeUiTest {
        val onlyOrphan = CleanupPlan(plan.orphans)
        setContent { CleanupPreviewDialog(onlyOrphan, canTrash = false, onDismiss = {}) {} }

        onNodeWithText(localized(DMR.strings.cleanup_preview_permanent)).assertExists()
        onNodeWithText(localized(MR.strings.action_delete)).assertIsNotEnabled()
    }
}
