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
 * The fixture source's page previews and related titles, which the entry screen shows
 * (PagePreviewsJourneyTest). Neither touches the network or the source's settings, so this needs
 * no app runtime.
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
