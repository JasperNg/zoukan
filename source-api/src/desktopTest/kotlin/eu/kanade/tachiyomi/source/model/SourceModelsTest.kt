package eu.kanade.tachiyomi.source.model

import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import rx.Observable

/**
 * The extension API's models as the app copies and merges them: a copy must keep every field the
 * source set (a lost field is lost data once saved), and the genre string is split the way the
 * Android app splits it.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SourceModelsTest {

    private val memo = buildJsonObject { put("mihon.key", "value") }

    private fun fullManga() = SManga.create().apply {
        url = "/manga/1"
        title = "Title"
        artist = "Artist"
        author = "Author"
        description = "Description"
        genre = "Action, Drama"
        status = SManga.ONGOING
        thumbnail_url = "https://example.org/cover.jpg"
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
        memo = this@SourceModelsTest.memo
    }

    private fun SManga.fields() = listOf(
        url, title, artist, author, description, genre, status, thumbnail_url, update_strategy, initialized, memo,
    )

    @Test
    fun `a manga copy keeps every field and is independent of the original`() {
        val original = fullManga()
        val copy = original.copy()
        copy.fields() shouldBe original.fields()
        copy.title = "Changed"
        original.title shouldBe "Title"
    }

    @Test
    fun `the named copy changes only what it names and keeps the memo`() {
        val copy = fullManga().copy(title = "New", status = SManga.COMPLETED)
        copy.title shouldBe "New"
        copy.status shouldBe SManga.COMPLETED
        copy.author shouldBe "Author"
        copy.thumbnail_url shouldBe "https://example.org/cover.jpg"
        copy.memo shouldBe memo
        copy.initialized shouldBe true
    }

    @Test
    fun `genres are split on comma and space, trimmed, without blanks or repeats`() {
        fun genres(genre: String?) = SManga.create().apply { this.genre = genre }.getGenres()
        genres("Action, Drama ,  Action, , Sci-Fi") shouldBe listOf("Action", "Drama", "Sci-Fi")
        genres("Action, Drama, Action") shouldBe listOf("Action", "Drama")
        genres("Comedy,Romance") shouldBe listOf("Comedy,Romance")
        genres(null) shouldBe null
        genres("  ") shouldBe null
    }

    @Test
    fun `a chapter copied from another takes all of its fields`() {
        val source = SChapter("Chapter 5", "/c/5", date_upload = 1234, chapter_number = 5f, scanlator = "Group").apply {
            memo = this@SourceModelsTest.memo
        }
        val target = SChapter.create().apply {
            url = "/old"
            name = "Old"
        }
        target.copyFrom(source)
        listOf(target.name, target.url, target.date_upload, target.chapter_number, target.scanlator, target.memo) shouldBe
            listOf("Chapter 5", "/c/5", 1234L, 5f, "Group", memo)
    }

    @Test
    fun `a new chapter has no number and no date until the source sets them`() {
        val chapter = SChapter("Name", "/c")
        chapter.chapter_number shouldBe -1f
        chapter.date_upload shouldBe 0L
        chapter.scanlator shouldBe null
        chapter.memo.isEmpty() shouldBe true
    }

    @Test
    fun `page progress is a percentage, and unknown when the size is unknown`() {
        val page = Page(index = 2, url = "/p/3")
        page.number shouldBe 3
        page.update(bytesRead = 50, contentLength = 200, done = false)
        page.progress shouldBe 25
        page.update(bytesRead = 200, contentLength = 200, done = true)
        page.progress shouldBe 100
        page.update(bytesRead = 10, contentLength = 0, done = false)
        page.progress shouldBe -1
        page.update(bytesRead = 10, contentLength = -1, done = false)
        page.progress shouldBe -1
        // Large files do not overflow
        page.update(bytesRead = 3_000_000_000L, contentLength = 6_000_000_000L, done = false)
        page.progress shouldBe 50
    }

    @Test
    fun `filter equality looks at name and state, and a filter list is never equal to another`() {
        class Box(state: Boolean) : Filter.CheckBox("Box", state)
        Box(true) shouldBe Box(true)
        (Box(true) == Box(false)) shouldBe false
        val list = FilterList(Box(true))
        // Deliberately never equal, so a screen always takes a new list as a change
        (list == list) shouldBe false
        FilterList().isEmpty() shouldBe true
    }

    private class FakeCatalogue(
        private val details: () -> SManga,
        private val chapters: () -> List<SChapter>,
    ) : CatalogueSource {
        override val id = 1L
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = false

        @Deprecated("Use the suspend API instead")
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable { details() }

        @Deprecated("Use the suspend API instead")
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable { chapters() }
    }

    @Test
    fun `a manga update fetches only what is asked and keeps the rest as given`() = runBlocking<Unit> {
        val given = fullManga()
        val known = listOf(SChapter("Known", "/c/1"))
        val fetched = SManga("/manga/1", "Fetched")
        val source = FakeCatalogue({ fetched }, { listOf(SChapter("New", "/c/2")) })

        source.getMangaUpdate(given, known, fetchDetails = false, fetchChapters = true).let {
            it.manga shouldBe given
            it.chapters.map { c -> c.name } shouldBe listOf("New")
        }
        source.getMangaUpdate(given, known, fetchDetails = true, fetchChapters = false).let {
            it.manga shouldBe fetched
            it.chapters shouldBe known
        }
    }

    @Test
    fun `a failed details fetch fails the update instead of returning stale details as new`() = runBlocking<Unit> {
        val source = FakeCatalogue({ error("details failed") }, { emptyList() })
        shouldThrow<IllegalStateException> {
            source.getMangaUpdate(fullManga(), emptyList(), fetchDetails = true, fetchChapters = true)
        }.message shouldBe "details failed"
    }
}
