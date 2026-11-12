package zoukan.desktop.library

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga

class LibraryUpdaterTest {

    private val updater = LibraryUpdater(
        dirs = mockk(),
        getLibraryManga = mockk(),
        refreshManga = mockk(),
        sourceManager = mockk(),
        preferences = mockk<LibraryPreferences> {
            every { updateCategories() } returns set(emptySet())
            every { updateCategoriesExclude() } returns set(emptySet())
            // The Android defaults
            every { autoUpdateMangaRestrictions() } returns set(
                setOf(
                    LibraryPreferences.MANGA_HAS_UNREAD,
                    LibraryPreferences.MANGA_NON_COMPLETED,
                    LibraryPreferences.MANGA_NON_READ,
                    LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
                ),
            )
        },
        fetchInterval = FetchInterval(mockk()),
        filterChaptersForDownload = mockk(),
        downloadManager = mockk(),
        errors = mockk(relaxed = true),
        getManga = mockk(),
        getMergedMangaForDownloading = mockk(),
        getTracks = mockk(),
        insertTrack = mockk(),
        trackerManager = mockk(),
        syncPreferences = mockk(),
        syncManager = { mockk() },
    )

    private fun set(value: Set<String>) = mockk<Preference<Set<String>>> { every { get() } returns value }

    private fun entry(id: Long, total: Long = 10, read: Long = 0, status: Int = SManga.ONGOING, categories: List<Long> = emptyList()) =
        LibraryManga(
            // Manga.create() reads custom titles through Injekt, so a mock
            manga = mockk<Manga> {
                every { this@mockk.id } returns id
                every { title } returns "M$id"
                every { this@mockk.status } returns status.toLong()
                every { updateStrategy } returns UpdateStrategy.ALWAYS_UPDATE
                every { nextUpdate } returns 0L
                every { dateAdded } returns id
            },
            categories = categories,
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )

    @Test
    fun `the Android default skip rules`() {
        val library = listOf(
            entry(1, total = 10, read = 10),
            entry(2, total = 10, read = 3),
            entry(3, total = 10, read = 0),
            entry(4, total = 10, read = 10, status = SManga.COMPLETED),
            entry(5, total = 0),
        )
        val (selected, skipped) = updater.select(library, null)
        selected.map { it.id }.sorted() shouldContainExactly listOf(1L, 5L)
        skipped.map { it.second } shouldContainExactly listOf("Has unread chapters", "Has unread chapters", "Completed")
    }

    @Test
    fun `skip rules off and one category`() {
        val library = listOf(entry(1, read = 0, categories = listOf(7)), entry(2, read = 0), entry(3, read = 10, categories = listOf(8)))
        updater.select(library, null, ignoreRestrictions = true).first.map { it.id }.sorted() shouldContainExactly listOf(1L, 2L, 3L)
        updater.select(library, 7L, ignoreRestrictions = true).first.map { it.id } shouldContainExactly listOf(1L)
        updater.select(library, 0L, ignoreRestrictions = true).first.map { it.id } shouldContainExactly listOf(2L)
    }

    @Test
    fun `a source, status or tracking tab updates its entries with the skip rules`() {
        val library = listOf(entry(1, read = 10, categories = listOf(7)), entry(2, read = 3), entry(3, read = 10))
        val (selected, skipped) = updater.select(library, null, groupIds = setOf(1L, 2L))
        selected.map { it.id } shouldContainExactly listOf(1L)
        skipped.map { it.second } shouldContainExactly listOf("Has unread chapters")
    }
}
