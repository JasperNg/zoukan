package zoukan.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import eu.kanade.tachiyomi.data.track.komga.BookMetadataAggregationDto
import eu.kanade.tachiyomi.data.track.komga.ReadProgressUpdateV2Dto
import eu.kanade.tachiyomi.data.track.komga.ReadProgressV2Dto
import eu.kanade.tachiyomi.data.track.komga.SeriesDto
import eu.kanade.tachiyomi.data.track.komga.SeriesMetadataDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A Komga server on the [FixtureServer], for the tracking journeys: the app's Komga tracker binds
 * itself to the stand-in source `eu.kanade.tachiyomi.extension.all.komga.Komga` (test sources) and
 * reads and writes the read progress here. The JSON comes from the tracker's own DTO classes, so a
 * change to them breaks this file's compile rather than a journey.
 *
 * Routes:
 * - `GET /api/v1/series`: every series as `[{"id", "title"}]` (the stand-in source's own format).
 * - `GET /api/v1/series/<id>`: a [SeriesDto].
 * - `GET /api/v1/series/<id>/thumbnail`: a fixture cover.
 * - `GET /api/v1/series/<id>/books`: the books, `[{"id", "name", "number"}]`, numbered from 1.
 * - `GET /api/v2/series/<id>/read-progress/tachiyomi`: a [ReadProgressV2Dto].
 * - `PUT` the same path with a [ReadProgressUpdateV2Dto]: marks the books up to that number read
 *   (never unmarks, as Komga does) and answers 204.
 *
 * Unknown series answer 404.
 */
class FixtureKomga(private val server: FixtureServer) : AutoCloseable {

    private data class Series(val id: String, val title: String, val books: Int)

    private val series = ConcurrentHashMap<String, Series>()
    private val lastRead = ConcurrentHashMap<String, Double>()

    /** Every progress PUT, as (series id, lastBookNumberSortRead), in order. */
    val progressUpdates: MutableList<Pair<String, Double>> = Collections.synchronizedList(mutableListOf())

    /** The series' URL as the stand-in source and the tracker use it. */
    fun seriesUrl(id: String) = "${server.baseUrl}/api/v1/series/$id"

    /** Adds (or replaces) series [id] with [books] books numbered 1 to [books], none read. */
    fun series(id: String, title: String, books: Int) {
        require(books >= 0) { "books must not be negative: $books" }
        series[id] = Series(id, title, books)
        lastRead.remove(id)
    }

    /** The read progress of series [id], as the tracker reads it. */
    fun progress(id: String): ReadProgressV2Dto = progressOf(series.getValue(id))

    /** Marks the books of series [id] up to [number] read, as reading on another device does. */
    fun markRead(id: String, number: Double) {
        require(series.containsKey(id)) { "No series $id" }
        lastRead.merge(id, number, ::maxOf)
    }

    private val route: (HttpExchange, List<String>) -> Boolean = route@{ exchange, parts ->
        if (parts.size < 3 || parts[0] != "api" || parts[2] != "series") return@route false
        when (parts[1]) {
            "v1" -> v1(exchange, parts.drop(3))
            "v2" -> v2(exchange, parts.drop(3))
            else -> return@route false
        }
        true
    }

    init {
        server.extraRoutes += route
    }

    private fun v1(exchange: HttpExchange, rest: List<String>) {
        if (exchange.requestMethod != "GET") return notAllowed(exchange, "GET")
        if (rest.isEmpty()) {
            val list = buildJsonArray {
                series.values.sortedBy { it.id }.forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("title", s.title)
                        },
                    )
                }
            }
            return json(exchange, list.toString())
        }
        val s = series[rest[0]] ?: return notFound(exchange)
        when (rest.drop(1)) {
            emptyList<String>() -> json(exchange, JSON.encodeToString(SeriesDto.serializer(), seriesDto(s)))
            listOf("thumbnail") -> respond(exchange, 200, "image/png", cover())
            listOf("books") -> json(
                exchange,
                buildJsonArray {
                    (1..s.books).forEach { n ->
                        add(
                            buildJsonObject {
                                put("id", "${s.id}-$n")
                                put("name", "Book $n")
                                put("number", n)
                            },
                        )
                    }
                }.toString(),
            )
            else -> notFound(exchange)
        }
    }

    private fun v2(exchange: HttpExchange, rest: List<String>) {
        if (rest.size != 3 || rest[1] != "read-progress" || rest[2] != "tachiyomi") return notFound(exchange)
        val s = series[rest[0]] ?: return notFound(exchange)
        when (exchange.requestMethod) {
            "GET" -> json(exchange, JSON.encodeToString(ReadProgressV2Dto.serializer(), progressOf(s)))
            "PUT" -> {
                val update = runCatching {
                    JSON.decodeFromString(ReadProgressUpdateV2Dto.serializer(), exchange.requestBody.readAllBytes().decodeToString())
                }.getOrNull()
                if (update == null) {
                    respond(exchange, 400, "text/plain", "Bad read progress".toByteArray())
                    return
                }
                progressUpdates += s.id to update.lastBookNumberSortRead
                lastRead.merge(s.id, update.lastBookNumberSortRead, ::maxOf)
                respond(exchange, 204)
            }
            else -> notAllowed(exchange, "GET, PUT")
        }
    }

    private fun progressOf(s: Series): ReadProgressV2Dto {
        val read = (1..s.books).count { it <= (lastRead[s.id] ?: 0.0) }
        return ReadProgressV2Dto(
            booksCount = s.books,
            booksReadCount = read,
            booksUnreadCount = s.books - read,
            booksInProgressCount = 0,
            // Books are numbered 1, 2, 3...: the read ones are the first [read]
            lastReadContinuousNumberSort = read.toDouble(),
            maxNumberSort = s.books.toFloat(),
        )
    }

    private fun seriesDto(s: Series): SeriesDto {
        val progress = progressOf(s)
        return SeriesDto(
            id = s.id,
            libraryId = "fixture-library",
            name = s.title,
            created = DATE,
            lastModified = DATE,
            fileLastModified = DATE,
            booksCount = s.books,
            booksReadCount = progress.booksReadCount,
            booksUnreadCount = progress.booksUnreadCount,
            booksInProgressCount = progress.booksInProgressCount,
            metadata = SeriesMetadataDto(
                status = "ONGOING",
                created = DATE,
                lastModified = DATE,
                title = s.title,
                titleSort = s.title,
                summary = "A Komga fixture series.",
                summaryLock = false,
                readingDirection = "LEFT_TO_RIGHT",
                readingDirectionLock = false,
                publisher = "Fixture Publisher",
                publisherLock = false,
                ageRating = null,
                ageRatingLock = false,
                language = "en",
                languageLock = false,
                genres = emptySet(),
                genresLock = false,
                tags = emptySet(),
                tagsLock = false,
            ),
            booksMetadata = BookMetadataAggregationDto(
                releaseDate = null,
                summary = "",
                summaryNumber = "",
                created = DATE,
                lastModified = DATE,
            ),
        )
    }

    private fun cover(): ByteArray = checkNotNull(FixtureKomga::class.java.getResourceAsStream("/e2e-fixtures/images/$COVER")) {
        "The fixture cover $COVER is missing"
    }.use { it.readBytes() }

    private fun json(exchange: HttpExchange, body: String) {
        respond(exchange, 200, "application/json", body.toByteArray())
    }

    private fun notFound(exchange: HttpExchange) {
        respond(exchange, 404, "text/plain", "Not found".toByteArray())
    }

    private fun notAllowed(exchange: HttpExchange, allow: String) {
        exchange.responseHeaders.add("Allow", allow)
        respond(exchange, 405, "text/plain", "Method not allowed".toByteArray())
    }

    override fun close() {
        server.extraRoutes -= route
    }

    companion object {
        /** The name the app's Komga tracker accepts (getAcceptedSources in track/komga/Komga.kt). */
        const val SOURCE_CLASS = "eu.kanade.tachiyomi.extension.all.komga.Komga"

        private const val COVER = "cover-1.png"
        private const val DATE = "2026-01-01T00:00:00Z"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
