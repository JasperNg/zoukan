package zoukan.desktop.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import exh.source.LIBRARY_UPDATE_EXCLUDED_SOURCES
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import java.nio.file.Files

/**
 * The library update leaves out the sources Android leaves out, and queues a merged entry's new
 * chapters under the entries it merges, as Android's LibraryUpdateJob.
 */
class LibraryUpdaterDownloadsTest {

    private val excludedBefore = LIBRARY_UPDATE_EXCLUDED_SOURCES

    @BeforeEach
    fun setUp() {
        LIBRARY_UPDATE_EXCLUDED_SOURCES = listOf(EH)
    }

    @AfterEach
    fun tearDown() {
        LIBRARY_UPDATE_EXCLUDED_SOURCES = excludedBefore
    }

    private fun manga(id: Long, source: Long) = mockk<Manga> {
        every { this@mockk.id } returns id
        every { title } returns "M$id"
        every { this@mockk.source } returns source
        every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
        every { favorite } returns true
    }

    private fun entry(manga: Manga) = LibraryManga(
        manga = manga,
        categories = emptyList(),
        totalChapters = 1,
        readCount = 1,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    private fun chapter(id: Long, mangaId: Long) = Chapter.create().copy(id = id, mangaId = mangaId, sourceOrder = id)

    @Test
    fun `excluded sources are not updated and merged chapters are queued under their entries`() = runBlocking<Unit> {
        val merged = manga(1, MERGED_SOURCE_ID)
        val gallery = manga(2, EH)
        val childA = manga(10, 100)
        val childB = manga(11, 101)
        val library = listOf(entry(merged), entry(gallery))
        val newChapters = listOf(chapter(1, 10), chapter(2, 11), chapter(3, 10))

        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(merged, any(), any(), any(), any(), any()) } returns RefreshManga.Result(merged, newChapters)
        }
        val downloads = mockk<DownloadManager>(relaxed = true)
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(Files.createTempDirectory("zoukan-update-downloads").toFile()),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library },
            refreshManga = refreshManga,
            sourceManager = mockk<SourceManager> { every { this@mockk.get(any<Long>()) } returns mockk() },
            preferences = mockk<LibraryPreferences> {
                every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true) { every { get() } returns 0L }
                every { autoUpdateMetadata() } returns mockk<Preference<Boolean>> { every { get() } returns false }
                every { newUpdatesCount() } returns mockk<Preference<Int>>(relaxed = true)
            },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk<FilterChaptersForDownload> { coEvery { await(any(), any()) } answers { secondArg() } },
            downloadManager = downloads,
            errors = mockk(relaxed = true),
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { library.first { it.id == firstArg<Long>() }.manga } },
            getMergedMangaForDownloading = mockk<GetMergedMangaForDownloading> { coEvery { await(1L) } returns listOf(childA, childB) },
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        val result = updater.run(mangaIds = setOf(1L, 2L))

        result.entries.map { it.id } shouldContainExactly listOf(1L)
        result.newChapters shouldBe 3
        coVerify(exactly = 0) { refreshManga.invoke(gallery, any(), any(), any(), any(), any()) }
        verify(exactly = 0) { downloads.enqueue(merged, any(), any()) }
        verify { downloads.enqueue(childA, match { list -> list.map { it.id }.sorted() == listOf(1L, 3L) }, false) }
        verify { downloads.enqueue(childB, match { list -> list.map { it.id } == listOf(2L) }, false) }
        // The downloads start once the update ends
        verify(exactly = 1) { downloads.start() }
    }

    private companion object {
        const val EH = 6900L
    }
}
