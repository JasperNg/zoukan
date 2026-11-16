package zoukan.desktop.domain

import exh.source.MERGED_SOURCE_ID
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * Chapters grouped by the entry they belong to: the entry itself, or for a merged entry the
 * entries it merges, which own the chapters and their downloads, as on Android.
 *
 * With [cache] the merged entries are remembered for the life of this instance (a screen model
 * that lives as long as one entry's screen); without it each call reads them again.
 */
class ChaptersByEntry(private val getManga: GetManga, private val cache: Boolean = false) {

    private val entries = ConcurrentHashMap<Long, Manga>()

    /** The same grouping, remembering the merged entries it reads. */
    fun cached() = ChaptersByEntry(getManga, cache = true)

    suspend fun group(manga: Manga, chapters: List<Chapter>): List<Pair<Manga, List<Chapter>>> {
        if (manga.source != MERGED_SOURCE_ID) return listOf(manga to chapters)
        return chapters.groupBy { it.mangaId }.mapNotNull { (id, list) ->
            val entry = if (cache) {
                entries[id] ?: getManga.await(id)?.also { entries[id] = it }
            } else {
                getManga.await(id)
            }
            entry?.let { it to list }
        }
    }
}
