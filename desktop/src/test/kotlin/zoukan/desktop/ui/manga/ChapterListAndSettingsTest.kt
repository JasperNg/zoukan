package zoukan.desktop.ui.manga

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import eu.kanade.presentation.util.formatChapterNumber
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.reader.ReaderChapterListDialog
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.chapter_state_bookmarked
import zoukan.i18n.desktop.chapter_state_read

/**
 * Tests for the chapter list UI, accessibility semantics, [ChapterSettings] sorting/filtering,
 * and [MissingChapters] gap detection.
 */
@OptIn(ExperimentalTestApi::class)
class ChapterListAndSettingsTest {

    /** The chapter list renders from plain values and callbacks: no screen model, no Injekt. */
    @Nested
    inner class ChapterListRendering {

        private fun chapter(id: Long, read: Boolean = false, bookmark: Boolean = false) = Chapter.create().copy(
            id = id,
            mangaId = 1,
            name = "Chapter $id",
            chapterNumber = id.toDouble(),
            read = read,
            bookmark = bookmark,
        )

        private inner class Calls {
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

    /** A screen reader hears whether a chapter is read or bookmarked, not only a fade and an icon. */
    @Nested
    inner class ChapterRowAccessibility {

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

    @Nested
    inner class ChapterSettingsRules {

        // Sources list the newest chapter first: chapter 3 has source order 0
        private val c1 = chapter(1, number = 1.0, upload = 300, scanlator = "A", read = true)
        private val c2 = chapter(2, number = 2.0, upload = 100, scanlator = "B", bookmark = true)
        private val c3 = chapter(3, number = 3.0, upload = 200, scanlator = null)
        private val chapters = listOf(c1, c2, c3)

        private fun chapter(
            id: Long,
            number: Double,
            upload: Long,
            scanlator: String?,
            read: Boolean = false,
            bookmark: Boolean = false,
        ) = Chapter.create().copy(
            id = id,
            name = "Chapter $id",
            chapterNumber = number,
            sourceOrder = 3 - id,
            dateUpload = upload,
            scanlator = scanlator,
            read = read,
            bookmark = bookmark,
        )

        private fun manga(flags: Long) = Manga.create().copy(id = 1, chapterFlags = flags)

        private fun apply(
            flags: Long,
            excluded: Set<String> = emptySet(),
            downloaded: Set<Long> = emptySet(),
            isLocal: Boolean = false,
            downloadedOnly: Boolean = false,
        ) = ChapterSettings.apply(manga(flags), chapters, excluded, downloaded, isLocal, downloadedOnly).map { it.id }

        @Test
        fun `default settings list chapters newest first in source order`() {
            apply(0) shouldBe listOf(3L, 2L, 1L)
            apply(Manga.CHAPTER_SORT_ASC) shouldBe listOf(1L, 2L, 3L)
        }

        @Test
        fun `sorts by chapter number and upload date in either direction`() {
            apply(Manga.CHAPTER_SORTING_NUMBER) shouldBe listOf(3L, 2L, 1L)
            apply(Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC) shouldBe listOf(1L, 2L, 3L)
            apply(Manga.CHAPTER_SORTING_UPLOAD_DATE) shouldBe listOf(1L, 3L, 2L)
            apply(Manga.CHAPTER_SORTING_UPLOAD_DATE or Manga.CHAPTER_SORT_ASC) shouldBe listOf(2L, 3L, 1L)
        }

        @Test
        fun `filters unread, bookmarked and downloaded chapters`() {
            apply(Manga.CHAPTER_SHOW_UNREAD) shouldBe listOf(3L, 2L)
            apply(Manga.CHAPTER_SHOW_READ) shouldBe listOf(1L)
            apply(Manga.CHAPTER_SHOW_BOOKMARKED) shouldBe listOf(2L)
            apply(Manga.CHAPTER_SHOW_NOT_BOOKMARKED) shouldBe listOf(3L, 1L)
            apply(Manga.CHAPTER_SHOW_DOWNLOADED, downloaded = setOf(1L)) shouldBe listOf(1L)
            apply(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, downloaded = setOf(1L)) shouldBe listOf(3L, 2L)
        }

        @Test
        fun `Downloaded only forces the downloaded filter on`() {
            apply(0, downloaded = setOf(2L), downloadedOnly = true) shouldBe listOf(2L)
            apply(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, downloaded = setOf(2L), downloadedOnly = true) shouldBe listOf(2L)
            ChapterSettings.filtered(manga(0), downloadedOnly = true) shouldBe true
        }

        @Test
        fun `a local entry's chapters all count as downloaded`() {
            apply(Manga.CHAPTER_SHOW_DOWNLOADED, isLocal = true) shouldBe listOf(3L, 2L, 1L)
            apply(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, isLocal = true) shouldBe emptyList()
        }

        @Test
        fun `hides excluded scanlators but keeps chapters without one`() {
            apply(0, excluded = setOf("A")) shouldBe listOf(3L, 2L)
            apply(0, excluded = setOf("A", "B")) shouldBe listOf(3L)
        }

        @Test
        fun `reports when a filter is on`() {
            ChapterSettings.filtered(Manga.create().copy(id = 1, chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC), downloadedOnly = false) shouldBe false
            ChapterSettings.filtered(manga(Manga.CHAPTER_SHOW_NOT_DOWNLOADED), downloadedOnly = false) shouldBe true
        }

        @Test
        fun `the Display tab's chapter numbers read as Android's`() {
            formatChapterNumber(12.0) shouldBe "12"
            formatChapterNumber(12.5) shouldBe "12.5"
            formatChapterNumber(1.25) shouldBe "1.25"
        }
    }

    @Nested
    inner class MissingChaptersRules {

        private fun chapter(id: Long, number: Double) = Chapter.create().copy(id = id, chapterNumber = number)

        // Chapters 1, 2, 5, 6.5 and 10: 3 and 4 are missing, then 7 to 9
        private val ascending = listOf(chapter(1, 1.0), chapter(2, 2.0), chapter(5, 5.0), chapter(6, 6.5), chapter(10, 10.0))

        @Test
        fun `gaps sit above the chapter after them, newest first`() {
            val missing = MissingChapters.of(ascending.reversed(), descending = true)
            missing.before shouldBe mapOf(6L to 3, 2L to 2)
            missing.end shouldBe 0
        }

        @Test
        fun `gaps sit above the chapter after them, oldest first`() {
            val missing = MissingChapters.of(ascending, descending = false)
            missing.before shouldBe mapOf(5L to 2, 10L to 3)
            missing.end shouldBe 0
        }

        @Test
        fun `chapters below the lowest one count from its number`() {
            val list = listOf(chapter(4, 4.0), chapter(5, 5.0))
            MissingChapters.of(list.reversed(), descending = true).end shouldBe 3
            MissingChapters.of(list, descending = false).before shouldBe mapOf(4L to 3)
        }

        @Test
        fun `unknown chapter numbers make no gaps`() {
            val list = listOf(chapter(1, -1.0), chapter(2, 7.0), chapter(3, -1.0))
            MissingChapters.of(list, descending = false) shouldBe MissingChapters(emptyMap(), 0)
        }
    }
}
