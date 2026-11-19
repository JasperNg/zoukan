package zoukan.desktop.ui.library

import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.GroupLibraryMode
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

    private fun input(group: Int) = LibraryViewInput(
        items = items,
        settings = LibraryScreenModel.Settings(sortPrefs = LibraryScreenModel.SortPrefs(group = group)),
        sources = mapOf(10L to ("Zeta" to "en"), 20L to ("Alpha" to "ja")),
        trackStatuses = mapOf(1L to listOf(TrackStatus.READING), 3L to listOf(TrackStatus.READING, TrackStatus.COMPLETED)),
    )

    @Test
    fun `source groups are named after the source, in name order`() {
        val inp = input(LibraryGroup.BY_SOURCE)
        val projected = LibraryViewProjector.project(inp)
        projected.tabs.map { it.name } shouldContainExactly listOf("Alpha", "Local source", "Zeta")
        LibraryViewProjector.inCategory(inp.items, 10L, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(1L, 3L)
        // Local's id is the Default category's, which grouping must not treat as Default
        LibraryViewProjector.inCategory(inp.items, LocalSource.ID, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(4L)
    }

    @Test
    fun `status groups follow Komikku's order, unknown last`() {
        val inp = input(LibraryGroup.BY_STATUS)
        val projected = LibraryViewProjector.project(inp)
        projected.tabs.map { it.name } shouldContainExactly listOf("Ongoing", "Completed", "Unknown")
        LibraryViewProjector.inCategory(inp.items, SManga.COMPLETED + 1L, grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(2L, 3L)
    }

    @Test
    fun `an entry with several tracking statuses is in each, untracked ones in Not tracked`() {
        val inp = input(LibraryGroup.BY_TRACK_STATUS)
        val projected = LibraryViewProjector.project(inp)
        projected.tabs.map { it.id } shouldContainExactly listOf(1L, 5L, 7L)
        LibraryViewProjector.inCategory(inp.items, TrackStatus.READING.int.toLong(), grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(1L, 3L)
        LibraryViewProjector.inCategory(inp.items, TrackStatus.OTHER.int.toLong(), grouped = true, projected.groups).map { it.id } shouldContainExactly listOf(2L, 4L)
    }

    @Test
    fun `group tab updates follow the library dynamic category updates setting`() {
        val bySource = input(LibraryGroup.BY_SOURCE)
        val bySourceGroups = LibraryViewProjector.project(bySource).groups
        LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.GLOBAL, bySourceGroups) shouldBe null
        LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.ALL_BUT_UNGROUPED, bySourceGroups) shouldBe setOf(1L, 3L)
        LibraryViewProjector.groupUpdateIds(bySource, 10L, GroupLibraryMode.ALL, bySourceGroups) shouldBe setOf(1L, 3L)

        val ungrouped = input(LibraryGroup.UNGROUPED)
        val ungroupedGroups = LibraryViewProjector.project(ungrouped).groups
        LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.GLOBAL, ungroupedGroups) shouldBe null
        LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.ALL_BUT_UNGROUPED, ungroupedGroups) shouldBe null
        LibraryViewProjector.groupUpdateIds(ungrouped, null, GroupLibraryMode.ALL, ungroupedGroups) shouldBe setOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `ungrouped shows only All`() {
        val inp = input(LibraryGroup.UNGROUPED)
        val projected = LibraryViewProjector.project(inp)
        projected.tabs.size shouldBe 0
        LibraryViewProjector.inCategory(inp.items, null, grouped = true, projected.groups).size shouldBe 4
    }
}
