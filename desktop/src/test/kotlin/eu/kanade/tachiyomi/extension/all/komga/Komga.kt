package eu.kanade.tachiyomi.extension.all.komga

import eu.kanade.tachiyomi.data.track.komga.SeriesDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A stand-in for the Komga extension (named like it, which is what the app's Komga tracker
 * accepts), over `zoukan.desktop.e2e.FixtureKomga`, for the tracking journeys.
 *
 * As the real extension's, its manga URLs are absolute series API URLs
 * (`<baseUrl>/api/v1/series/<id>`): the tracker binds by reading that URL as it is. Chapters are
 * the series' books, and every book has one fixture page.
 *
 * @param httpClient the client to use; the app's by default. Unit tests pass a plain one.
 */
// The request and parse helpers are deprecated, but still what extensions override
@Suppress("OVERRIDE_DEPRECATION")
class Komga(
    override val baseUrl: String,
    private val httpClient: OkHttpClient? = null,
) : HttpSource() {

    override val name = "Komga (fixture)"
    override val lang = "all"
    override val supportsLatest = false

    // Neither the source nor a search by the title's words: only the journeys' own requests
    override val disableRelatedMangas: Boolean get() = true

    override val client: OkHttpClient by lazy { httpClient ?: network.client }

    override fun headersBuilder() = Headers.Builder()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/v1/series", headers)

    override fun popularMangaParse(response: Response): MangasPage = seriesList(response, query = "")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/v1/series?search=${java.net.URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        seriesList(response, query = response.request.url.queryParameter("search").orEmpty())

    private fun seriesList(response: Response, query: String): MangasPage {
        val mangas = Json.parseToJsonElement(response.body.string()).jsonArray.map { it.jsonObject }
            .filter { query.isBlank() || it.string("title").contains(query, ignoreCase = true) }
            .map { series ->
                SManga.create().apply {
                    url = "$baseUrl/api/v1/series/${series.string("id")}"
                    title = series.string("title")
                    thumbnail_url = "$url/thumbnail"
                }
            }
        return MangasPage(mangas, hasNextPage = false)
    }

    // The URL as it is: it is absolute
    override fun mangaDetailsRequest(manga: SManga): Request = GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val series = JSON.decodeFromString(SeriesDto.serializer(), response.body.string())
        return SManga.create().apply {
            title = series.metadata.title
            description = series.metadata.summary
            thumbnail_url = "${response.request.url}/thumbnail"
            status = if (series.metadata.status == "ENDED") SManga.COMPLETED else SManga.ONGOING
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("${manga.url}/books", headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        // Newest first, as sources list them
        Json.parseToJsonElement(response.body.string()).jsonArray.map { it.jsonObject }.map { book ->
            SChapter.create().apply {
                url = "$baseUrl/api/v1/books/${book.string("id")}"
                name = book.string("name")
                chapter_number = book.getValue("number").jsonPrimitive.float
            }
        }.reversed()

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // One fixture page per book, without a request
    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, imageUrl = "$baseUrl/images/$PAGE"))

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        const val PAGE = "png-1.png"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
