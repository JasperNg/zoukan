package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Shikimori's search, bind, update and delete, as the requests the shared tracker sends to
 * shikimori.one and the answers it maps, with recorded answers in place of the network.
 */
class ShikimoriApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val shikimori = Shikimori(4L)

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
        env.close()
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
