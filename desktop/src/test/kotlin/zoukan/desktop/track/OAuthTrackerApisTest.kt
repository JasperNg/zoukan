package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.anilist.AnilistInterceptor
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.myanimelist.MALTitleNotApproved
import eu.kanade.tachiyomi.data.track.myanimelist.MALTokenExpired
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.testing.FreshInjekt
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * AniList's search, bind, progress update and unbind, as the GraphQL requests the app sends and
 * the answers it reads, with recorded answers in place of graphql.anilist.co.
 *
 * The hosted trackers' API addresses are constants in the Android code the desktop shares
 * (AnilistApi.API_URL), so no journey can point one at the fixture server without changing that
 * code or adding an address setting. Instead the client here answers every call itself, before
 * any network: the tests check the requests' operations and variables and the mapping of the
 * answers, which is the tracker logic the shared code carries.
 */
class AnilistApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val answers = ArrayDeque<String>()

    private val client = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                request.url.host shouldBe "graphql.anilist.co"
                request.method shouldBe "POST"
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                requests += Json.parseToJsonElement(body).jsonObject
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(synchronized(answers) { answers.removeFirst() }.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private val api = AnilistApi(client, AnilistInterceptor(mockk<Anilist>(relaxed = true), "token"))

    @BeforeEach
    fun json() {
        // What the app registers (DesktopAppModule)
        Injekt.addSingleton(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    private fun answer(json: String) = synchronized(answers) { answers.addLast(json) }

    private fun JsonObject.variables(): JsonObject = getValue("variables").jsonObject

    private fun JsonObject.operation(): String = getValue("query").jsonPrimitive.content.trimStart('|').trim().substringBefore("(")

    @Test
    fun `search sends the query and maps each result to a track`() = runBlocking<Unit> {
        answer(
            """
            {"data": {"Page": {"media": [
              {"id": 30013, "title": {"userPreferred": "One Piece"}, "coverImage": {"large": "https://img/op.jpg"},
               "description": "Pirates &amp; treasure", "format": "MANGA", "status": "RELEASING",
               "startDate": {"year": 1997, "month": 7, "day": 22}, "chapters": null, "averageScore": 88,
               "staff": {"edges": [
                 {"role": "Story & Art", "node": {"name": {"userPreferred": "Eiichiro Oda", "native": null, "full": null}}}
               ]}},
              {"id": 2, "title": {"userPreferred": "One Shot"}, "coverImage": {"large": "https://img/os.jpg"},
               "description": null, "format": "ONE_SHOT", "status": null,
               "startDate": {"year": null, "month": null, "day": null}, "chapters": 1, "averageScore": null,
               "staff": {"edges": []}}
            ]}}}
            """.trimIndent(),
        )
        val results = api.search("one piece")

        val request = requests.single()
        request.operation() shouldBe "query Search"
        request.variables().getValue("query").jsonPrimitive.content shouldBe "one piece"

        results.map { it.remote_id } shouldContainExactly listOf(30013L, 2L)
        val first = results[0]
        first.tracker_id shouldBe TrackerManager.ANILIST
        first.title shouldBe "One Piece"
        first.summary shouldBe "Pirates & treasure"
        first.total_chapters shouldBe 0L
        first.score shouldBe 88.0
        first.publishing_type shouldBe "MANGA"
        first.start_date shouldBe "1997-07-22"
        first.tracking_url shouldBe AnilistApi.mangaUrl(30013)
        first.authors shouldContainExactly listOf("Eiichiro Oda")
        first.artists shouldContainExactly listOf("Eiichiro Oda")
        val second = results[1]
        second.publishing_type shouldBe "ONE-SHOT"
        second.total_chapters shouldBe 1L
        second.score shouldBe -1.0
        second.start_date shouldBe ""
    }

    @Test
    fun `binding adds the entry to the list and keeps the list entry's id`() = runBlocking<Unit> {
        answer("""{"data": {"SaveMediaListEntry": {"id": 555, "status": "CURRENT"}}}""")
        val track = Track.create(TrackerManager.ANILIST).apply {
            remote_id = 30013
            status = Anilist.READING
            last_chapter_read = 12.0
        }
        val bound = api.addLibManga(track)

        bound.library_id shouldBe 555L
        val request = requests.single()
        request.operation() shouldBe "mutation AddManga"
        val variables = request.variables()
        variables.getValue("mangaId").jsonPrimitive.long shouldBe 30013L
        variables.getValue("progress").jsonPrimitive.int shouldBe 12
        variables.getValue("status").jsonPrimitive.content shouldBe "CURRENT"
    }

    @Test
    fun `a progress update sends the list entry, the chapter and the status`() = runBlocking<Unit> {
        answer("""{"data": {"SaveMediaListEntry": {"id": 555, "status": "COMPLETED", "progress": 40}}}""")
        val track = Track.create(TrackerManager.ANILIST).apply {
            remote_id = 30013
            library_id = 555
            status = Anilist.COMPLETED
            last_chapter_read = 40.0
            score = 85.0
        }
        api.updateLibManga(track)

        val request = requests.single()
        request.operation() shouldBe "mutation UpdateManga"
        val variables = request.variables()
        variables.getValue("listId").jsonPrimitive.long shouldBe 555L
        variables.getValue("progress").jsonPrimitive.int shouldBe 40
        variables.getValue("status").jsonPrimitive.content shouldBe "COMPLETED"
        variables.getValue("score").jsonPrimitive.int shouldBe 85
    }

    @Test
    fun `unbinding deletes the list entry`() = runBlocking<Unit> {
        answer("""{"data": {"DeleteMediaListEntry": {"deleted": true}}}""")
        api.deleteLibManga(mockk<DomainTrack> { every { libraryId } returns 555L })

        val request = requests.single()
        request.operation() shouldBe "mutation DeleteManga"
        request.variables().getValue("listId").jsonPrimitive.long shouldBe 555L
    }

    @Test
    fun `an AniList error in a 200 answer fails the call with its message`() = runBlocking<Unit> {
        answer("""{"errors": [{"message": "Invalid token", "status": 400}], "data": null}""")
        shouldThrow<Exception> { api.search("x") }.message shouldContain "login again"

        answer("""{"errors": [{"message": "Too Many Requests.", "status": 429}], "data": null}""")
        shouldThrow<Exception> { api.updateLibManga(Track.create(TrackerManager.ANILIST).apply { status = Anilist.READING }) }
            .message shouldBe "Too Many Requests."
    }
}

/**
 * MyAnimeList's search, bind, update and delete, as the requests the shared tracker sends to
 * api.myanimelist.net and the answers it maps, with recorded answers in place of the network.
 */
class MyAnimeListApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val mal = MyAnimeList(1L)

    @BeforeEach
    fun setUp() {
        env.register()
    }

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        env.preferences.setCredentials(mal, "reader", ACCESS)
        env.preferences.trackToken(mal).set(
            """{"token_type":"Bearer","refresh_token":"$REFRESH","access_token":"$ACCESS","expires_in":2678400,"created_at":$createdAt}""",
        )
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(ACCESS, REFRESH, "new-access")
    }

    private fun date(text: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text)!!.time

    private fun track() = Track.create(1L).apply {
        remote_id = 2
        title = "Berserk"
        tracking_url = "https://myanimelist.net/manga/2"
    }

    @Test
    fun `search sends the query with the token in a header and maps the results, leaving out novels`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """
            {"data": [
              {"node": {"id": 2, "title": "Berserk", "synopsis": "Guts, a former mercenary",
                "num_chapters": 0, "mean": 9.47,
                "main_picture": {"medium": "https://cdn.myanimelist.net/images/manga/1/157897.jpg",
                                 "large": "https://cdn.myanimelist.net/images/manga/1/157897l.jpg"},
                "status": "currently_publishing", "media_type": "manga", "start_date": "1989-08-25",
                "authors": [{"node": {"id": 1868, "first_name": "Kentarou", "last_name": "Miura"}, "role": "Story & Art"},
                            {"node": {"id": 9, "first_name": "Kouji", "last_name": "Mori"}, "role": "Story"}]}},
              {"node": {"id": 3, "title": "Berserk: The Flame Dragon Knight", "num_chapters": 1,
                "main_picture": {"medium": "https://cdn.myanimelist.net/images/manga/3/1.jpg"},
                "status": "finished", "media_type": "light_novel"}},
              {"node": {"id": 4, "title": "Berserk Oneshot", "num_chapters": 1,
                "status": "finished", "media_type": "one_shot"}}
            ],
            "paging": {"next": "https://api.myanimelist.net/v2/manga?offset=3&q=berserk"}}
            """.trimIndent(),
        )
        val query = "berserk " + "x".repeat(80)
        val results = mal.search(query)

        val request = http.requests.single()
        request.method shouldBe "GET"
        request.url.host shouldBe "api.myanimelist.net"
        request.path shouldBe "/v2/manga"
        // MAL answers 400 to a query over 64 characters
        request.url.queryParameter("q") shouldBe query.take(64)
        request.url.queryParameter("nsfw") shouldBe "true"
        request.url.queryParameter("fields")!! shouldContain "authors{first_name,last_name}"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"

        results.map { it.remote_id } shouldContainExactly listOf(2L, 4L)
        val berserk = results[0]
        berserk.tracker_id shouldBe 1L
        berserk.title shouldBe "Berserk"
        berserk.summary shouldBe "Guts, a former mercenary"
        berserk.cover_url shouldBe "https://cdn.myanimelist.net/images/manga/1/157897l.jpg"
        berserk.score shouldBe 9.47
        berserk.total_chapters shouldBe 0L
        berserk.publishing_status shouldBe "currently publishing"
        berserk.publishing_type shouldBe "manga"
        berserk.start_date shouldBe "1989-08-25"
        berserk.tracking_url shouldBe "https://myanimelist.net/manga/2"
        berserk.authors shouldContainExactly listOf("Kentarou Miura", "Kouji Mori")
        berserk.artists shouldContainExactly emptyList()
        val oneShot = results[1]
        oneShot.publishing_type shouldBe "one shot"
        oneShot.score shouldBe -1.0
        oneShot.cover_url shouldBe ""
        oneShot.start_date shouldBe ""
    }

    @Test
    fun `binding an entry that is not on the list adds it as reading`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"id": 2, "num_chapters": 0}""")
        http.answer("""{"status": "reading", "score": 0, "num_chapters_read": 3, "is_rereading": false, "updated_at": "2026-11-16T10:00:00+00:00"}""")

        val bound = mal.bind(track().apply { last_chapter_read = 3.0 }, hasReadChapters = true)

        val (find, add) = http.requests
        find.method shouldBe "GET"
        find.path shouldBe "/v2/manga/2"
        find.url.queryParameter("fields") shouldBe "num_chapters,my_list_status{start_date,finish_date}"
        add.method shouldBe "PUT"
        add.path shouldBe "/v2/manga/2/my_list_status"
        add.headers["Authorization"] shouldBe "Bearer $ACCESS"
        val form = add.form()
        form["status"] shouldBe "reading"
        form["is_rereading"] shouldBe "false"
        form["num_chapters_read"] shouldBe "3"
        form.getValue("score").toDouble() shouldBe 0.0

        bound.remote_id shouldBe 2L
        bound.status shouldBe MyAnimeList.READING
        bound.last_chapter_read shouldBe 3.0
    }

    @Test
    fun `binding an entry already on the list keeps its progress, score and dates`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """{"id": 2, "num_chapters": 120, "my_list_status": {"status": "completed", "score": 8, "num_chapters_read": 120,
               "is_rereading": false, "start_date": "2020-01-02", "finish_date": "2020-03-04"}}""",
        )
        http.answer("""{"status": "completed", "score": 8, "num_chapters_read": 120, "is_rereading": false, "start_date": "2020-01-02", "finish_date": "2020-03-04"}""")

        val bound = mal.bind(track(), hasReadChapters = true)

        bound.total_chapters shouldBe 120L
        bound.status shouldBe MyAnimeList.COMPLETED
        bound.score shouldBe 8.0
        bound.last_chapter_read shouldBe 120.0
        bound.started_reading_date shouldBe date("2020-01-02")
        bound.finished_reading_date shouldBe date("2020-03-04")
        val form = http.requests[1].form()
        form["status"] shouldBe "completed"
        form["num_chapters_read"] shouldBe "120"
        form.getValue("score").toDouble() shouldBe 8.0
        form["start_date"] shouldBe "2020-01-02"
        form["finish_date"] shouldBe "2020-03-04"
    }

    @Test
    fun `progress, status and score updates send the list status`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status": "reading", "score": 7, "num_chapters_read": 15, "is_rereading": true}""")

        val updated = mal.update(
            track().apply {
                status = MyAnimeList.REREADING
                last_chapter_read = 15.0
                total_chapters = 120
                score = 7.0
            },
            didReadChapter = true,
        )

        val form = http.requests.single().form()
        http.requests.single().method shouldBe "PUT"
        // A re-read is "reading" with the re-reading flag
        form["status"] shouldBe "reading"
        form["is_rereading"] shouldBe "true"
        form["num_chapters_read"] shouldBe "15"
        form.getValue("score").toDouble() shouldBe 7.0
        updated.status shouldBe MyAnimeList.REREADING
        updated.score shouldBe 7.0
    }

    @Test
    fun `reading the last chapter completes the entry`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status": "completed", "score": 0, "num_chapters_read": 120, "is_rereading": false}""")

        val updated = mal.update(
            track().apply {
                status = MyAnimeList.READING
                last_chapter_read = 120.0
                total_chapters = 120
            },
            didReadChapter = true,
        )

        http.requests.single().form()["status"] shouldBe "completed"
        updated.status shouldBe MyAnimeList.COMPLETED
    }

    @Test
    fun `unbinding deletes the list status`() = runBlocking<Unit> {
        signIn()
        http.answer("[]")
        mal.delete(mockk<DomainTrack> { every { remoteId } returns 2L })

        val request = http.requests.single()
        request.method shouldBe "DELETE"
        request.path shouldBe "/v2/manga/2/my_list_status"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
    }

    @Test
    fun `an expired token is refreshed first and the new one is stored`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"token_type":"Bearer","expires_in":2678400,"access_token":"new-access","refresh_token":"new-refresh"}""")
        http.answer("""{"data": [], "paging": {}}""")

        mal.search("berserk")

        val (refresh, search) = http.requests
        refresh.method shouldBe "POST"
        refresh.url.toString() shouldBe "https://myanimelist.net/v1/oauth2/token"
        refresh.form()["grant_type"] shouldBe "refresh_token"
        refresh.form()["refresh_token"] shouldBe REFRESH
        search.headers["Authorization"] shouldBe "Bearer new-access"
        env.preferences.trackToken(mal).get() shouldContain "new-access"
    }

    @Test
    fun `a refused refresh marks the login expired and later calls stop before the network`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"error":"invalid_grant"}""", code = 401)

        // The call fails with the interceptor's MALTokenExpired message
        shouldThrow<IOException> { mal.search("berserk") }.message shouldBe MALTokenExpired().message
        env.preferences.trackAuthExpired(mal).get() shouldBe true

        shouldThrow<IOException> { mal.search("berserk") }.message shouldBe MALTokenExpired().message
        http.requests.size shouldBe 1

        // Signing in again clears it
        env.preferences.setCredentials(mal, "reader", "token")
        env.preferences.trackAuthExpired(mal).get() shouldBe false
    }

    @Test
    fun `error answers fail the call with a message`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"error":"internal_error"}""", code = 500)
        shouldThrow<HttpException> { mal.search("berserk") }.code shouldBe 500

        // An unapproved title: MAL lists it in search but refuses to add it
        http.answer("""{"message":"Invalid content","error":"invalid_content"}""", code = 400)
        shouldThrow<MALTitleNotApproved> { mal.update(track().apply { status = MyAnimeList.READING }) }

        http.answer("""{"message":"","error":"not_found"}""", code = 404)
        shouldThrow<HttpException> { mal.update(track().apply { status = MyAnimeList.READING }) }.code shouldBe 404
    }

    @Test
    fun `signed out, no call goes out`() = runBlocking<Unit> {
        shouldThrow<Exception> { mal.search("berserk") }.message!! shouldNotContain ACCESS
        http.requests.size shouldBe 0
    }

    private companion object {
        const val ACCESS = "mal-access-token"
        const val REFRESH = "mal-refresh-token"
    }
}

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

/**
 * Shikimori's search, bind, update and delete, as the requests the shared tracker sends to
 * shikimori.one and the answers it maps, with recorded answers in place of the network.
 */
class ShikimoriApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val shikimori = Shikimori(4L)

    @BeforeEach
    fun setUp() {
        env.register()
    }

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        // Shikimori keeps the user id as the user name
        env.preferences.setCredentials(shikimori, USER_ID, ACCESS)
        env.preferences.trackToken(shikimori).set(
            """{"access_token":"$ACCESS","token_type":"Bearer","created_at":$createdAt,"expires_in":86400,"refresh_token":"$REFRESH"}""",
        )
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(ACCESS, REFRESH, "new-access")
    }

    private fun track() = Track.create(4L).apply {
        remote_id = 21
        title = "One Piece"
        tracking_url = "https://shikimori.one/mangas/21-one-piece"
    }

    private val onePiece = """
        {"id": 21, "name": "One Piece", "russian": "Ван-Пис",
         "image": {"original": "/system/mangas/original/21.jpg", "preview": "/system/mangas/preview/21.jpg",
                   "x96": "/system/mangas/x96/21.jpg", "x48": "/system/mangas/x48/21.jpg"},
         "url": "/mangas/21-one-piece", "kind": "manga", "score": "8.62", "status": "ongoing",
         "volumes": 0, "chapters": 0, "aired_on": "1997-07-22", "released_on": null}
    """.trimIndent()

    @Test
    fun `search sends the query with the token in a header and maps the results`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """[$onePiece,
              {"id": 2, "name": "Berserk", "russian": "Берсерк", "image": {"original": "/o.jpg", "preview": "/system/mangas/preview/2.jpg"},
               "url": "/mangas/2-berserk", "kind": "one_shot", "score": "9.3", "status": "released",
               "volumes": 41, "chapters": 380, "aired_on": null, "released_on": "2021-09-10"}]""",
        )

        val results = shikimori.search("one piece")

        val request = http.requests.single()
        request.method shouldBe "GET"
        request.url.host shouldBe "shikimori.one"
        request.path shouldBe "/api/mangas"
        request.url.queryParameter("search") shouldBe "one piece"
        request.url.queryParameter("order") shouldBe "popularity"
        request.url.queryParameter("limit") shouldBe "20"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"

        results.map { it.remote_id } shouldContainExactly listOf(21L, 2L)
        val first = results[0]
        first.tracker_id shouldBe 4L
        first.title shouldBe "One Piece"
        first.cover_url shouldBe "https://shikimori.one/system/mangas/preview/21.jpg"
        first.tracking_url shouldBe "https://shikimori.one/mangas/21-one-piece"
        first.score shouldBe 8.62
        first.total_chapters shouldBe 0L
        first.publishing_status shouldBe "ongoing"
        first.publishing_type shouldBe "manga"
        first.start_date shouldBe "1997-07-22"
        val second = results[1]
        second.total_chapters shouldBe 380L
        second.publishing_type shouldBe "one_shot"
        second.start_date shouldBe ""
    }

    @Test
    fun `binding an entry that is not on the list adds a user rate and keeps its id`() = runBlocking<Unit> {
        signIn()
        http.answer(onePiece)
        http.answer("[]")
        http.answer(
            """{"id": 9001, "user_id": 777, "target_id": 21, "target_type": "Manga", "score": 0,
               "status": "watching", "rewatches": 0, "episodes": 0, "volumes": 0, "chapters": 3, "text": null}""",
        )

        val bound = shikimori.bind(track().apply { last_chapter_read = 3.0 }, hasReadChapters = true)

        val (manga, rates, add) = http.requests
        manga.path shouldBe "/api/mangas/21"
        rates.method shouldBe "GET"
        rates.path shouldBe "/api/v2/user_rates"
        rates.url.queryParameter("user_id") shouldBe USER_ID
        rates.url.queryParameter("target_id") shouldBe "21"
        rates.url.queryParameter("target_type") shouldBe "Manga"
        add.method shouldBe "POST"
        add.path shouldBe "/api/v2/user_rates"
        add.headers["Authorization"] shouldBe "Bearer $ACCESS"
        val rate = add.json().getValue("user_rate").jsonObject
        rate.getValue("user_id").jsonPrimitive.content shouldBe USER_ID
        rate.getValue("target_id").jsonPrimitive.long shouldBe 21L
        rate.getValue("target_type").jsonPrimitive.content shouldBe "Manga"
        rate.getValue("chapters").jsonPrimitive.int shouldBe 3
        rate.getValue("score").jsonPrimitive.int shouldBe 0
        rate.getValue("status").jsonPrimitive.content shouldBe "watching"

        bound.library_id shouldBe 9001L
        bound.remote_id shouldBe 21L
        bound.status shouldBe Shikimori.READING
    }

    @Test
    fun `binding an entry already on the list keeps its progress and score and the manga id`() = runBlocking<Unit> {
        signIn()
        http.answer(onePiece)
        http.answer(
            """[{"id": 9001, "user_id": 777, "target_id": 21, "target_type": "Manga", "score": 7,
                "status": "rewatching", "rewatches": 1, "episodes": 0, "volumes": 0, "chapters": 50}]""",
        )
        http.answer("""{"id": 9001, "user_id": 777, "target_id": 21, "target_type": "Manga", "score": 7, "status": "rewatching", "chapters": 50}""")

        val bound = shikimori.bind(track(), hasReadChapters = true)

        bound.library_id shouldBe 9001L
        // The user rate's id is the library id; the manga id stays the remote id
        bound.remote_id shouldBe 21L
        bound.status shouldBe Shikimori.REREADING
        bound.score shouldBe 7.0
        bound.last_chapter_read shouldBe 50.0
        val rate = http.requests[2].json().getValue("user_rate").jsonObject
        rate.getValue("target_id").jsonPrimitive.long shouldBe 21L
        rate.getValue("status").jsonPrimitive.content shouldBe "rewatching"
        rate.getValue("chapters").jsonPrimitive.int shouldBe 50
        rate.getValue("score").jsonPrimitive.int shouldBe 7
    }

    @Test
    fun `progress, status and score updates send the user rate`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"id": 9001, "user_id": 777, "target_id": 21, "target_type": "Manga", "score": 9, "status": "completed", "chapters": 120}""")

        shikimori.update(
            track().apply {
                library_id = 9001
                status = Shikimori.READING
                last_chapter_read = 120.0
                total_chapters = 120
                score = 9.0
            },
            didReadChapter = true,
        )

        val request = http.requests.single()
        request.method shouldBe "POST"
        val rate = request.json().getValue("user_rate").jsonObject
        rate.getValue("status").jsonPrimitive.content shouldBe "completed"
        rate.getValue("chapters").jsonPrimitive.int shouldBe 120
        rate.getValue("score").jsonPrimitive.int shouldBe 9
    }

    @Test
    fun `unbinding deletes the user rate`() = runBlocking<Unit> {
        signIn()
        http.answer("", code = 204)
        shikimori.delete(mockk<DomainTrack> { every { libraryId } returns 9001L })

        val request = http.requests.single()
        request.method shouldBe "DELETE"
        request.url.toString() shouldBe "https://shikimori.one/api/v2/user_rates/9001"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
    }

    @Test
    fun `an expired token is refreshed first and the new one is stored`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer(
            """{"access_token":"new-access","token_type":"Bearer","created_at":${System.currentTimeMillis() / 1000},
               "expires_in":86400,"refresh_token":"new-refresh","scope":"user_rates"}""",
        )
        http.answer("[]")

        shikimori.search("x")

        val (refresh, search) = http.requests
        refresh.method shouldBe "POST"
        refresh.url.toString() shouldBe "https://shikimori.one/oauth/token"
        refresh.form()["grant_type"] shouldBe "refresh_token"
        refresh.form()["refresh_token"] shouldBe REFRESH
        search.headers["Authorization"] shouldBe "Bearer new-access"
        env.preferences.trackToken(shikimori).get() shouldContain "new-access"
    }

    @Test
    fun `a refused token marks the login expired, as MyAnimeList does, until the user signs in again`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"error":"invalid_grant"}""", code = 400)
        http.answer("""{"error":"invalid_token"}""", code = 401)

        shouldThrow<IOException> { shikimori.search("x") }.message shouldBe "Shikimori: Login has expired"
        env.preferences.trackAuthExpired(shikimori).get() shouldBe true

        // Later calls stop before the network
        shouldThrow<IOException> { shikimori.search("x") }.message shouldBe "Shikimori: Login has expired"
        http.requests.size shouldBe 2

        env.preferences.setCredentials(shikimori, USER_ID, ACCESS)
        env.preferences.trackAuthExpired(shikimori).get() shouldBe false
    }

    @Test
    fun `missing tokens fail the call with an IOException of their own, not a crash on OkHttp's thread`() = runBlocking<Unit> {
        // Signed out
        shouldThrow<IOException> { shikimori.search("x") }.message shouldBe "Not authenticated with Shikimori"

        // An expired token with no refresh token
        env.preferences.trackToken(shikimori).set("""{"access_token":"$ACCESS","token_type":"Bearer","created_at":1000,"expires_in":86400}""")
        shouldThrow<IOException> { Shikimori(4L).search("x") }.message shouldBe "Shikimori: No refresh token"
        http.requests.size shouldBe 0
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"code":503}""", code = 503)
        shouldThrow<HttpException> { shikimori.search("x") }.code shouldBe 503

        http.answer("""{"errors":["Target has already been taken"]}""", code = 422)
        shouldThrow<HttpException> {
            shikimori.update(track().apply { status = Shikimori.READING })
        }.code shouldBe 422
    }

    private companion object {
        const val USER_ID = "777"
        const val ACCESS = "shikimori-access-token"
        const val REFRESH = "shikimori-refresh-token"
    }
}
