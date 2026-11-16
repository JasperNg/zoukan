package zoukan.desktop.ui.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** Changing only the selection must not refilter or resort a large library. */
class LibraryScreenModelSelectionTest {

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
        items = (1L..5000L).map { entry(it, "title $it", listOf(if (it % 2 == 0L) 1L else 2L)) },
        categories = listOf(Category(1, "One", 1, 0, false), Category(2, "Two", 2, 0, false)),
    )

    @Test
    fun `a selection change keeps the shown list, tabs and counts`() {
        val visible = state.visible
        val tabs = state.tabs
        val count = state.countIn(1L)

        val selected = state.copy(selection = setOf(1L, 2L))

        (selected.visible === visible) shouldBe true
        (selected.tabs === tabs) shouldBe true
        selected.countIn(1L) shouldBe count
    }

    @Test
    fun `a selection change does not change what the state shows`() {
        state.sameView(state.copy(selection = setOf(1L))) shouldBe true
        state.sameView(state.copy(query = "x")) shouldBe false
    }

    @Test
    fun `a query or filter change still recomputes`() {
        val visible = state.visible

        state.copy(selectedCategory = 2L).visible shouldNotBe visible
        val searched = state.copy(searchResults = setOf(3L), query = "c")
        searched.visible.map { it.id } shouldBe listOf(3L)
        state.visible shouldBe visible
    }
}
