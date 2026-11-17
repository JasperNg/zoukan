package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Bangumi's search, bind and update, as the requests the shared tracker sends to api.bgm.tv and
 * the answers it maps, with recorded answers in place of the network. (Bangumi has no unbind:
 * it is not a DeletableTracker, on Android either.)
 */
class BangumiApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val bangumi = Bangumi(5L)

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        env.preferences.setCredentials(bangumi, USERNAME, ACCESS)
        env.preferences.trackToken(bangumi).set(
            """{"access_token":"$ACCESS","token_type":"Bearer","created_at":$createdAt,"expires_in":604800,
               "refresh_token":"$REFRESH","user_id":123}""",
        )
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(ACCESS, REFRESH, "new-access")
        env.close()
    }

    private fun track() = Track.create(5L).apply {
        remote_id = 46036
        title = "海贼王"
        tracking_url = "https://bangumi.tv/subject/46036"
    }

    @Test
    fun `search posts the keyword for books and maps manga results, leaving out other platforms`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """
            {"total": 3, "limit": 20, "offset": 0, "data": [
              {"id": 46036, "type": 1, "name": "ONE PIECE", "name_cn": "海贼王", "summary": "  伟大航路的故事。  ",
               "date": "1997-07-22", "platform": "漫画", "nsfw": false, "locked": false,
               "images": {"large": "https://lain.bgm.tv/pic/cover/l/a.jpg", "common": "https://lain.bgm.tv/r/400/pic/cover/l/a.jpg"},
               "volumes": 107, "eps": 1100, "rating": {"rank": 10, "total": 5000, "count": {}, "score": 8.9},
               "infobox": [{"key": "作者", "value": "尾田栄一郎"}, {"key": "别名", "value": [{"v": "One Piece"}]}], "tags": []},
              {"id": 2, "type": 1, "name": "Some Novel", "name_cn": "", "summary": "s", "date": null, "platform": "小说",
               "images": null, "volumes": 1, "eps": 0, "rating": null, "infobox": []},
              {"id": 3, "type": 1, "name": "Untitled", "name_cn": "", "summary": " plain ", "date": null, "platform": "漫画",
               "images": null, "volumes": 0, "eps": 0, "rating": {"score": null}, "infobox": []}
            ]}
            """.trimIndent(),
        )

        val results = bangumi.search("one piece")

        val request = http.requests.single()
        request.method shouldBe "POST"
        request.url.host shouldBe "api.bgm.tv"
        request.path shouldBe "/v0/search/subjects"
        request.url.queryParameter("limit") shouldBe "20"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
        request.headers["Content-Type"]!! shouldStartWith "application/json"
        val body = request.json()
        body.getValue("keyword").jsonPrimitive.content shouldBe "one piece"
        body.getValue("sort").jsonPrimitive.content shouldBe "match"
        body.getValue("filter").jsonObject.getValue("type").jsonArray.map { it.jsonPrimitive.int } shouldContainExactly listOf(1)

        results.map { it.remote_id } shouldContainExactly listOf(46036L, 3L)
        val onePiece = results[0]
        onePiece.tracker_id shouldBe 5L
        onePiece.title shouldBe "海贼王"
        onePiece.summary shouldBe "作品原名：ONE PIECE\n伟大航路的故事。"
        onePiece.cover_url shouldBe "https://lain.bgm.tv/r/400/pic/cover/l/a.jpg"
        onePiece.score shouldBe 8.9
        onePiece.total_chapters shouldBe 1100L
        onePiece.start_date shouldBe "1997-07-22"
        onePiece.tracking_url shouldBe "https://bangumi.tv/subject/46036"
        val untitled = results[1]
        untitled.title shouldBe "Untitled"
        untitled.summary shouldBe "plain"
        untitled.score shouldBe -1.0
        untitled.cover_url shouldBe ""
        untitled.start_date shouldBe ""
    }

    @Test
    fun `binding an entry that is not collected adds it as reading`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"title":"Not Found","description":"subject is not collected by user"}""", code = 404)
        http.answer("", code = 202)

        val bound = bangumi.bind(track().apply { last_chapter_read = 3.0 }, hasReadChapters = true)

        val (status, add) = http.requests
        status.method shouldBe "GET"
        status.path shouldBe "/v0/users/$USERNAME/collections/46036"
        add.method shouldBe "POST"
        add.path shouldBe "/v0/users/-/collections/46036"
        add.headers["Authorization"] shouldBe "Bearer $ACCESS"
        val body = add.json()
        body.getValue("type").jsonPrimitive.int shouldBe 3
        body.getValue("rate").jsonPrimitive.int shouldBe 0
        body.getValue("ep_status").jsonPrimitive.int shouldBe 3
        body.getValue("private").jsonPrimitive.boolean shouldBe false

        bound.remote_id shouldBe 46036L
        bound.status shouldBe Bangumi.READING
    }

    @Test
    fun `binding a collected entry keeps its progress, score and chapter count`() = runBlocking<Unit> {
        signIn()
        http.answer(
            """{"subject_id": 46036, "subject_type": 1, "rate": 8, "type": 2, "tags": [], "ep_status": 100, "vol_status": 10,
               "updated_at": "2026-01-01T00:00:00Z", "private": true,
               "subject": {"id": 46036, "name": "ONE PIECE", "name_cn": "海贼王", "eps": 120, "volumes": 10, "score": 8.9}}""",
        )
        http.answer("", code = 204)

        val bound = bangumi.bind(track(), hasReadChapters = true)

        bound.status shouldBe Bangumi.COMPLETED
        bound.score shouldBe 8.0
        bound.last_chapter_read shouldBe 100.0
        bound.total_chapters shouldBe 120L
        val update = http.requests[1]
        update.method shouldBe "PATCH"
        update.path shouldBe "/v0/users/-/collections/46036"
        update.json().getValue("type").jsonPrimitive.int shouldBe 2
        update.json().getValue("ep_status").jsonPrimitive.int shouldBe 100
        update.json().getValue("rate").jsonPrimitive.int shouldBe 8
    }

    @Test
    fun `progress, status and score updates patch the collection, the score kept in range`() = runBlocking<Unit> {
        signIn()
        http.answer("", code = 204)

        bangumi.update(
            track().apply {
                status = Bangumi.ON_HOLD
                last_chapter_read = 40.0
                score = 12.0
                private = true
            },
        )

        val request = http.requests.single()
        request.method shouldBe "PATCH"
        request.headers["Authorization"] shouldBe "Bearer $ACCESS"
        val body = request.json()
        body.getValue("type").jsonPrimitive.int shouldBe 4
        body.getValue("ep_status").jsonPrimitive.int shouldBe 40
        body.getValue("rate").jsonPrimitive.int shouldBe 10
        body.getValue("private").jsonPrimitive.boolean shouldBe true
    }

    @Test
    fun `an expired token is refreshed first and the new one is stored`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"access_token":"new-access","token_type":"Bearer","expires_in":604800,"refresh_token":"new-refresh","user_id":123}""")
        http.answer("", code = 204)

        bangumi.update(track().apply { status = Bangumi.READING })

        val (refresh, update) = http.requests
        refresh.method shouldBe "POST"
        refresh.url.toString() shouldBe "https://bgm.tv/oauth/access_token"
        refresh.form()["grant_type"] shouldBe "refresh_token"
        refresh.form()["refresh_token"] shouldBe REFRESH
        update.headers["Authorization"] shouldBe "Bearer new-access"
        env.preferences.trackToken(bangumi).get() shouldContain "new-access"
    }

    @Test
    fun `a refused token fails the call with its status, without a crash`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"error":"invalid_grant"}""", code = 400)
        http.answer("""{"title":"Unauthorized","description":"access token has expired"}""", code = 401)

        shouldThrow<HttpException> { bangumi.update(track().apply { status = Bangumi.READING }) }.code shouldBe 401
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"title":"Internal Server Error"}""", code = 500)
        shouldThrow<HttpException> { bangumi.search("x") }.code shouldBe 500

        // A collection status other than 404 is an error, not "not collected"
        http.answer("""{"title":"Forbidden"}""", code = 403)
        shouldThrow<HttpException> { bangumi.bind(track(), hasReadChapters = false) }.code shouldBe 403
        http.requests.last().method shouldBe "GET"
    }

    private companion object {
        const val USERNAME = "reader"
        const val ACCESS = "bangumi-access-token"
        const val REFRESH = "bangumi-refresh-token"
    }
}
