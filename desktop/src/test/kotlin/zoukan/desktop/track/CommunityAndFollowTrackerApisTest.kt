package zoukan.desktop.track

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.online.all.MangaDex
import exh.md.handlers.FollowsHandler
import exh.md.service.MangaDexAuthService
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.mangaDexSourceIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.exh.MangaDexSync
import zoukan.desktop.testing.FreshInjekt
import java.io.IOException
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Bangumi's search, bind and update, as the requests the shared tracker sends to api.bgm.tv and
 * the answers it maps, with recorded answers in place of the network. (Bangumi has no unbind:
 * it is not a DeletableTracker, on Android either.)
 */
class BangumiApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val bangumi = Bangumi(5L)

    @BeforeEach
    fun setUp() {
        env.register()
    }

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
               "images": null, "volumes": 0, "eps": 0, "rating": {"score": null}}
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
    fun `a refused token marks the login expired, as MyAnimeList does, until the user signs in again`() = runBlocking<Unit> {
        signIn(createdAt = 1_000)
        http.answer("""{"error":"invalid_grant"}""", code = 400)
        http.answer("""{"title":"Unauthorized","description":"access token has expired"}""", code = 401)

        shouldThrow<IOException> { bangumi.update(track().apply { status = Bangumi.READING }) }
            .message shouldBe "Bangumi: Login has expired"
        env.preferences.trackAuthExpired(bangumi).get() shouldBe true

        // Later calls stop before the network
        shouldThrow<IOException> { bangumi.search("x") }.message shouldBe "Bangumi: Login has expired"
        http.requests.size shouldBe 2

        env.preferences.setCredentials(bangumi, USERNAME, ACCESS)
        env.preferences.trackAuthExpired(bangumi).get() shouldBe false
    }

    @Test
    fun `missing tokens fail the call with an IOException of their own, not a crash on OkHttp's thread`() = runBlocking<Unit> {
        // Signed out
        shouldThrow<IOException> { bangumi.search("x") }.message shouldBe "Not authenticated with Bangumi"

        // An expired token with no refresh token
        env.preferences.trackToken(bangumi).set("""{"access_token":"$ACCESS","token_type":"Bearer","created_at":1000,"expires_in":604800}""")
        shouldThrow<IOException> { Bangumi(5L).search("x") }.message shouldBe "Bangumi: No refresh token"
        http.requests.size shouldBe 0
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

/**
 * MangaUpdates' search, bind, update and delete, as the requests the shared tracker sends to
 * api.mangaupdates.com and the answers it maps, with recorded answers in place of the network.
 * MangaUpdates signs in with a session token and has no refresh: a refused token is an error.
 */
class MangaUpdatesApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val mangaUpdates = MangaUpdates(7L)

    @BeforeEach
    fun setUp() {
        env.register()
    }

    private fun signIn() {
        // The user id as the name, the session token as the password
        env.preferences.setCredentials(mangaUpdates, "4242", SESSION)
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(SESSION)
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
    fun `an expired session marks the login expired, as MyAnimeList does, until the user signs in again`() = runBlocking<Unit> {
        signIn()
        http.answer("""{"status":"exception","reason":"You must be logged in."}""", code = 401)
        shouldThrow<IOException> { mangaUpdates.delete(mockk<DomainTrack> { every { remoteId } returns SERIES }) }
            .message shouldBe "MangaUpdates: Login has expired"
        env.preferences.trackAuthExpired(mangaUpdates).get() shouldBe true

        // Later calls stop before the network
        shouldThrow<IOException> { mangaUpdates.update(track().apply { status = MangaUpdates.READING_LIST }) }
            .message shouldBe "MangaUpdates: Login has expired"
        http.requests.size shouldBe 1

        env.preferences.setCredentials(mangaUpdates, "4242", SESSION)
        env.preferences.trackAuthExpired(mangaUpdates).get() shouldBe false
    }

    @Test
    fun `a record without a type or year leaves them empty, not the text null`() = runBlocking<Unit> {
        http.answer(
            """{"total_hits": 1, "page": 1, "per_page": 25, "results": [
                 {"record": {"series_id": $SERIES, "title": "Untitled"}, "hit_title": "Untitled"}]}""",
        )

        val untitled = mangaUpdates.search("untitled").single()
        untitled.publishing_type shouldBe ""
        untitled.start_date shouldBe ""
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

/**
 * The MangaDex follow calls behind Settings > MangaDex > "Follow the library on MangaDex"
 * (MangaDexSync.pushFavorites) and MDList updates: the follow status and rating reads, follow,
 * unfollow, status and rating writes the shared FollowsHandler sends to api.mangadex.org, with
 * recorded answers in place of the network. MangaDexSettingsTest covers the dialog itself.
 */
class MangaDexFollowApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private lateinit var mdList: MdList
    private lateinit var handler: FollowsHandler
    private val mangaDex = mockk<MangaDex>()
    private val previousSourceIds = mangaDexSourceIds

    @BeforeEach
    fun setUp() {
        env.register()
        signIn()
        // MdList finds the MangaDex source through MdUtil, which reads these from Injekt
        Injekt.addSingleton<SourcePreferences>(mockk(relaxed = true))
        Injekt.addSingleton<SourceManager>(mockk(relaxed = true))
        mdList = MdList(TrackerManager.MDLIST)
        // As MangaDex builds it: its auth client carries MDList's interceptor
        val authClient = http.client.newBuilder().addInterceptor(mdList.interceptor).build()
        handler = FollowsHandler("en", MangaDexAuthService(authClient, headersOf("User-Agent", "Zoukan test")))
        coEvery { mangaDex.fetchTrackingInfo(any()) } coAnswers { handler.fetchTrackingInfo(firstArg()) }
        coEvery { mangaDex.updateFollowStatus(any(), any()) } coAnswers { handler.updateFollowStatus(firstArg(), secondArg()) }
        coEvery { mangaDex.updateRating(any()) } coAnswers { handler.updateRating(firstArg()) }
        mockkObject(MdUtil.Companion)
        every { MdUtil.getEnabledMangaDex(any(), any()) } returns mangaDex
    }

    private fun signIn(createdAt: Long = System.currentTimeMillis() / 1000) {
        env.preferences.trackToken(MdList(TrackerManager.MDLIST)).set(
            """{"token_type":"Bearer","refresh_token":"$REFRESH","access_token":"$ACCESS","expires_in":900,"created_at":$createdAt}""",
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(MdUtil.Companion)
        mangaDexSourceIds = previousSourceIds
        http.checkAllAnswered(ACCESS, REFRESH)
    }

    private fun answerRemote(status: String?, rating: Int?) {
        http.on("GET", "/manga/$UUID/status", """{"result": "ok", "status": ${status?.let { "\"$it\"" } ?: "null"}}""")
        http.on(
            "GET",
            "/rating",
            if (rating == null) {
                // MangaDex sends an empty array, not an object, when there is no rating
                """{"result": "ok", "ratings": []}"""
            } else {
                """{"result": "ok", "ratings": {"$UUID": {"rating": $rating, "createdAt": "2024-01-01T00:00:00+00:00"}}}"""
            },
        )
    }

    private fun track(status: FollowStatus, score: Double = 0.0) = Track.create(TrackerManager.MDLIST).apply {
        manga_id = 5
        title = "Berserk"
        tracking_url = "https://mangadex.org/manga/$UUID"
        this.status = status.long
        this.score = score
    }

    private fun RecordedHttp.Call.mangaDexStatus() = json().getValue("status")

    @Test
    fun `following the library follows each unfollowed entry as reading and stores the status`() = runBlocking<Unit> {
        answerRemote(status = null, rating = null)
        http.on("POST", "/manga/$UUID/follow", """{"result": "ok"}""")
        http.on("POST", "/manga/$UUID/status", """{"result": "ok"}""")
        mangaDexSourceIds = listOf(MANGADEX_SOURCE)
        val entry = mockk<Manga>(relaxed = true) {
            every { id } returns 5L
            every { source } returns MANGADEX_SOURCE
            every { url } returns "/manga/$UUID"
            every { title } returns "Berserk"
        }
        val other = mockk<Manga>(relaxed = true) { every { source } returns 1L }
        val inserted = slot<DomainTrack>()
        val insertTrack = mockk<InsertTrack> { coEvery { await(capture(inserted)) } returns Unit }
        val sync = MangaDexSync(
            sourcePreferences = mockk(),
            sourceManager = mockk(),
            trackerManager = mockk { every { mdList } returns this@MangaDexFollowApiTest.mdList },
            getManga = mockk(),
            getFavorites = mockk<GetFavorites> { coEvery { await() } returns listOf(entry, other) },
            networkToLocalManga = mockk(),
            updateManga = mockk(),
            updateMangaFromRemote = mockk(),
            insertFlatMetadata = mockk(),
            getTracks = mockk<GetTracks> { coEvery { await(5L) } returns emptyList() },
            insertTrack = insertTrack,
        )

        sync.pushFavorites() shouldBe 1

        http.requests.map { it.method to it.path } shouldContainExactlyInAnyOrder listOf(
            "GET" to "/manga/$UUID/status",
            "GET" to "/rating",
            "POST" to "/manga/$UUID/follow",
            "POST" to "/manga/$UUID/status",
        )
        http.requests.forEach {
            it.url.host shouldBe "api.mangadex.org"
            it.headers["Authorization"] shouldBe "Bearer $ACCESS"
        }
        http.requests.single { it.path == "/rating" }.url.queryParameterValues("manga[]") shouldContainExactly listOf(UUID)
        // The status is written after the follow
        val writes = http.requests.filter { it.method == "POST" }
        writes.map { it.path } shouldContainExactly listOf("/manga/$UUID/follow", "/manga/$UUID/status")
        writes[1].mangaDexStatus().jsonPrimitive.content shouldBe "reading"
        // No rating to change: no rating call
        http.requests.none { it.path.startsWith("/rating/") } shouldBe true

        inserted.captured.trackerId shouldBe TrackerManager.MDLIST
        inserted.captured.mangaId shouldBe 5L
        inserted.captured.status shouldBe FollowStatus.READING.long
        inserted.captured.remoteUrl shouldBe "https://mangadex.org/manga/$UUID"
    }

    @Test
    fun `binding reads the follow status and rating from MangaDex`() = runBlocking<Unit> {
        answerRemote(status = "on_hold", rating = 7)

        val bound = mdList.bind(track(FollowStatus.UNFOLLOWED), hasReadChapters = true)

        bound.status shouldBe FollowStatus.ON_HOLD.long
        bound.score shouldBe 7.0
        // Already followed with that status and rating: nothing to write
        http.requests.all { it.method == "GET" } shouldBe true
    }

    @Test
    fun `unfollowing deletes the follow and clears the reading status`() = runBlocking<Unit> {
        answerRemote(status = "reading", rating = null)
        http.on("DELETE", "/manga/$UUID/follow", """{"result": "ok"}""")
        http.on("POST", "/manga/$UUID/status", """{"result": "ok"}""")

        val updated = mdList.update(track(FollowStatus.UNFOLLOWED))

        val writes = http.requests.filter { it.method != "GET" }
        writes.map { it.method to it.path } shouldContainExactly listOf(
            "DELETE" to "/manga/$UUID/follow",
            "POST" to "/manga/$UUID/status",
        )
        writes[1].mangaDexStatus() shouldBe JsonNull
        updated.status shouldBe FollowStatus.UNFOLLOWED.long
    }

    @Test
    fun `a status MangaDex does not accept leaves the entry at its remote status`() = runBlocking<Unit> {
        answerRemote(status = "plan_to_read", rating = null)
        http.on("POST", "/manga/$UUID/follow", """{"result": "ok"}""")
        http.on("POST", "/manga/$UUID/status", """{"result": "error", "errors": [{"status": 400, "title": "Bad status"}]}""")

        val updated = mdList.update(track(FollowStatus.DROPPED))

        updated.status shouldBe FollowStatus.PLAN_TO_READ.long
    }

    @Test
    fun `a new score is posted and a cleared one deleted`() = runBlocking<Unit> {
        answerRemote(status = "reading", rating = 7)
        http.on("POST", "/rating/$UUID", """{"result": "ok"}""")
        mdList.update(track(FollowStatus.READING, score = 9.0))
        val post = http.requests.single { it.path == "/rating/$UUID" }
        post.method shouldBe "POST"
        post.json().getValue("rating").jsonPrimitive.int shouldBe 9

        http.on("DELETE", "/rating/$UUID", """{"result": "ok"}""")
        mdList.update(track(FollowStatus.READING, score = 0.0))
        http.requests.last { it.path == "/rating/$UUID" }.method shouldBe "DELETE"
    }

    @Test
    fun `an expired access token is refreshed once and the call retried with the new one`() = runBlocking<Unit> {
        http.answer(
            """{"result": "error", "errors": [{"status": 401}]}""",
            code = 401,
            headers = mapOf("WWW-Authenticate" to """Bearer error="invalid_token", error_description="The access token expired""""),
        )
        http.answer("""{"token_type":"Bearer","refresh_token":"new-refresh","access_token":"new-access","expires_in":900}""")
        http.answer("""{"result": "ok"}""")
        http.answer("""{"result": "ok"}""")

        handler.updateFollowStatus(UUID, FollowStatus.READING) shouldBe true

        val (first, refresh, retry, status) = http.requests
        first.headers["Authorization"] shouldBe "Bearer $ACCESS"
        refresh.method shouldBe "POST"
        refresh.url.toString() shouldBe "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"
        refresh.form()["grant_type"] shouldBe "refresh_token"
        refresh.form()["refresh_token"] shouldBe REFRESH
        retry.headers["Authorization"] shouldBe "Bearer new-access"
        status.headers["Authorization"] shouldBe "Bearer new-access"
        mdList.isLoggedIn shouldBe true
    }

    @Test
    fun `an expired token that cannot be refreshed signs out of MDList, without a crash`() = runBlocking<Unit> {
        mdList.isLoggedIn shouldBe true
        http.answer(
            """{"result": "error"}""",
            code = 401,
            headers = mapOf("WWW-Authenticate" to """Bearer error="invalid_token", error_description="The access token expired""""),
        )
        http.answer("""{"error": "invalid_grant", "error_description": "Token is not active"}""", code = 400)

        shouldThrow<HttpException> { handler.updateFollowStatus(UUID, FollowStatus.READING) }.code shouldBe 401

        mdList.isLoggedIn shouldBe false
        env.preferences.trackToken(mdList).isSet() shouldBe false
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        http.answer("""{"result": "error", "errors": [{"status": 503}]}""", code = 503)
        shouldThrow<HttpException> { handler.updateFollowStatus(UUID, FollowStatus.READING) }.code shouldBe 503
    }

    private companion object {
        const val UUID = "801513ba-a712-498c-8f57-cae55b38cc92"
        const val MANGADEX_SOURCE = 2499283573021220255L
        const val ACCESS = "md-access-token"
        const val REFRESH = "md-refresh-token"
    }
}
