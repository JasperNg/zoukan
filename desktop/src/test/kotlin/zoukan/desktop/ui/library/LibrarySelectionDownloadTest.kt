package zoukan.desktop.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.local.LocalSource

/** The selection bar's download actions, as Android's LibraryTab shows them. */
class LibrarySelectionDownloadTest {

    private fun entry(id: Long, source: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source, ogTitle = "title $id"),
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

    private val state = LibraryScreenModel.State(
        loading = false,
        items = listOf(entry(1, LocalSource.ID), entry(2, LocalSource.ID), entry(3, 42L)),
    )

    @Test
    fun `a selection of local entries offers no download actions`() {
        val local = state.copy(selection = setOf(1L, 2L))
        local.selectionCanDownload shouldBe false
        local.selectionHasRemote shouldBe false
    }

    @Test
    fun `a mixed selection hides Download but keeps Delete downloads`() {
        val mixed = state.copy(selection = setOf(1L, 3L))
        mixed.selectionCanDownload shouldBe false
        mixed.selectionHasRemote shouldBe true
    }

    @Test
    fun `a selection of source entries offers both`() {
        val remote = state.copy(selection = setOf(3L))
        remote.selectionCanDownload shouldBe true
        remote.selectionHasRemote shouldBe true
    }
}
