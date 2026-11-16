package zoukan.desktop.domain

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException

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
        updateAllFails: Boolean = false,
    ): SyncChapters {
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            if (updateAllFails) coEvery { updateAll(any()) } throws IOException("database is locked")
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
            getChapters,
            mockk(relaxed = true),
            libraryPreferences,
            downloads,
            paths,
            getExcluded,
            object : DatabaseHandler by mockk() {
                override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T =
                    block(mockk(relaxed = true))
            },
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

    @TempDir
    lateinit var temp: File

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    @Test
    fun `a chapter update that fails leaves the download under the name the database still has`() = runBlocking<Unit> {
        val realManga = mockk<Manga>(relaxed = true) {
            every { id } returns 1L
            every { title } returns "Manga"
            every { ogTitle } returns "Manga"
            every { this@mockk.source } returns 7L
        }
        val fakeSource = mockk<Source> { every { this@mockk.toString() } returns "Fake (EN)" }
        val sourceManager = mockk<SourceManager> { every { getOrStub(7L) } returns fakeSource }
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))

        val old = Chapter.create().copy(id = 5, mangaId = 1, url = "/1", name = "Ch. 1", sourceOrder = 0, dateUpload = 1_700_000_000_000L, chapterNumber = 1.0)
        val dir = paths.mangaDir(realManga).apply { mkdirs() }
        val bytes = kotlin.random.Random(5).nextBytes(4096)
        File(dir, "Ch. 1.cbz").writeBytes(bytes)

        shouldThrow<IOException> {
            sync(listOf(old), downloads = manager, paths = paths, updateAllFails = true)
                .await(listOf(sChapter("/1", "Chapter 1")), realManga, source)
        }

        File(dir, "Ch. 1.cbz").readBytes() shouldBe bytes
        File(dir, "Chapter 1.cbz").exists() shouldBe false
        manager.isDownloaded(realManga, old) shouldBe true
    }
}
