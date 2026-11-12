package zoukan.desktop.ui.library

import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.SearchEngine
import exh.source.EH_SOURCE_ID
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.local.LocalSource

class LibrarySearchTest {

    private fun entry(id: Long, source: Long, title: String, genre: List<String>? = null, author: String? = null) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source, ogTitle = title, ogGenre = genre, ogAuthor = author),
        categories = listOf(0L),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private val items = listOf(
        // An E-Hentai gallery with metadata: searched by its tags and titles, not its genres
        entry(1, EH_SOURCE_ID, "Gallery one", genre = listOf("ignored")),
        entry(2, 10, "Blue Sky", genre = listOf("Action", "Comedy"), author = "Someone"),
        entry(3, 10, "Red Moon", genre = listOf("Drama")),
        entry(4, LocalSource.ID, "Local book"),
    )

    private val search = LibrarySearch(
        searchEngine = SearchEngine(),
        sourceManager = mockk<SourceManager>().also { every { it.get(any<Long>()) } returns null },
        trackerManager = mockk<TrackerManager> { every { loggedInTrackers() } returns emptyList() },
        getIdsOfFavoriteMangaWithMetadata = mockk { coEvery { await() } returns listOf(1L) },
        getSearchTags = mockk<GetSearchTags> {
            coEvery { await(1L) } returns listOf(
                SearchTag(null, 1L, "artist", "Jane Doe", 0),
                SearchTag(null, 1L, "female", "glasses", 0),
            )
        },
        getSearchTitles = mockk<GetSearchTitles> { coEvery { await(1L) } returns listOf(SearchTitle(null, 1L, "Alt title", 0)) },
    )

    private fun find(query: String) = runBlocking { search.search(items, query, emptyMap()) }

    @Test
    fun `plain text matches titles, authors and genres`() {
        find("sky") shouldContainExactlyInAnyOrder listOf(2L)
        find("someone") shouldContainExactlyInAnyOrder listOf(2L)
        find("drama") shouldContainExactlyInAnyOrder listOf(3L)
    }

    @Test
    fun `namespaced tags match metadata tags`() {
        find("artist:jane") shouldContainExactlyInAnyOrder listOf(1L)
        find("female:glasses") shouldContainExactlyInAnyOrder listOf(1L)
        find("artist:nobody") shouldContainExactlyInAnyOrder emptyList()
    }

    @Test
    fun `metadata titles match, metadata entries skip their genres`() {
        find("alt title") shouldContainExactlyInAnyOrder listOf(1L)
        find("ignored") shouldContainExactlyInAnyOrder emptyList()
    }

    @Test
    fun `a dash excludes`() {
        // As Komikku: an excluded word never lets a local entry through (its source id check fails)
        find("-sky") shouldContainExactlyInAnyOrder listOf(1L, 3L)
        find("-artist:jane") shouldContainExactlyInAnyOrder listOf(2L, 3L, 4L)
    }

    @Test
    fun `id and src pick entries and sources`() {
        find("id:3") shouldContainExactlyInAnyOrder listOf(3L)
        find("src:10") shouldContainExactlyInAnyOrder listOf(2L, 3L)
        find("src:local") shouldContainExactlyInAnyOrder listOf(4L)
    }
}
