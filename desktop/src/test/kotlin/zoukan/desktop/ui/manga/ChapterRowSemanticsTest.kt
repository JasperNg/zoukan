package zoukan.desktop.ui.manga

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.reader.ReaderChapterListDialog
import zoukan.i18n.desktop.DMR

/** A screen reader hears whether a chapter is read or bookmarked, not only a fade and an icon. */
@OptIn(ExperimentalTestApi::class)
class ChapterRowSemanticsTest {

    private val readAndBookmarked = Chapter.create().copy(
        id = 1,
        mangaId = 1,
        name = "Chapter 1",
        chapterNumber = 1.0,
        read = true,
        bookmark = true,
    )
    private val plain = Chapter.create().copy(id = 2, mangaId = 1, name = "Chapter 2", chapterNumber = 2.0)

    private val readAndBookmarkedState = SemanticsMatcher("read and bookmarked") { node ->
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let {
            localized(DMR.strings.chapter_state_read) in it && localized(DMR.strings.chapter_state_bookmarked) in it
        } == true
    }

    @Test
    fun `a read, bookmarked chapter in the manga list says so to a screen reader`() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                ChapterList(
                    chapters = listOf(readAndBookmarked, plain),
                    downloaded = emptySet(),
                    downloads = emptyMap(),
                    selected = emptySet(),
                    displayMode = Manga.CHAPTER_DISPLAY_NAME,
                    swipeStart = ChapterSwipeAction.Disabled,
                    swipeEnd = ChapterSwipeAction.Disabled,
                    callbacks = ChapterListCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        onNode(hasText("Chapter 1")).assert(readAndBookmarkedState)
        onNode(hasText("Chapter 2")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `a read, bookmarked chapter in the reader's chapter list says so to a screen reader`() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                ReaderChapterListDialog(listOf(readAndBookmarked, plain), current = 2L, onOpen = {}, onBookmark = {}, onDismiss = {})
            }
        }
        onNode(hasText("Chapter 1")).assert(readAndBookmarkedState)
        onNode(hasText("Chapter 2")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
