package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class DownloadAheadTest {

    private fun chapter(id: Long, read: Boolean = false, manga: Long = 1) =
        Chapter.create().copy(id = id, mangaId = manga, chapterNumber = id.toDouble(), read = read)

    private val mangas = mapOf(1L to Manga.create().copy(id = 1), 2L to Manga.create().copy(id = 2))
    private val readerManga = mangas.getValue(1)
    private val mangaOf: suspend (Chapter) -> Manga? = { mangas[it.mangaId] }

    private fun queue(
        navigable: List<Chapter>,
        nextId: Long,
        amount: Int,
        downloaded: Set<Long>,
    ) = runBlocking {
        DownloadAhead { _, c -> c.id in downloaded }
            .chaptersToQueue(readerManga, navigable.first { it.id == nextId }, navigable, amount, mangaOf)
    }

    @Test
    fun `nothing is queued when the next chapter is not downloaded`() {
        queue((1L..5L).map { chapter(it) }, nextId = 2, amount = 2, downloaded = emptySet()).shouldBeEmpty()
    }

    @Test
    fun `only unread chapters are queued`() {
        val navigable = listOf(chapter(1), chapter(2), chapter(3, read = true), chapter(4), chapter(5))
        val result = queue(navigable, nextId = 2, amount = 2, downloaded = setOf(2))
        result.single().second.map { it.id } shouldContainExactly listOf(2L, 4L)
    }

    @Test
    fun `chapters are grouped by entry for a merged entry`() {
        val navigable = listOf(chapter(1, manga = 1), chapter(2, manga = 1), chapter(3, manga = 2), chapter(4, manga = 2))
        val result = queue(navigable, nextId = 2, amount = 3, downloaded = setOf(2))
        result.map { it.first.id to it.second.map { c -> c.id } } shouldContainExactly
            listOf(1L to listOf(2L), 2L to listOf(3L, 4L))
    }
}
