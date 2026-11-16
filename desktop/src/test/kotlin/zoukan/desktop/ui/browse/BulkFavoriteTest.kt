package zoukan.desktop.ui.browse

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import zoukan.desktop.library.AddToLibrary

/** Adding a bulk selection to the library goes through the same path as adding one entry. */
class BulkFavoriteTest {

    private fun manga(id: Long, title: String, favorite: Boolean, dateAdded: Long = 0) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { source } returns 10L
        every { this@mockk.title } returns title
        every { this@mockk.favorite } returns favorite
        every { this@mockk.dateAdded } returns dateAdded
    }

    private val addToLibrary = mockk<AddToLibrary>(relaxed = true)
    private val getManga = mockk<GetManga>()
    private val getCategories = mockk<GetCategories>()
    private val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)

    private fun adder() = BulkLibraryAdd(
        addToLibrary = addToLibrary,
        getManga = getManga,
        getCategories = getCategories,
        setMangaCategories = setMangaCategories,
    )

    init {
        coEvery { getCategories.await(any<Long>()) } returns emptyList()
    }

    @Test
    fun `bulk add fetches each new entry as a single add does`() = runBlocking<Unit> {
        val a = manga(1, "Alpha", favorite = false)
        val b = manga(2, "Bravo", favorite = false)
        coEvery { getManga.await(1) } returns a
        coEvery { getManga.await(2) } returns b

        adder().add(listOf(a, b), listOf(5L)) shouldBe 2

        // getManga hands back the same instances, so the calls are matched by them
        coVerify { addToLibrary.add(a, listOf(5L), fetchOnAdd = true) }
        coVerify { addToLibrary.add(b, listOf(5L), fetchOnAdd = true) }
    }

    @Test
    fun `an entry added since the page loaded keeps its date added`() = runBlocking<Unit> {
        val snapshot = manga(1, "Alpha", favorite = false)
        coEvery { getManga.await(1) } returns manga(1, "Alpha", favorite = true, dateAdded = 1000)

        adder().add(listOf(snapshot), listOf(5L)) shouldBe 0

        coVerify(exactly = 0) { addToLibrary.add(any(), any(), any()) }
        coVerify { setMangaCategories.await(1, listOf(5L)) }
    }

    @Test
    fun `a duplicate the library check finds under another spelling is reported`() = runBlocking<Unit> {
        val a = manga(1, "Alpha", favorite = false)
        val b = manga(2, "Bravo", favorite = false)
        coEvery { addToLibrary.duplicates(match { it.id == 1L }) } returns listOf(MangaWithChapterCount(manga(9, "alpha!", favorite = true), 3))
        coEvery { addToLibrary.duplicates(match { it.id == 2L }) } returns emptyList()

        adder().duplicates(listOf(a, b)).map { it.id } shouldBe listOf(1L)
    }
}
