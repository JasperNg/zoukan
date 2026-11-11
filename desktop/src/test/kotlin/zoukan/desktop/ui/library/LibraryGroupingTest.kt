package zoukan.desktop.ui.library

import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.local.LocalSource

class LibraryGroupingTest {

    private fun entry(id: Long, source: Long, status: Int) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source, ogStatus = status.toLong(), ogTitle = "Entry $id"),
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
        entry(1, source = 10, status = SManga.ONGOING),
        entry(2, source = 20, status = SManga.COMPLETED),
        entry(3, source = 10, status = SManga.COMPLETED),
        entry(4, source = LocalSource.ID, status = SManga.UNKNOWN),
    )

    private fun state(group: Int) = LibraryScreenModel.State(
        loading = false,
        items = items,
        settings = LibraryScreenModel.Settings(group = group),
        sources = mapOf(10L to ("Zeta" to "en"), 20L to ("Alpha" to "ja")),
        trackStatuses = mapOf(1L to listOf(TrackStatus.READING), 3L to listOf(TrackStatus.READING, TrackStatus.COMPLETED)),
    )

    @Test
    fun `source groups are named after the source, in name order`() {
        val state = state(LibraryGroup.BY_SOURCE)
        state.tabs.map { it.name } shouldContainExactly listOf("Alpha", "Local source", "Zeta")
        state.inCategory(10L).map { it.id } shouldContainExactly listOf(1L, 3L)
        // Local's id is the Default category's, which grouping must not treat as Default
        state.inCategory(LocalSource.ID).map { it.id } shouldContainExactly listOf(4L)
    }

    @Test
    fun `status groups follow Komikku's order, unknown last`() {
        val state = state(LibraryGroup.BY_STATUS)
        state.tabs.map { it.name } shouldContainExactly listOf("Ongoing", "Completed", "Unknown")
        state.inCategory(SManga.COMPLETED + 1L).map { it.id } shouldContainExactly listOf(2L, 3L)
    }

    @Test
    fun `an entry with several tracking statuses is in each, untracked ones in Not tracked`() {
        val state = state(LibraryGroup.BY_TRACK_STATUS)
        state.tabs.map { it.id } shouldContainExactly listOf(1L, 5L, 7L)
        state.inCategory(TrackStatus.READING.int.toLong()).map { it.id } shouldContainExactly listOf(1L, 3L)
        state.inCategory(TrackStatus.OTHER.int.toLong()).map { it.id } shouldContainExactly listOf(2L, 4L)
    }

    @Test
    fun `ungrouped shows only All`() {
        val state = state(LibraryGroup.UNGROUPED)
        state.tabs.size shouldBe 0
        state.inCategory(null).size shouldBe 4
    }
}
