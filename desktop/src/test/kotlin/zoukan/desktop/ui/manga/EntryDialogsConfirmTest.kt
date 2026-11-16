package zoukan.desktop.ui.manga

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * The entry page's dialogs that only report a choice: each confirm button passes on exactly what
 * the user checked or typed. Clear entry matters most, as its flags delete downloads and local files.
 */
@OptIn(ExperimentalTestApi::class)
class EntryDialogsConfirmTest {

    private data class Clear(val deleteDownload: Boolean, val removeChapters: Boolean, val deleteLocalFiles: Boolean)

    private fun ComposeUiTest.row(label: String): SemanticsNodeInteraction = onNode(hasText(label) and isToggleable())

    private fun clearWith(vararg labels: (String) -> String): List<Clear> {
        val confirmed = mutableListOf<Clear>()
        runDesktopComposeUiTest {
            setContent {
                ClearMangaDialog(
                    onDismissRequest = {},
                    showDownloadedData = true,
                    localFolders = listOf("Alpha"),
                    onConfirm = { download, chapters, local -> confirmed += Clear(download, chapters, local) },
                )
            }
            labels.forEach { row(it("Alpha")).performClick() }
            onNodeWithText(localized(MR.strings.action_ok)).performClick()
            waitForIdle()
        }
        return confirmed
    }

    private val downloadedData: (String) -> String = { localized(KMR.strings.downloaded_data) }
    private val chaptersFromDatabase: (String) -> String = { localized(KMR.strings.chapters_from_database) }
    private val localFiles: (String) -> String = { localized(DMR.strings.clear_local_files, it) }

    @Test
    fun `clear entry deletes only the downloads when only downloaded data is checked`() {
        clearWith(downloadedData) shouldBe listOf(Clear(deleteDownload = true, removeChapters = false, deleteLocalFiles = false))
    }

    @Test
    fun `clear entry removes only the chapters when only chapters from database is checked`() {
        clearWith(chaptersFromDatabase) shouldBe listOf(Clear(deleteDownload = false, removeChapters = true, deleteLocalFiles = false))
    }

    @Test
    fun `clear entry deletes only the local files when only the local folder is checked`() {
        clearWith(localFiles) shouldBe listOf(Clear(deleteDownload = false, removeChapters = false, deleteLocalFiles = true))
    }

    @Test
    fun `custom download passes on the typed count`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<Int>()
        setContent { CustomDownloadDialog(onConfirm = { confirmed += it }, onDismiss = {}) }
        onNode(hasSetTextAction()).performTextInput("3")
        onNodeWithText(localized(MR.strings.action_download)).performClick()
        waitForIdle()
        confirmed shouldBe listOf(3)
    }

    private val categories = listOf(
        Category(id = 1, name = "Reading", order = 0, flags = 0, hidden = false),
        Category(id = 2, name = "Later", order = 1, flags = 0, hidden = false),
        Category(id = 3, name = "Done", order = 2, flags = 0, hidden = false),
    )

    @Test
    fun `add to categories passes on the checked categories`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<List<Long>>()
        setContent { CategoryDialog(categories, onConfirm = { confirmed += it }, onDismiss = {}) }
        row("Later").performClick()
        row("Done").performClick()
        onNodeWithText(localized(MR.strings.action_add)).performClick()
        waitForIdle()
        confirmed.single().sorted() shouldBe listOf(2L, 3L)
    }

    @Test
    fun `change categories starts with the entry's own categories checked and keeps them on OK`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<Set<Long>>()
        setContent { ChangeCategoryDialog(categories, chosen = setOf(1L, 3L), onConfirm = { confirmed += it }, onDismiss = {}) }
        row("Reading").assertIsOn()
        row("Later").assertIsOff()
        row("Done").assertIsOn()
        onNodeWithText(localized(MR.strings.action_ok)).performClick()
        waitForIdle()
        confirmed shouldBe listOf(setOf(1L, 3L))
    }

    @Test
    fun `change categories applies a checked and an unchecked category`() = runDesktopComposeUiTest {
        val confirmed = mutableListOf<Set<Long>>()
        setContent { ChangeCategoryDialog(categories, chosen = setOf(1L), onConfirm = { confirmed += it }, onDismiss = {}) }
        row("Reading").performClick()
        row("Later").performClick()
        onNodeWithText(localized(MR.strings.action_ok)).performClick()
        waitForIdle()
        confirmed shouldBe listOf(setOf(2L))
    }
}
