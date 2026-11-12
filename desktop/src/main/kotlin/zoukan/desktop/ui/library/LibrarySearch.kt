package zoukan.desktop.ui.library

import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import exh.source.MANGADEX_IDS
import exh.source.isMetadataSource
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import kotlin.coroutines.coroutineContext

/**
 * The library search, as Android's LibraryScreenModel.filterLibrary and filterManga (SY): SY's
 * query syntax through [SearchEngine] (quotes, `-` to exclude, `namespace:tag` against the gallery
 * tags of E-Hentai and other metadata sources), `id:` and `src:` (`src:local` for local entries),
 * tracker names and statuses, and the "mangadex-dmca" query (AZ).
 */
class LibrarySearch(
    private val searchEngine: SearchEngine = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata = Injekt.get(),
    private val getSearchTags: GetSearchTags = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
) {

    /** The ids of the entries in [unfiltered] that match [query]. */
    suspend fun search(unfiltered: List<LibraryManga>, query: String, tracksByManga: Map<Long, List<Track>>): Set<Long> {
        if (unfiltered.isEmpty() || query.isBlank()) return unfiltered.mapTo(HashSet()) { it.id }
        // AZ -->
        if (query.trim().lowercase() == "mangadex-dmca") {
            // Special easter egg query
            return unfiltered.filter {
                it.manga.source in MANGADEX_IDS &&
                    it.manga.url.removePrefix("/manga/").lowercase() in mangaDexDmcaUuids
            }.mapTo(HashSet()) { it.id }
        }
        // AZ <--
        val parsedQuery = searchEngine.parseQuery(query)
        val mangaWithMetaIds = getIdsOfFavoriteMangaWithMetadata.await().toHashSet()
        val loggedInTrackServices = trackerManager.loggedInTrackers().isNotEmpty()
        val tracks = if (loggedInTrackServices) tracksByManga else emptyMap()
        val sourceNames = unfiltered.map { it.manga.source }.distinct()
            .mapNotNull { id -> sourceManager.get(id)?.let { id to it.name } }
            .toMap()
        return unfiltered.distinctBy { it.id }.filter { item ->
            coroutineContext.ensureActive()
            val mangaId = item.manga.id
            if (query.startsWith("id:", true)) {
                return@filter mangaId == query.substringAfter("id:").toLongOrNull()
            }
            val sourceId = item.manga.source
            if (query.startsWith("src:", true)) {
                val querySource = query.substringAfter("src:")
                return@filter if (querySource.equals(LOCAL_SOURCE_ID_ALIAS, ignoreCase = true)) {
                    sourceId == LocalSource.ID
                } else {
                    sourceId == querySource.toLongOrNull()
                }
            }
            if (isMetadataSource(sourceId) && mangaId in mangaWithMetaIds) {
                filterManga(
                    queries = parsedQuery,
                    libraryManga = item,
                    tracks = tracks[mangaId],
                    sourceName = sourceNames[sourceId],
                    checkGenre = false,
                    searchTags = getSearchTags.await(mangaId),
                    searchTitles = getSearchTitles.await(mangaId),
                    loggedInTrackServices = loggedInTrackServices,
                )
            } else {
                // No meta? Filter using title
                filterManga(
                    queries = parsedQuery,
                    libraryManga = item,
                    tracks = tracks[mangaId],
                    sourceName = sourceNames[sourceId],
                    loggedInTrackServices = loggedInTrackServices,
                )
            }
        }.mapTo(HashSet()) { it.id }
    }

    private fun filterManga(
        queries: List<QueryComponent>,
        libraryManga: LibraryManga,
        tracks: List<Track>?,
        sourceName: String?,
        checkGenre: Boolean = true,
        searchTags: List<SearchTag>? = null,
        searchTitles: List<SearchTitle>? = null,
        loggedInTrackServices: Boolean,
    ): Boolean {
        val manga = libraryManga.manga
        val sourceIdString = manga.source.takeUnless { it == LocalSource.ID }?.toString()
        val genre = if (checkGenre) manga.genre.orEmpty() else emptyList()
        return queries.all { queryComponent ->
            when (queryComponent.excluded) {
                false -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        manga.title.contains(query, true) ||
                            (manga.author?.contains(query, true) == true) ||
                            (manga.artist?.contains(query, true) == true) ||
                            (manga.description?.contains(query, true) == true) ||
                            (sourceName?.contains(query, true) == true) ||
                            (sourceIdString != null && sourceIdString == query) ||
                            (loggedInTrackServices && tracks != null && filterTracks(query, tracks)) ||
                            (genre.any { it.contains(query, true) }) ||
                            (searchTags?.any { it.name.contains(query, true) } == true) ||
                            (searchTitles?.any { it.title.contains(query, true) } == true)
                    }
                    is Namespace -> {
                        searchTags != null &&
                            searchTags.any {
                                val tag = queryComponent.tag
                                (
                                    it.namespace.equals(queryComponent.namespace, true) &&
                                        tag?.run { it.name.contains(tag.asQuery(), true) } == true
                                    ) ||
                                    (tag == null && it.namespace.equals(queryComponent.namespace, true))
                            }
                    }
                    else -> true
                }
                true -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        query.isBlank() ||
                            (
                                (!manga.title.contains(query, true)) &&
                                    (manga.author?.contains(query, true) != true) &&
                                    (manga.artist?.contains(query, true) != true) &&
                                    (manga.description?.contains(query, true) != true) &&
                                    (sourceName?.contains(query, true) != true) &&
                                    (sourceIdString != null && sourceIdString != query) &&
                                    (!loggedInTrackServices || tracks == null || !filterTracks(query, tracks)) &&
                                    (!genre.any { it.contains(query, true) }) &&
                                    (searchTags?.any { it.name.contains(query, true) } != true) &&
                                    (searchTitles?.any { it.title.contains(query, true) } != true)
                                )
                    }
                    is Namespace -> {
                        val searchedTag = queryComponent.tag?.asQuery()
                        searchTags == null ||
                            (queryComponent.namespace.isBlank() && searchedTag.isNullOrBlank()) ||
                            searchTags.all { mangaTag ->
                                if (queryComponent.namespace.isBlank() && !searchedTag.isNullOrBlank()) {
                                    !mangaTag.name.contains(searchedTag, true)
                                } else if (searchedTag.isNullOrBlank()) {
                                    mangaTag.namespace == null ||
                                        !mangaTag.namespace.equals(queryComponent.namespace, true)
                                } else if (mangaTag.namespace.isNullOrBlank()) {
                                    true
                                } else {
                                    !mangaTag.name.contains(searchedTag, true) ||
                                        !mangaTag.namespace.equals(queryComponent.namespace, true)
                                }
                            }
                    }
                    else -> true
                }
            }
        }
    }

    private fun filterTracks(constraint: String, tracks: List<Track>): Boolean {
        return tracks.any { track ->
            val trackService = trackerManager.get(track.trackerId)
            if (trackService != null) {
                val status = trackService.getStatus(track.status)?.let { localized(it) }
                val name = trackService.name
                status?.contains(constraint, true) == true || name.contains(constraint, true)
            } else {
                false
            }
        }
    }

    companion object {
        /** `src:local` finds local entries, as Android's LibraryItem */
        const val LOCAL_SOURCE_ID_ALIAS = "local"

        /** The MangaDex ids under DMCA takedowns (KMK's res/raw/mangadex_dmca_uuids.txt). */
        val mangaDexDmcaUuids: Set<String> by lazy {
            runCatching {
                LibrarySearch::class.java.getResourceAsStream("/mangadex_dmca_uuids.txt")!!.bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toHashSet()
                }
            }.getOrElse { emptySet() }
        }
    }
}
