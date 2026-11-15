package zoukan.desktop.e2e

import eu.kanade.tachiyomi.data.track.komga.ReadProgressUpdateV2Dto
import eu.kanade.tachiyomi.data.track.komga.ReadProgressV2Dto
import eu.kanade.tachiyomi.data.track.komga.SeriesDto
import eu.kanade.tachiyomi.extension.all.komga.Komga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

/**
 * The Komga API [FixtureKomga] serves, decoded with the app's Komga tracker DTOs, and the Komga
 * stand-in source over it. Plain HTTP, no app runtime.
 */
class FixtureKomgaTest {

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val json = Json { ignoreUnknownKeys = true }

    // Unit, so JUnit runs the expression-bodied tests (it skips test methods that return a value)
    private fun withKomga(block: (FixtureServer, FixtureKomga) -> Unit) = FixtureServer().use { server ->
        FixtureKomga(server).use {
            it.series("1", "Komga Title", books = 3)
            block(server, it)
        }
    }

    private fun call(url: String, method: String = "GET", body: String? = null): HttpResponse<ByteArray> {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    /** The read-progress URL as KomgaApi builds it from the series URL. */
    private fun progressUrl(seriesUrl: String) =
        "${seriesUrl.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi"

    private fun progress(server: FixtureServer, id: String): ReadProgressV2Dto {
        val response = call(progressUrl("${server.baseUrl}/api/v1/series/$id"))
        response.statusCode() shouldBe 200
        return json.decodeFromString(ReadProgressV2Dto.serializer(), response.body().decodeToString())
    }

    @Test
    fun `a series decodes as the tracker's SeriesDto`() = withKomga { server, komga ->
        val response = call(komga.seriesUrl("1"))
        response.statusCode() shouldBe 200
        val series = json.decodeFromString(SeriesDto.serializer(), response.body().decodeToString())
        series.id shouldBe "1"
        series.metadata.title shouldBe "Komga Title"
        series.booksCount shouldBe 3
        series.booksUnreadCount shouldBe 3
        komga.seriesUrl("1") shouldBe "${server.baseUrl}/api/v1/series/1"
    }

    @Test
    fun `the read progress decodes as ReadProgressV2Dto`() = withKomga { server, _ ->
        progress(server, "1") shouldBe ReadProgressV2Dto(
            booksCount = 3,
            booksReadCount = 0,
            booksUnreadCount = 3,
            booksInProgressCount = 0,
            lastReadContinuousNumberSort = 0.0,
            maxNumberSort = 3f,
        )
    }

    @Test
    fun `a progress PUT round trips`() = withKomga { server, komga ->
        val body = json.encodeToString(ReadProgressUpdateV2Dto.serializer(), ReadProgressUpdateV2Dto(2.0))
        call(progressUrl(komga.seriesUrl("1")), "PUT", body).statusCode() shouldBe 204

        komga.progress("1").lastReadContinuousNumberSort shouldBe 2.0
        komga.progressUpdates shouldContainExactly listOf("1" to 2.0)
        val read = progress(server, "1")
        read.booksReadCount shouldBe 2
        read.booksUnreadCount shouldBe 1
        read.lastReadContinuousNumberSort shouldBe 2.0
    }

    @Test
    fun `a lower progress PUT does not unmark books, and markRead moves it on`() = withKomga { server, komga ->
        val url = progressUrl(komga.seriesUrl("1"))
        call(url, "PUT", """{"lastBookNumberSortRead":2.0}""").statusCode() shouldBe 204
        call(url, "PUT", """{"lastBookNumberSortRead":1.0}""").statusCode() shouldBe 204
        komga.progress("1").lastReadContinuousNumberSort shouldBe 2.0

        komga.markRead("1", 3.0)
        progress(server, "1").booksReadCount shouldBe 3
    }

    @Test
    fun `a bad progress body answers 400`() = withKomga { _, komga ->
        call(progressUrl(komga.seriesUrl("1")), "PUT", "{").statusCode() shouldBe 400
        komga.progressUpdates shouldBe emptyList()
    }

    @Test
    fun `an unknown series answers 404`() = withKomga { server, komga ->
        call(komga.seriesUrl("9")).statusCode() shouldBe 404
        call(progressUrl(komga.seriesUrl("9"))).statusCode() shouldBe 404
        call("${server.baseUrl}/api/v1/series/1/unknown").statusCode() shouldBe 404
    }

    @Test
    fun `the thumbnail is an image`() = withKomga { _, komga ->
        val response = call("${komga.seriesUrl("1")}/thumbnail")
        response.statusCode() shouldBe 200
        response.headers().firstValue("Content-Type").orElse(null) shouldBe "image/png"
        ImageIO.read(ByteArrayInputStream(response.body())) shouldNotBe null
    }

    @Test
    fun `the stand-in source has the class name the Komga tracker accepts`() {
        // Hard-coded as in the tracker's getAcceptedSources (app/.../data/track/komga/Komga.kt):
        // building the tracker needs the app's runtime
        val accepted = "eu.kanade.tachiyomi.extension.all.komga.Komga"
        FixtureKomga.SOURCE_CLASS shouldBe accepted
        // EnhancedTracker.accept compares source::class.qualifiedName
        Class.forName(accepted).kotlin.qualifiedName shouldBe accepted
        HttpSource::class.java.isAssignableFrom(Class.forName(accepted)) shouldBe true
    }

    @Test
    fun `the stand-in source lists, details and chapters from absolute series URLs`() = withKomga { server, komga ->
        komga.series("2", "Another Series", books = 1)
        val source = Komga(server.baseUrl, OkHttpClient())
        runBlocking<Unit> {
            val listed = source.getPopularManga(1).mangas
            listed.map { it.title } shouldContainExactly listOf("Komga Title", "Another Series")
            val manga = listed.first()
            manga.url shouldBe komga.seriesUrl("1")
            source.getMangaUrl(manga) shouldBe manga.url

            val update = source.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)
            update.manga.title shouldBe "Komga Title"
            update.manga.status shouldBe SManga.ONGOING
            update.chapters.map { it.chapter_number } shouldContainExactly listOf(3f, 2f, 1f)
            update.chapters.last().url shouldBe "${server.baseUrl}/api/v1/books/1-1"

            val pages = source.getPageList(update.chapters.last())
            pages.map { it.imageUrl } shouldContainExactly listOf("${server.baseUrl}/images/png-1.png")
            call(pages.single().imageUrl!!).statusCode() shouldBe 200

            source.getSearchManga(1, "another", source.getFilterList()).mangas.map { it.title } shouldContainExactly
                listOf("Another Series")
        }
    }

    @Test
    fun `after close the Komga routes are gone`() {
        FixtureServer().use { server ->
            val komga = FixtureKomga(server)
            komga.series("1", "Komga Title", books = 1)
            komga.close()
            server.extraRoutes.size shouldBe 0
            call(komga.seriesUrl("1")).statusCode() shouldBe 404
        }
    }
}
