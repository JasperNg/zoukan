package zoukan.desktop.track

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
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
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
import tachiyomi.domain.track.model.Track as DomainTrack

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
