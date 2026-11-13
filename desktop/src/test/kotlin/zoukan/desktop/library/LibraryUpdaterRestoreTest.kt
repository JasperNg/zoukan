package zoukan.desktop.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.domain.RefreshManga
import java.nio.file.Files

/** An update a restore stops is not a finished update: the schedule must run it again. */
class LibraryUpdaterRestoreTest {

    private fun entry(id: Long) = LibraryManga(
        manga = mockk<Manga> {
            every { this@mockk.id } returns id
            every { title } returns "M$id"
            every { source } returns 10L
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
    fun `an update stopped for a restore says so and keeps the previous update time`() = runBlocking<Unit> {
        val timestamp = mockk<Preference<Long>>(relaxed = true) { every { get() } returns 1234L }
        val refreshManga = mockk<RefreshManga>()
        val updater = LibraryUpdater(
            dirs = ZoukanDirs(Files.createTempDirectory("zoukan-update-restore").toFile()),
            getLibraryManga = mockk<GetLibraryManga> { coEvery { await() } returns listOf(entry(1), entry(2)) },
            refreshManga = refreshManga,
            sourceManager = mockk(relaxed = true),
            preferences = mockk<LibraryPreferences> { every { lastUpdatedTimestamp() } returns timestamp },
            fetchInterval = mockk(),
            filterChaptersForDownload = mockk(),
            downloadManager = mockk(),
            errors = mockk(relaxed = true),
            getManga = mockk(),
            getMergedMangaForDownloading = mockk(),
            getTracks = mockk(),
            insertTrack = mockk(),
            trackerManager = mockk { every { mdList } returns mockk { every { isLoggedIn } returns false } },
            syncPreferences = mockk(),
            syncManager = { mockk() },
        )

        // A restore is waiting: the update stops before its first entry
        RestoreGate.pending.incrementAndGet()
        val result = try {
            updater.run(mangaIds = setOf(1L, 2L))
        } finally {
            RestoreGate.pending.decrementAndGet()
        }

        result.stoppedForRestore shouldBe true
        coVerify(exactly = 0) { refreshManga.invoke(any(), any(), any(), any(), any(), any()) }
        verify { timestamp.set(1234L) }
    }
}
