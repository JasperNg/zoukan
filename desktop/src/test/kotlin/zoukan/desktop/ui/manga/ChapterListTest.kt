package zoukan.desktop.ui.manga

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga

/**
 * The chapter list renders from plain values and callbacks: no screen model, no Injekt.
 */
@OptIn(ExperimentalTestApi::class)
class ChapterListTest {

    private fun chapter(id: Long, read: Boolean = false, bookmark: Boolean = false) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        name = "Chapter $id",
        chapterNumber = id.toDouble(),
        read = read,
        bookmark = bookmark,
    )

    private class Calls {
        val opened = mutableListOf<Long>()
        val markRead = mutableListOf<Pair<List<Long>, Boolean>>()
        val toggled = mutableListOf<Long>()
        val callbacks = ChapterListCallbacks(
            onOpen = { opened += it.id },
            onToggleSelect = { toggled += it.id },
            onMarkRead = { chapters, read -> markRead += chapters.map { it.id } to read },
            onBookmark = { _, _ -> },
            onMarkPreviousRead = {},
            onDownload = {},
            onCancelDownload = {},
            onDeleteDownload = {},
            onDeleteSelected = {},
            onClearSelection = {},
        )
    }

    @Test
    fun `renders read, bookmarked and downloaded chapters with their callbacks`() = runDesktopComposeUiTest {
        val read = chapter(1, read = true)
        val bookmarked = chapter(2, bookmark = true)
        val downloaded = chapter(3)
        val calls = Calls()
        var selected = setOf<Long>()
        setContent {
            ChapterList(
                chapters = listOf(read, bookmarked, downloaded),
                downloaded = setOf(downloaded.id),
                downloads = emptyMap(),
                selected = selected,
                displayMode = Manga.CHAPTER_DISPLAY_NAME,
                swipeStart = ChapterSwipeAction.ToggleRead,
                swipeEnd = ChapterSwipeAction.Disabled,
                callbacks = calls.callbacks,
            )
        }

        listOf("Chapter 1", "Chapter 2", "Chapter 3").forEach { onNodeWithText(it).assertIsDisplayed() }

        onNodeWithText("Chapter 2").performClick()
        calls.opened shouldBe listOf(2L)

        // A swipe towards the start runs the configured action on that chapter
        onNodeWithText("Chapter 3").performTouchInput { swipeLeft() }
        waitForIdle()
        calls.markRead shouldBe listOf(listOf(3L) to true)
    }

    @Test
    fun `a right click on a selected chapter acts on the whole selection`() = runDesktopComposeUiTest {
        val chapters = listOf(chapter(1), chapter(2), chapter(3))
        val calls = Calls()
        setContent {
            ChapterList(
                chapters = chapters,
                downloaded = emptySet(),
                downloads = emptyMap(),
                selected = setOf(1L, 2L),
                displayMode = Manga.CHAPTER_DISPLAY_NAME,
                swipeStart = ChapterSwipeAction.Disabled,
                swipeEnd = ChapterSwipeAction.Disabled,
                callbacks = calls.callbacks,
            )
        }

        onNodeWithText("Chapter 1").performMouseInput { rightClick() }
        waitForIdle()
        onNodeWithText("Mark as read").performClick()
        calls.markRead shouldBe listOf(listOf(1L, 2L) to true)
    }
}
