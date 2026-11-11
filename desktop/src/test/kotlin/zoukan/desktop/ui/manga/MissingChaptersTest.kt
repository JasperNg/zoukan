package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MissingChaptersTest {
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
