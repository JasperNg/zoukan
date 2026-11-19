package zoukan.desktop.e2e

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The fixture source's page previews and related titles, which the entry screen shows. Neither
 * touches the network or the source's settings, so this needs no app runtime.
 */
class FixtureSourcePreviewsTest {

    private val baseUrl = "http://127.0.0.1:1"
    private val formats = manga("/manga/formats", FixtureCatalog.FORMATS_TITLE)

    @Test
    fun `previews are the first chapter's pages, numbered from 1`() = runBlocking<Unit> {
        val page = FixtureSource(baseUrl, previews = true).getPagePreviewList(formats, emptyList(), 1)

        page.pagePreviews.map { it.index } shouldContainExactly listOf(1, 2, 3)
        page.pagePreviews.map { it.imageUrl } shouldContainExactly FixtureCatalog.pages("jpeg").map { "$baseUrl/images/${it.file}" }
        page.page shouldBe 1
        page.hasNextPage shouldBe false
        page.pagePreviewPages shouldBe 1
    }

    @Test
    fun `with more preview pages each page goes on numbering, cycling the chapter's pages, and the last has no next`() = runBlocking<Unit> {
        val source = FixtureSource(baseUrl, previews = true, previewPages = 2, previewsPerPage = 5)
        val jpeg = FixtureCatalog.pages("jpeg").map { "$baseUrl/images/${it.file}" }

        val first = source.getPagePreviewList(formats, emptyList(), 1)
        first.pagePreviews.map { it.index } shouldContainExactly listOf(1, 2, 3, 4, 5)
        first.pagePreviews.map { it.imageUrl } shouldContainExactly listOf(jpeg[0], jpeg[1], jpeg[2], jpeg[0], jpeg[1])
        first.hasNextPage shouldBe true
        first.pagePreviewPages shouldBe 2

        val second = source.getPagePreviewList(formats, emptyList(), 2)
        second.pagePreviews.map { it.index } shouldContainExactly listOf(6, 7, 8, 9, 10)
        second.pagePreviews.first().imageUrl shouldBe jpeg[2]
        second.hasNextPage shouldBe false

        source.getPagePreviewList(formats, emptyList(), 3).pagePreviews.shouldBeEmpty()
        source.previewPagesAsked shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `the genre filter is there only when asked for, and keeps that genre's titles`() {
        FixtureSource(baseUrl).getFilterList().shouldBeEmpty()
        val filter = FixtureSource(baseUrl, filters = true).getFilterList().single() as FixtureSource.GenreFilter
        filter.selected shouldBe null
        filter.state = filter.values.indexOf(FixtureSource.GENRE_DRAMA)
        filter.selected shouldBe FixtureCatalog.GENRE_DRAMA

        val body = FixtureCatalog.manga.joinToString(",", "[", "]") {
            """{"key":"${it.key}","title":"${it.title}","cover":"c","bonus":${it.bonus},"searchOnly":${it.searchOnly},"genre":"${it.genre}"}"""
        }
        FixtureSource.catalogPage(body, query = "Fixture", genre = FixtureCatalog.GENRE_DRAMA).mangas.map { it.title } shouldContainExactly
            listOf("Fixture Second")
    }

    @Test
    fun `no previews by default`() = runBlocking<Unit> {
        val page = FixtureSource(baseUrl).getPagePreviewList(formats, emptyList(), 1)

        page.pagePreviews.shouldBeEmpty()
        page.hasNextPage shouldBe false
    }

    @Test
    fun `a title not in the catalogue has no previews, and no error`() = runBlocking<Unit> {
        val migrated = manga("/gallery/12345", "Somewhere else")

        FixtureSource(baseUrl, previews = true).getPagePreviewList(migrated, emptyList(), 1).pagePreviews.shouldBeEmpty()
    }

    @Test
    fun `related titles are two other catalogue titles`() = runBlocking<Unit> {
        val related = FixtureSource(baseUrl, related = true).fetchRelatedMangaList(formats)

        related shouldHaveSize 2
        related.map { it.url } shouldNotContain formats.url
        // Neither search-only nor the bonus title
        related.map { it.title } shouldContainExactly listOf("Fixture Second", "Fixture Third")
        related.forEach { it.thumbnail_url!!.startsWith("$baseUrl/images/cover-") shouldBe true }
    }

    @Test
    fun `related titles of another title leave that title out`() = runBlocking<Unit> {
        val second = manga("/manga/second", "Fixture Second")

        FixtureSource(baseUrl, related = true).fetchRelatedMangaList(second).map { it.title } shouldContainExactly
            listOf(FixtureCatalog.FORMATS_TITLE, "Fixture Third")
    }

    @Test
    fun `no related titles by default, and the app's search for them stays on`() = runBlocking<Unit> {
        val source = FixtureSource(baseUrl)

        source.fetchRelatedMangaList(formats).shouldBeEmpty()
        source.disableRelatedMangasBySearch shouldBe false
        FixtureSource(baseUrl, related = true).disableRelatedMangasBySearch shouldBe true
    }

    private fun manga(url: String, title: String) = SManga.create().apply {
        this.url = url
        this.title = title
    }
}
