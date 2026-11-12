package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterSettingsTest {

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
        ChapterSettings.filtered(manga(Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC), downloadedOnly = false) shouldBe false
        ChapterSettings.filtered(manga(Manga.CHAPTER_SHOW_NOT_DOWNLOADED), downloadedOnly = false) shouldBe true
    }

    @Test
    fun `the Display tab's chapter numbers read as Android's`() {
        eu.kanade.presentation.util.formatChapterNumber(12.0) shouldBe "12"
        eu.kanade.presentation.util.formatChapterNumber(12.5) shouldBe "12.5"
        eu.kanade.presentation.util.formatChapterNumber(1.25) shouldBe "1.25"
    }
}
