package zoukan.desktop.download

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Which chapters "Download ahead" queues while reading, as Android's
 * ReaderViewModel.downloadNextChapters: nothing unless the next chapter is downloaded already,
 * otherwise the next [amount] unread chapters, grouped by the entry that owns them.
 */
class DownloadAhead(private val isDownloaded: (Manga, Chapter) -> Boolean) {

    suspend fun chaptersToQueue(
        readerManga: Manga,
        next: Chapter,
        navigable: List<Chapter>,
        amount: Int,
        mangaOf: suspend (Chapter) -> Manga?,
    ): List<Pair<Manga, List<Chapter>>> {
        val nextManga = mangaOf(next) ?: return emptyList()
        if (!isDownloaded(nextManga, next)) return emptyList()
        val from = navigable.indexOfFirst { it.id == next.id }
        val chapters = navigable.drop(from).filter { !it.read }.take(amount)
        return chapters.groupBy { it.mangaId }.mapNotNull { (_, list) ->
            val manga = mangaOf(list.first()) ?: return@mapNotNull null
            manga to list
        }
    }
}
