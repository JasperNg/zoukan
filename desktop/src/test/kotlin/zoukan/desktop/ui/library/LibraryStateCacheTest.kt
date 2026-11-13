package zoukan.desktop.ui.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The library's derived lists are computed once per state, and a new state computes its own. */
class LibraryStateCacheTest {

    private fun entry(id: Long, title: String, categories: List<Long>) = LibraryManga(
        manga = Manga.create().copy(id = id, ogTitle = title),
        categories = categories,
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private val state = LibraryScreenModel.State(
        loading = false,
        items = listOf(entry(1, "b", listOf(1L)), entry(2, "a", listOf(1L)), entry(3, "c", listOf(2L)), entry(2, "a", listOf(2L))),
        categories = listOf(Category(1, "One", 1, 0, false), Category(2, "Two", 2, 0, false)),
    )

    @Test
    fun `visible, tabs and counts are the same on every read of one state`() {
        (state.visible === state.visible) shouldBe true
        (state.tabs === state.tabs) shouldBe true
        state.countIn(1L) shouldBe 2
        state.countIn(1L) shouldBe 2
        state.countIn(null) shouldBe 3
        state.entryCount shouldBe 3
    }

    @Test
    fun `a changed state recomputes what it shows`() {
        state.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)

        val filtered = state.copy(selectedCategory = 2L)

        filtered.visible.map { it.id } shouldContainExactly listOf(2L, 3L)
        filtered.countIn(2L) shouldBe 2
        state.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)
    }
}
