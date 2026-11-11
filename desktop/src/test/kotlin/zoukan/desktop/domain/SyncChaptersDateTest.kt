package zoukan.desktop.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class SyncChaptersDateTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `the fetch time stand-in for a missing upload date is not a source date`() {
        // What syncing stores for a source without dates: upload = now, fetch = now + 1…n
        SyncChapters.hasSourceDate(Chapter.create().copy(dateUpload = now, dateFetch = now + 1)) shouldBe false
        SyncChapters.hasSourceDate(Chapter.create().copy(dateUpload = now, dateFetch = now + 950)) shouldBe false
        SyncChapters.hasSourceDate(Chapter.create().copy(dateUpload = 0, dateFetch = now)) shouldBe false
        // Real dates: days before the fetch, or a chapter restored with an older fetch date
        SyncChapters.hasSourceDate(Chapter.create().copy(dateUpload = now - 86_400_000, dateFetch = now + 3)) shouldBe true
        SyncChapters.hasSourceDate(Chapter.create().copy(dateUpload = now, dateFetch = now - 10)) shouldBe true
    }
}
