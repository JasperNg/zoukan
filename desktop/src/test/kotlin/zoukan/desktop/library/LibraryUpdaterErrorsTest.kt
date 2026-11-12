package zoukan.desktop.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.domain.RefreshManga
import java.nio.file.Files

/** The update writes each failure to the Android error tables and clears the entries that updated. */
class LibraryUpdaterErrorsTest {

    private fun entry(id: Long, source: Long) = LibraryManga(
        manga = mockk<Manga> {
            every { this@mockk.id } returns id
            every { title } returns "M$id"
            every { this@mockk.source } returns source
            every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
            every { favorite } returns true
        },
        categories = emptyList(),
        totalChapters = 1,
        readCount = 0,
        bookmarkCount = 0,
        bookmarkReadCount = 0,
        chapterFlags = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )

    @Test
    fun `failures are recorded, updated entries cleared, and a retry runs only the given entries`() = runBlocking<Unit> {
        val library = listOf(entry(1, 10), entry(2, 10), entry(3, 99), entry(4, 10))
        val errors = mockk<LibraryUpdateErrors>(relaxed = true)
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(match { it.id == 1L }, any(), any(), any(), any(), any()) } answers { RefreshManga.Result(firstArg(), emptyList()) }
            coEvery { this@mockk.invoke(match { it.id == 2L }, any(), any(), any(), any(), any()) } throws NoChaptersException()
            coEvery { this@mockk.invoke(match { it.id == 4L }, any(), any(), any(), any(), any()) } throws IllegalStateException("HTTP error 503")
        }
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(10L) } returns mockk()
            every { get(99L) } returns null
        }
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(Files.createTempDirectory("zoukan-update-errors").toFile()),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns library },
            refreshManga = refreshManga,
            sourceManager = sourceManager,
            preferences = mockk<LibraryPreferences> {
                every { lastUpdatedTimestamp() } returns mockk<Preference<Long>>(relaxed = true)
                every { autoUpdateMetadata() } returns mockk<Preference<Boolean>> { every { get() } returns false }
            },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = errors,
            getManga = mockk<GetManga> { coEvery { await(any<Long>()) } answers { library.first { it.id == firstArg<Long>() }.manga } },
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        val result = updater.run(mangaIds = setOf(1L, 2L, 3L, 4L))

        result.updated shouldBe 4
        result.failures.map { it.manga.id to it.message }.sortedBy { it.first } shouldContainExactly listOf(
            2L to "No chapters found",
            3L to "Source not found",
            4L to "HTTP error 503",
        )
        coVerify(exactly = 1) { errors.clean() }
        coVerify(exactly = 1) { errors.clear(1L) }
        coVerify(exactly = 1) { errors.record(2L, "No chapters found") }
        coVerify(exactly = 1) { errors.record(3L, "Source not found") }
        coVerify(exactly = 1) { errors.record(4L, "HTTP error 503") }

        val retry = updater.run(mangaIds = setOf(1L))
        retry.updated shouldBe 1
        retry.skipped shouldBe emptyList()
        coVerify(exactly = 2) { errors.clear(1L) }
    }
}
