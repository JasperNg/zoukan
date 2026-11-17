package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * MangaUpdates' search, bind, update and delete, as the requests the shared tracker sends to
 * api.mangaupdates.com and the answers it maps, with recorded answers in place of the network.
 * MangaUpdates signs in with a session token and has no refresh: a refused token is an error.
 */
class MangaUpdatesApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val mangaUpdates = MangaUpdates(7L)

    private fun signIn() {
        // The user id as the name, the session token as the password
        env.preferences.setCredentials(mangaUpdates, "4242", SESSION)
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(SESSION)
        env.close()
    }

    private fun track() = Track.create(7L).apply {
        remote_id = SERIES
        title = "One Piece"
        tracking_url = "https://www.mangaupdates.com/series/pb8uwds/one-piece"
    }

    @Test
    fun `search posts the query without the session token and maps the records`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """
            {"total_hits": 1, "page": 1, "per_page": 25, "results": [
              {"record": {"series_id": $SERIES, "title": "One Piece &amp; Friends",
                "url": "https://www.mangaupdates.com/series/pb8uwds/one-piece",
                "description": "Gol D. Roger &quot;the Pirate King&quot;",
                "image": {"url": {"original": "https://cdn.mangaupdates.com/image/i1.jpg", "thumb": "https://cdn.mangaupdates.com/image/thumb/i1.jpg"},
                          "height": 350, "width": 250},
                "type": "Manga", "year": "1997", "bayesian_rating": 8.9, "rating_votes": 5000, "latest_chapter": 1130,
                "genres": [{"genre": "Action"}], "last_updated": {"timestamp": 1700000000}},
               "hit_title": "One Piece"}
            ]}
            """.trimIndent(),
        )

        val results = mangaUpdates.search("one piece")

        val request = http.requests.single()
        request.method shouldBe "POST"
        request.url.toString() shouldBe "https://api.mangaupdates.com/v1/series/search"
        // Search is public: the session token is not sent
        request.headers["Authorization"].shouldBeNull()
        request.json().getValue("search").jsonPrimitive.content shouldBe "one piece"
        request.json().getValue("filter_types").jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
            listOf("drama cd", "novel")

        val onePiece = results.single()
        onePiece.tracker_id shouldBe 7L
        onePiece.remote_id shouldBe SERIES
        onePiece.title shouldBe "One Piece & Friends"
        onePiece.summary shouldBe "Gol D. Roger \"the Pirate King\""
        onePiece.cover_url shouldBe "https://cdn.mangaupdates.com/image/i1.jpg"
        onePiece.tracking_url shouldBe "https://www.mangaupdates.com/series/pb8uwds/one-piece"
        onePiece.publishing_type shouldBe "Manga"
        onePiece.start_date shouldBe "1997"
        onePiece.total_chapters shouldBe 0L
    }

    @Test
    fun `binding a series that is not on a list adds it to the reading list`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"exception","reason":"This series is not on one of your lists."}""", code = 404)
        http.answer("""{"status":"success","reason":"Series added to list"}""")

        val bound = mangaUpdates.bind(track(), hasReadChapters = true)

        val (find, add) = http.requests
        find.method shouldBe "GET"
        find.path shouldBe "/v1/lists/series/$SERIES"
        find.headers["Authorization"] shouldBe "Bearer $SESSION"
        add.method shouldBe "POST"
        add.path shouldBe "/v1/lists/series"
        add.headers["Authorization"] shouldBe "Bearer $SESSION"
        val item = add.jsonArray().single().jsonObject
        item.getValue("series").jsonObject.getValue("id").jsonPrimitive.long shouldBe SERIES
        item.getValue("list_id").jsonPrimitive.long shouldBe MangaUpdates.READING_LIST

        bound.remote_id shouldBe SERIES
        bound.status shouldBe MangaUpdates.READING_LIST
        bound.score shouldBe 0.0
    }

    @Test
    fun `binding a series that has no read chapters adds it to the wish list`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"exception"}""", code = 404)
        http.answer("""{"status":"success"}""")

        mangaUpdates.bind(track(), hasReadChapters = false).status shouldBe MangaUpdates.WISH_LIST
        http.requests[1].jsonArray().single().jsonObject.getValue("list_id").jsonPrimitive.long shouldBe MangaUpdates.WISH_LIST
    }

    @Test
    fun `binding a series already on a list keeps its list, chapter and rating`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """{"series": {"id": $SERIES, "title": "One Piece"}, "list_id": 2, "status": {"volume": 0, "chapter": 1130},
               "priority": 0, "time_added": {"timestamp": 1600000000}}""",
        )
        http.answer("""{"rating": 8.5}""")

        val bound = mangaUpdates.bind(track(), hasReadChapters = true)

        http.requests.map { it.method to it.path } shouldContainExactly listOf(
            "GET" to "/v1/lists/series/$SERIES",
            "GET" to "/v1/series/$SERIES/rating",
        )
        bound.status shouldBe MangaUpdates.COMPLETE_LIST
        bound.last_chapter_read shouldBe 1130.0
        bound.score shouldBe 8.5
    }

    @Test
    fun `progress, status and score updates send the list item, then the rating`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"success"}""")
        http.answer("""{"status":"success"}""")

        mangaUpdates.update(
            track().apply {
                status = MangaUpdates.ON_HOLD_LIST
                last_chapter_read = 40.0
                score = 8.5
            },
        )

        val (update, rating) = http.requests
        update.method shouldBe "POST"
        update.path shouldBe "/v1/lists/series/update"
        val item = update.jsonArray().single().jsonObject
        item.getValue("series").jsonObject.getValue("id").jsonPrimitive.long shouldBe SERIES
        item.getValue("list_id").jsonPrimitive.long shouldBe MangaUpdates.ON_HOLD_LIST
        item.getValue("status").jsonObject.getValue("chapter").jsonPrimitive.int shouldBe 40
        rating.method shouldBe "PUT"
        rating.path shouldBe "/v1/series/$SERIES/rating"
        rating.json().getValue("rating").jsonPrimitive.double shouldBe 8.5
        rating.headers["Authorization"] shouldBe "Bearer $SESSION"
    }

    @Test
    fun `a score of zero deletes the rating`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"success"}""")
        http.answer("""{"status":"success"}""")

        mangaUpdates.update(
            track().apply {
                status = MangaUpdates.READING_LIST
                score = 0.0
            },
        )

        http.requests[1].method shouldBe "DELETE"
        http.requests[1].path shouldBe "/v1/series/$SERIES/rating"
    }

    @Test
    fun `unbinding deletes the series from the lists`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"success"}""")
        mangaUpdates.delete(mockk<DomainTrack> { every { remoteId } returns SERIES })

        val request = http.requests.single()
        request.method shouldBe "POST"
        request.path shouldBe "/v1/lists/series/delete"
        request.jsonArray().map { it.jsonPrimitive.long } shouldContainExactly listOf(SERIES)
        request.headers["Authorization"] shouldBe "Bearer $SESSION"
    }

    @Test
    fun `an expired session fails the call with its status, without a crash`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"exception","reason":"You must be logged in."}""", code = 401)
        shouldThrow<HttpException> { mangaUpdates.delete(mockk<DomainTrack> { every { remoteId } returns SERIES }) }.code shouldBe 401
    }

    @Test
    fun `signed out, list calls stop before the network`() = runBlocking<Unit> {
        shouldThrow<IOException> { mangaUpdates.update(track().apply { status = MangaUpdates.READING_LIST }) }
            .message!! shouldContain "Not authenticated with MangaUpdates"
        http.requests.size shouldBe 0
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"exception","reason":"Internal error"}""", code = 500)
        shouldThrow<HttpException> { mangaUpdates.search("x") }.code shouldBe 500

        http.answer("""{"status":"exception","reason":"Invalid list"}""", code = 400)
        shouldThrow<HttpException> { mangaUpdates.update(track().apply { status = 99 }) }.code shouldBe 400
    }

    private companion object {
        const val SESSION = "mu-session-token"
        const val SERIES = 55099564912L
    }
}
