package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import zoukan.desktop.testing.FreshInjekt
import java.io.IOException
import java.util.TimeZone
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Kitsu's search (through Algolia), bind, update and delete, as the JSON:API requests the shared
 * tracker sends to kitsu.app and the answers it maps, with recorded answers in place of the network.
 */
class KitsuApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val kitsu = Kitsu(TrackerManager.KITSU)

    @BeforeEach
    fun setUp() {
        env.register()
    }

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        // Kitsu keeps the user id as the "password"
        env.preferences.setCredentials(kitsu, "reader", USER_ID)
        env.preferences.trackToken(kitsu).set(
            """{"access_token":"$ACCESS","token_type":"Bearer","created_at":$createdAt,"expires_in":2592000,"refresh_token":"$REFRESH"}""",
        )
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(ACCESS, REFRESH, "new-access")
    }

    private fun track() = Track.create(TrackerManager.KITSU).apply {
        remote_id = 12
        title = "One Piece"
        tracking_url = "https://kitsu.app/manga/12"
    }

    @Test
    fun `search gets the Algolia key from Kitsu, then searches Algolia without the Kitsu token`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"media": {"key": "algolia-search-key", "index": "production_media"}}""")
        http.answer(
            """
            {"hits": [
              {"id": 12, "canonicalTitle": "One Piece", "chapterCount": null, "subtype": "manga",
               "posterImage": {"original": "https://media.kitsu.app/manga/poster_images/12/original.jpg"},
               "synopsis": "Gol D. Roger was known as the Pirate King.", "averageRating": 84.12,
               "startDate": 869572800, "endDate": null},
              {"id": 13, "canonicalTitle": "Some Light Novel", "chapterCount": 10, "subtype": "novel",
               "posterImage": null, "synopsis": null, "averageRating": null, "startDate": null, "endDate": 1500000000},
              {"id": 14, "canonicalTitle": "Wanted!", "chapterCount": 5, "subtype": "oneshot",
               "posterImage": null, "synopsis": null, "averageRating": null, "startDate": null, "endDate": 900000000}
            ], "nbHits": 3}
            """.trimIndent(),
        )

        val results = kitsu.search("one piece")

        val (key, algolia) = http.requests
        key.method shouldBe "GET"
        key.url.toString() shouldBe "https://kitsu.app/api/edge/algolia-keys/media/"
        key.headers["Authorization"] shouldBe "Bearer $ACCESS"
        algolia.method shouldBe "POST"
        algolia.url.host shouldBe "awqo5j657s-dsn.algolia.net"
        algolia.path shouldBe "/1/indexes/production_media/query/"
        algolia.headers["X-Algolia-API-Key"] shouldBe "algolia-search-key"
        algolia.headers["X-Algolia-Application-Id"] shouldBe "AWQO5J657S"
        // The Kitsu token is never sent to Algolia
        algolia.headers["Authorization"].shouldBeNull()
        val params = algolia.json().getValue("params").jsonPrimitive.content
        params shouldStartWith "query=one+piece&"
        params shouldContain "facetFilters=%5B%22kind%3Amanga%22%5D"

        results.map { it.remote_id } shouldContainExactly listOf(12L, 14L)
        val onePiece = results[0]
        onePiece.tracker_id shouldBe TrackerManager.KITSU
        onePiece.title shouldBe "One Piece"
        onePiece.cover_url shouldBe "https://media.kitsu.app/manga/poster_images/12/original.jpg"
        onePiece.summary shouldBe "Gol D. Roger was known as the Pirate King."
        onePiece.score shouldBe 84.12
        onePiece.total_chapters shouldBe 0L
        onePiece.publishing_status shouldBe "Publishing"
        onePiece.publishing_type shouldBe "manga"
        onePiece.start_date shouldBe "1997-07-22"
        onePiece.tracking_url shouldBe "https://kitsu.app/manga/12"
        val wanted = results[1]
        wanted.publishing_status shouldBe "Finished"
        wanted.score shouldBe -1.0
        wanted.cover_url shouldBe ""
        wanted.start_date shouldBe ""
    }

    @Test
    fun `binding an entry that is not on the list creates a library entry and keeps its id`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"data": [], "meta": {"count": 0}, "links": {}}""")
        http.answer(
            """{"data": {"id": "5555", "type": "libraryEntries",
               "attributes": {"status": "current", "progress": 3, "private": false}}}""",
        )

        val bound = kitsu.bind(track().apply { last_chapter_read = 3.0 }, hasReadChapters = true)

        val (find, add) = http.requests
        find.method shouldBe "GET"
        find.path shouldBe "/api/edge/library-entries"
        find.url.queryParameter("filter[manga_id]") shouldBe "12"
        find.url.queryParameter("filter[user_id]") shouldBe USER_ID
        find.url.queryParameter("include") shouldBe "manga"
        add.method shouldBe "POST"
        add.url.toString() shouldBe "https://kitsu.app/api/edge/library-entries"
        add.headers["Authorization"] shouldBe "Bearer $ACCESS"
        add.headers["Content-Type"]!! shouldStartWith "application/vnd.api+json"
        val data = add.json().getValue("data").jsonObject
        data.getValue("type").jsonPrimitive.content shouldBe "libraryEntries"
        val attributes = data.getValue("attributes").jsonObject
        attributes.getValue("status").jsonPrimitive.content shouldBe "current"
        attributes.getValue("progress").jsonPrimitive.int shouldBe 3
        attributes.getValue("private").jsonPrimitive.boolean shouldBe false
        val relationships = data.getValue("relationships").jsonObject
        relationships.getValue("user").jsonObject.getValue("data").jsonObject.getValue("id").jsonPrimitive.content shouldBe USER_ID
        relationships.getValue("media").jsonObject.getValue("data").jsonObject.getValue("id").jsonPrimitive.long shouldBe 12L

        bound.library_id shouldBe 5555L
        bound.remote_id shouldBe 12L
        bound.status shouldBe Kitsu.READING
    }

    @Test
    fun `binding an entry already on the list keeps its progress and score, then updates it`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """
            {"data": [{"id": "5555", "type": "libraryEntries",
               "attributes": {"status": "completed", "progress": 100, "ratingTwenty": 16,
                              "startedAt": "2020-01-02T00:00:00.000Z", "finishedAt": null, "private": true}}],
             "included": [{"id": "12", "type": "manga",
               "attributes": {"canonicalTitle": "One Piece", "chapterCount": null, "mangaType": "manga",
                              "posterImage": {"original": "https://media.kitsu.app/p.jpg"}, "synopsis": "Pirates",
                              "startDate": "1997-07-22", "status": "current"}}]}
            """.trimIndent(),
        )
        http.answer("""{"data": {"id": "5555", "type": "libraryEntries"}}""")

        val bound = kitsu.bind(track(), hasReadChapters = true)

        bound.library_id shouldBe 5555L
        bound.status shouldBe Kitsu.COMPLETED
        bound.last_chapter_read shouldBe 100.0
        bound.score shouldBe 8.0
        // The remote "private" flag is not copied on bind: the app's choice stays
        bound.private shouldBe false
        val update = http.requests[1]
        update.method shouldBe "PATCH"
        update.path shouldBe "/api/edge/library-entries/5555"
        val attributes = update.json().getValue("data").jsonObject.getValue("attributes").jsonObject
        attributes.getValue("status").jsonPrimitive.content shouldBe "completed"
        attributes.getValue("progress").jsonPrimitive.int shouldBe 100
        attributes.getValue("ratingTwenty").jsonPrimitive.content shouldBe "16"
        attributes.getValue("startedAt").jsonPrimitive.content shouldBe "2020-01-02T00:00:00.000Z"
    }

    @Test
    fun `progress, status and score updates patch the library entry`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"data": {"id": "5555", "type": "libraryEntries"}}""")

        kitsu.update(
            track().apply {
                library_id = 5555
                status = Kitsu.ON_HOLD
                last_chapter_read = 40.0
                score = 0.0
                private = true
            },
        )

        val request = http.requests.single()
        request.method shouldBe "PATCH"
        request.path shouldBe "/api/edge/library-entries/5555"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
        val data = request.json().getValue("data").jsonObject
        data.getValue("id").jsonPrimitive.long shouldBe 5555L
        val attributes = data.getValue("attributes").jsonObject
        attributes.getValue("status").jsonPrimitive.content shouldBe "on_hold"
        attributes.getValue("progress").jsonPrimitive.int shouldBe 40
        // No score clears the rating
        attributes.getValue("ratingTwenty") shouldBe JsonNull
        attributes.getValue("startedAt") shouldBe JsonNull
        attributes.getValue("private").jsonPrimitive.boolean shouldBe true
    }

    @Test
    fun `unbinding deletes the library entry`() = runBlocking<Unit> {
        signIn()
        http.answer("", code = 204)
        kitsu.delete(mockk<DomainTrack> { every { libraryId } returns 5555L })

        val request = http.requests.single()
        request.method shouldBe "DELETE"
        request.url.toString() shouldBe "https://kitsu.app/api/edge/library-entries/5555"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
    }

    @Test
    fun `an expired token is refreshed first and the new one is stored`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer(
            """{"access_token":"new-access","token_type":"Bearer","created_at":${System.currentTimeMillis() / 1000},
               "expires_in":2592000,"refresh_token":"new-refresh","scope":"public"}""",
        )
        http.answer("""{"data": {"id": "5555", "type": "libraryEntries"}}""", code = 204)

        kitsu.delete(mockk<DomainTrack> { every { libraryId } returns 5555L })

        val (refresh, delete) = http.requests
        refresh.method shouldBe "POST"
        refresh.url.toString() shouldBe "https://kitsu.app/api/oauth/token"
        refresh.form()["grant_type"] shouldBe "refresh_token"
        refresh.form()["refresh_token"] shouldBe REFRESH
        delete.headers["Authorization"] shouldBe "Bearer new-access"
        env.preferences.trackToken(kitsu).get() shouldContain "new-access"
    }

    @Test
    fun `a refused refresh marks the login expired and later calls stop before the network`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"error":"invalid_grant"}""", code = 401)

        // As MyAnimeList does: the call fails with an IOException that says the login expired
        shouldThrow<IOException> { kitsu.search("one piece") }.message shouldBe "Kitsu: Login has expired"
        env.preferences.trackAuthExpired(kitsu).get() shouldBe true

        shouldThrow<IOException> { kitsu.search("one piece") }.message shouldBe "Kitsu: Login has expired"
        http.requests.size shouldBe 1
    }

    @Test
    fun `a revoked token marks the login expired, and signing in again clears it`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"errors":[{"title":"Invalid token","status":"401"}]}""", code = 401)

        shouldThrow<IOException> { kitsu.search("one piece") }.message shouldBe "Kitsu: Login has expired"
        env.preferences.trackAuthExpired(kitsu).get() shouldBe true

        // Signing in sets the new token before it reads the user id with it
        http.answer("""{"access_token":"new-access","token_type":"Bearer","created_at":${System.currentTimeMillis() / 1000},"expires_in":2592000,"refresh_token":"new-refresh"}""")
        http.answer("""{"data": [{"id": "$USER_ID"}]}""")
        kitsu.login("reader", "password")

        env.preferences.trackAuthExpired(kitsu).get() shouldBe false
        http.requests.last().headers["Authorization"] shouldBe "Bearer new-access"
    }

    @Test
    fun `missing tokens fail the call with an IOException of their own, not a crash on OkHttp's thread`() = runBlocking<Unit> {
        // Signed out
        shouldThrow<IOException> { kitsu.search("one piece") }.message shouldBe "Not authenticated with Kitsu"

        // An expired token with no refresh token
        env.preferences.trackToken(kitsu).set("""{"access_token":"$ACCESS","token_type":"Bearer","created_at":1000,"expires_in":2592000}""")
        shouldThrow<IOException> { Kitsu(TrackerManager.KITSU).search("one piece") }.message shouldBe "Kitsu: No refresh token"
        http.requests.size shouldBe 0
    }

    @Test
    fun `the start date is Algolia's UTC day in every time zone`() = runBlocking<Unit> {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            signIn()
            http.answer("""{"media": {"key": "algolia-search-key", "index": "production_media"}}""")
            // Recorded from Algolia: One Piece's start date, 1997-07-22, as midnight UTC in seconds
            http.answer(
                """{"hits": [{"id": 38, "canonicalTitle": "One Piece", "chapterCount": null, "subtype": "manga",
                   "posterImage": null, "synopsis": null, "averageRating": null, "startDate": 869529600, "endDate": null}]}""",
            )

            kitsu.search("one piece").single().start_date shouldBe "1997-07-22"
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"errors":[{"title":"Internal Server Error","status":"500"}]}""", code = 500)
        shouldThrow<HttpException> { kitsu.search("one piece") }.code shouldBe 500

        http.answer("""{"errors":[{"title":"Not Found","status":"404"}]}""", code = 404)
        shouldThrow<HttpException> {
            kitsu.update(
                track().apply {
                    library_id = 1
                    status = Kitsu.READING
                },
            )
        }.code shouldBe 404

        // An unknown list status in a 200 answer fails the refresh rather than storing a wrong status
        http.answer(
            """{"data": [{"id": "5555", "attributes": {"status": "archived", "progress": 1, "private": false}}],
               "included": [{"id": "12", "attributes": {"canonicalTitle": "One Piece", "status": "current"}}]}""",
        )
        shouldThrow<Exception> { kitsu.refresh(track().apply { library_id = 5555 }) }.message shouldBe "Unknown status"
    }

    private companion object {
        const val USER_ID = "424242"
        const val ACCESS = "kitsu-access-token"
        const val REFRESH = "kitsu-refresh-token"
    }
}
