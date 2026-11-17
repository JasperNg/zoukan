package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.myanimelist.MALTitleNotApproved
import eu.kanade.tachiyomi.data.track.myanimelist.MALTokenExpired
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * MyAnimeList's search, bind, update and delete, as the requests the shared tracker sends to
 * api.myanimelist.net and the answers it maps, with recorded answers in place of the network.
 */
class MyAnimeListApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val mal = MyAnimeList(1L)

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        env.preferences.setCredentials(mal, "reader", ACCESS)
        env.preferences.trackToken(mal).set(
            """{"token_type":"Bearer","refresh_token":"$REFRESH","access_token":"$ACCESS","expires_in":2678400,"created_at":$createdAt}""",
        )
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(ACCESS, REFRESH, "new-access")
        env.close()
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
