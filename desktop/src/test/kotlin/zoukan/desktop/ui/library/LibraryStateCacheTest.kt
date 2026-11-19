package zoukan.desktop.ui.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** Pure projection computes deterministic, immutable derived lists without mutating state. */
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

    private val input = LibraryViewInput(
        items = listOf(entry(1, "b", listOf(1L)), entry(2, "a", listOf(1L)), entry(3, "c", listOf(2L)), entry(2, "a", listOf(2L))),
        categories = listOf(Category(1, "One", 1, 0, false), Category(2, "Two", 2, 0, false)),
    )

    @Test
    fun `visible, tabs and counts are deterministic for a given input`() {
        val first = LibraryViewProjector.project(input, withCounts = true)
        val second = LibraryViewProjector.project(input, withCounts = true)

        first shouldBe second
        first.countIn(1L) shouldBe 2
        first.countIn(null) shouldBe 3
        first.entryCount shouldBe 3
    }

    @Test
    fun `a changed input recomputes what it shows without mutating prior projections`() {
        val initial = LibraryViewProjector.project(input, withCounts = true)
        initial.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)

        val filtered = LibraryViewProjector.project(input.copy(selectedCategory = 2L), withCounts = true)

        filtered.visible.map { it.id } shouldContainExactly listOf(2L, 3L)
        filtered.countIn(2L) shouldBe 2
        initial.visible.map { it.id } shouldContainExactly listOf(2L, 1L, 3L)
    }
}
