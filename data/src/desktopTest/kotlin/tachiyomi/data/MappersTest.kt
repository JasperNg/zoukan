package tachiyomi.data

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import mihon.core.common.extensions.JsonObjectEmpty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.chapter.ChapterSanitizer.sanitize
import tachiyomi.data.manga.MangaMapper

/** The row-to-model mappers and the chapter name rule the chapter sync applies. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MappersTest {

    @BeforeEach
    fun registerInjekt() {
        registerNoCustomMangaInfo()
    }

    private fun libraryManga(categories: String, lastUpdate: Long? = 5, nextUpdate: Long? = 6) =
        MangaMapper.mapLibraryManga(
            id = 1, source = 2, url = "/m", artist = null, author = null, description = null,
            genre = null, title = "Title", status = 1, thumbnailUrl = null, favorite = true,
            lastUpdate = lastUpdate, nextUpdate = nextUpdate, initialized = true, viewerFlags = 0,
            chapterFlags = 0, coverLastModified = 0, dateAdded = 0, filteredScanlators = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE, calculateInterval = 7, lastModifiedAt = 0,
            favoriteModifiedAt = null, version = 0, isSyncing = 0, notes = "", memo = JsonObjectEmpty,
            totalCount = 10, readCount = 3.0, latestUpload = 0, chapterFetchedAt = 0, lastRead = 0,
            bookmarkCount = 2.0, bookmarkedReadCount = 1, categories = categories,
        )

    @Test
    fun `a library entry's categories are parsed from the view's comma-separated list`() {
        libraryManga("0").categories shouldBe listOf(0L)
        libraryManga("3,1,12").categories shouldBe listOf(3L, 1L, 12L)
    }

    @Test
    fun `a library entry keeps its counts and turns missing update times into zero`() {
        val entry = libraryManga("1", lastUpdate = null, nextUpdate = null)
        entry.totalChapters shouldBe 10
        entry.readCount shouldBe 3
        entry.bookmarkCount shouldBe 2
        entry.bookmarkReadCount shouldBe 1
        entry.manga.lastUpdate shouldBe 0
        entry.manga.nextUpdate shouldBe 0
        entry.manga.fetchInterval shouldBe 7
        entry.manga.ogTitle shouldBe "Title"
    }

    @Test
    fun `a chapter name loses the manga title in front and the separators around it`() {
        "  Solo Leveling - Chapter 12 ".sanitize("Solo Leveling") shouldBe "Chapter 12"
        "Solo Leveling: Ch. 3".sanitize("Solo Leveling") shouldBe "Ch. 3"
        "　Chapter 1_ ".sanitize("Other") shouldBe "Chapter 1"
    }

    @Test
    fun `a chapter name that is only the title, or has it elsewhere, is not mangled`() {
        "Solo Leveling".sanitize("Solo Leveling") shouldBe ""
        "Chapter 1 of Solo Leveling".sanitize("Solo Leveling") shouldBe "Chapter 1 of Solo Leveling"
        // Only an exact, case-sensitive prefix is removed
        "solo leveling 5".sanitize("Solo Leveling") shouldBe "solo leveling 5"
        "".sanitize("") shouldBe ""
    }
}
