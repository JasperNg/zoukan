package zoukan.desktop.domain

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths

/** The download handling of SyncChapters, as Android's SyncChaptersWithSource. */
class SyncChaptersDownloadsTest {

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { title } returns "Manga"
        every { ogTitle } returns "Manga"
    }
    private val source = mockk<Source>(relaxed = true)

    private fun sChapter(url: String, name: String, scanlator: String? = null) = SChapter.create().apply {
        this.url = url
        this.name = name
        this.scanlator = scanlator
        date_upload = 1_700_000_000_000L
    }

    private fun sync(
        dbChapters: List<Chapter>,
        excluded: Set<String> = emptySet(),
        downloads: DownloadManager = mockk(relaxed = true),
        paths: DownloadPaths = mockk(relaxed = true),
    ): SyncChapters {
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            val added = slot<List<Chapter>>()
            coEvery { addAll(capture(added)) } answers { added.captured.mapIndexed { i, c -> c.copy(id = 100L + i) } }
        }
        val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(1L, any()) } returns dbChapters }
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true) {
            every { markDuplicateReadChapterAsRead().get() } returns emptySet()
        }
        val getExcluded = mockk<GetExcludedScanlators> { coEvery { await(1L) } returns excluded }
        return SyncChapters(
            chapterRepository,
            mockk(relaxed = true),
            ShouldUpdateDbChapter(),
            mockk(relaxed = true),
            getChapters,
            mockk(relaxed = true),
            libraryPreferences,
            downloads,
            paths,
            getExcluded,
        )
    }

    @Test
    fun `new chapters of excluded scanlators are not returned`() = runBlocking<Unit> {
        val result = sync(emptyList(), excluded = setOf("Bad group")).await(
            listOf(sChapter("/2", "Chapter 2", "Bad group"), sChapter("/1", "Chapter 1", "Good group")),
            manga,
            source,
        )
        result.map { it.url } shouldBe listOf("/1")
    }

    @Test
    fun `a downloaded chapter the source renamed is renamed on disk`() = runBlocking<Unit> {
        val old = Chapter.create().copy(id = 5, mangaId = 1, url = "/1", name = "Ch. 1", sourceOrder = 0, dateUpload = 1_700_000_000_000L, chapterNumber = 1.0)
        val downloads = mockk<DownloadManager>(relaxed = true) { every { isDownloaded(manga, old) } returns true }
        val paths = mockk<DownloadPaths> { every { isChapterDirNameChanged(old, any()) } returns true }

        sync(listOf(old), downloads = downloads, paths = paths).await(listOf(sChapter("/1", "Chapter 1")), manga, source)

        verify { downloads.renameChapter(manga, old, match { it.name == "Chapter 1" }) }
    }

    @Test
    fun `a chapter that is not downloaded is not renamed`() = runBlocking<Unit> {
        val old = Chapter.create().copy(id = 5, mangaId = 1, url = "/1", name = "Ch. 1", sourceOrder = 0, dateUpload = 1_700_000_000_000L, chapterNumber = 1.0)
        val downloads = mockk<DownloadManager>(relaxed = true) { every { isDownloaded(manga, old) } returns false }
        val paths = mockk<DownloadPaths> { every { isChapterDirNameChanged(old, any()) } returns true }

        sync(listOf(old), downloads = downloads, paths = paths).await(listOf(sChapter("/1", "Chapter 1")), manga, source)

        verify(exactly = 0) { downloads.renameChapter(any(), any(), any()) }
    }
}
