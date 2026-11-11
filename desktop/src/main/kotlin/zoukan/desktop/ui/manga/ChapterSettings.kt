package zoukan.desktop.ui.manga

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The chapter settings of an entry, as on Android: the unread, downloaded and bookmarked filters
 * and the sort, saved in the entry's chapter flags, and the scanlators it hides, saved in the
 * excluded_scanlators table. Android's interactors for the scanlators live in the app module, so
 * the queries are here.
 */
class ChapterSettings(
    mangaRepository: MangaRepository = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val handler: DatabaseHandler = Injekt.get(),
    getFavorites: GetFavorites = Injekt.get(),
) {
    private val setFlags = SetMangaChapterFlags(mangaRepository)
    private val setDefaultFlags = SetMangaDefaultChapterFlags(libraryPreferences, setFlags, getFavorites)

    suspend fun setUnreadFilter(manga: Manga, filter: TriState) {
        setFlags.awaitSetUnreadFilter(
            manga,
            when (filter) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
            },
        )
    }

    suspend fun setDownloadedFilter(manga: Manga, filter: TriState) {
        setFlags.awaitSetDownloadedFilter(
            manga,
            when (filter) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
            },
        )
    }

    suspend fun setBookmarkedFilter(manga: Manga, filter: TriState) {
        setFlags.awaitSetBookmarkFilter(
            manga,
            when (filter) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            },
        )
    }

    /** Sorts by [sorting] (oldest first, as on Android), or flips the order when already sorted by it */
    suspend fun setSortingOrFlip(manga: Manga, sorting: Long) {
        setFlags.awaitSetSortingModeOrFlipOrder(manga, sorting)
    }

    /** Applies the default settings (Android's "Reset") */
    suspend fun reset(manga: Manga) = setDefaultFlags.await(manga)

    /** Makes the entry's settings the default, and applies it to the whole library if [toLibrary] */
    suspend fun setAsDefault(manga: Manga, toLibrary: Boolean) {
        libraryPreferences.setChapterSettingsDefault(manga)
        if (toLibrary) setDefaultFlags.awaitAll()
    }

    fun excludedScanlators(mangaId: Long): Flow<Set<String>> =
        handler.subscribeToList { excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId) }
            .map { it.toSet() }
            .distinctUntilChanged()

    suspend fun setExcludedScanlators(mangaId: Long, excluded: Set<String>) {
        handler.await(inTransaction = true) {
            val current = excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId).executeAsList().toSet()
            (excluded - current).forEach { excluded_scanlatorsQueries.insert(mangaId, it) }
            val removed = current - excluded
            if (removed.isNotEmpty()) excluded_scanlatorsQueries.remove(mangaId, removed)
        }
    }

    companion object {
        /** The downloaded filter of [manga], forced on by More > Downloaded only, as on Android */
        fun downloadedFilter(manga: Manga, downloadedOnly: Boolean): TriState = if (downloadedOnly) {
            TriState.ENABLED_IS
        } else {
            when (manga.downloadedFilterRaw) {
            Manga.CHAPTER_SHOW_DOWNLOADED -> TriState.ENABLED_IS
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
                else -> TriState.DISABLED
            }
        }

        /** Whether the unread, downloaded or bookmarked filter is on */
        fun filtered(manga: Manga, downloadedOnly: Boolean): Boolean = manga.unreadFilter != TriState.DISABLED ||
            downloadedFilter(manga, downloadedOnly) != TriState.DISABLED ||
            manga.bookmarkedFilter != TriState.DISABLED

        /**
         * The [chapters] the list shows: without the [excluded] scanlators' chapters, filtered
         * and sorted as the entry's flags say. A local entry's chapters count as downloaded.
         */
        fun apply(
            manga: Manga,
            chapters: List<Chapter>,
            excluded: Set<String>,
            downloaded: Set<Long>,
            isLocal: Boolean,
            downloadedOnly: Boolean = false,
        ): List<Chapter> {
            val unread = manga.unreadFilter
            val bookmarked = manga.bookmarkedFilter
            val download = downloadedFilter(manga, downloadedOnly)
            return chapters.asSequence()
                .filter { it.scanlator.isNullOrBlank() || it.scanlator !in excluded }
                .filter { applyFilter(unread) { !it.read } }
                .filter { applyFilter(bookmarked) { it.bookmark } }
                .filter { applyFilter(download) { isLocal || it.id in downloaded } }
                .sortedWith(getChapterSort(manga))
                .distinctBy { it.id }
                .toList()
        }
    }
}
