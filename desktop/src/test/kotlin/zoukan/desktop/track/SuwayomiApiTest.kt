package zoukan.desktop.track

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/**
 * Suwayomi, the self-hosted tracker: the GraphQL calls it sends to the user's Suwayomi server
 * through the Suwayomi (Tachidesk) source's own client, and how it maps the answers, with recorded
 * answers in place of the server. Suwayomi has no search, list entry or unbind.
 */
class SuwayomiApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private var deleteDownloads = false

    init {
        val preferences = mockk<SharedPreferences> {
            every { getBoolean("Tracker Delete", false) } answers { deleteDownloads }
        }
        Injekt.addSingleton<Application>(mockk { every { getSharedPreferences(any(), any()) } returns preferences })
        // The extension's client adds the server's basic auth
        val sourceClient = http.client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic("reader", PASSWORD)).build())
            }
            .build()
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConfigurableSource::class)) {
            every { client } returns sourceClient
            every { baseUrl } returns "$SERVER/"
            every { id } returns 3100117499L
        }
        Injekt.addSingleton<SourceManager>(mockk { every { get(any()) } returns source })
    }

    private val suwayomi = Suwayomi(9L)

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(PASSWORD, https = false)
        env.close()
    }

    private fun answerManga(unread: Int = 368, latestRead: String = """{"lastReadAt": "1700000000", "chapterNumber": 12.0}""") {
        http.answer(
            """
            {"data": {"manga": {"artist": "Kentarou Miura", "author": "Kentarou Miura", "description": "Guts, a mercenary",
              "id": 42, "status": "ONGOING", "thumbnailUrl": "/api/v1/manga/42/thumbnail", "title": "Berserk",
              "url": "/manga/2", "genre": ["Action", "Dark Fantasy"], "inLibraryAt": "1700000000",
              "chapters": {"totalCount": 380}, "latestUploadedChapter": {"uploadDate": "1700000000000"},
              "latestFetchedChapter": {"fetchedAt": "1700000000"}, "latestReadChapter": $latestRead,
              "unreadCount": $unread, "downloadCount": 3}}}
            """.trimIndent(),
        )
    }

    @Test
    fun `matching an entry reads the manga from the server's GraphQL API`() = runBlocking<Unit> {
        answerManga()

        val found = suwayomi.api.getTrackSearch(42)

        val request = http.requests.single()
        request.method shouldBe "POST"
        request.url.toString() shouldBe "$SERVER/api/graphql"
        request.headers["Authorization"]!! shouldStartWith "Basic "
        val body = request.json()
        body.getValue("query").jsonPrimitive.content shouldStartWith "query GetManga("
        body.getValue("query").jsonPrimitive.content shouldContain "fragment MangaFragment on MangaType"
        body.getValue("variables").jsonObject.getValue("mangaId").jsonPrimitive.int shouldBe 42

        found.tracker_id shouldBe 9L
        found.remote_id shouldBe 42L
        found.title shouldBe "Berserk"
        found.summary shouldBe "Guts, a mercenary"
        found.cover_url shouldStartWith SERVER
        found.cover_url shouldEndWith "/api/v1/manga/42/thumbnail"
        found.tracking_url shouldBe "$SERVER/manga/42"
        found.total_chapters shouldBe 380L
        found.publishing_status shouldBe "ONGOING"
        found.last_chapter_read shouldBe 12.0
        found.status shouldBe Suwayomi.READING
    }

    @Test
    fun `the unread count gives the status`() = runBlocking<Unit> {
        answerManga(unread = 380, latestRead = "null")
        suwayomi.api.getTrackSearch(42).let {
            it.status shouldBe Suwayomi.UNREAD
            it.last_chapter_read shouldBe 0.0
        }
        answerManga(unread = 0)
        suwayomi.api.getTrackSearch(42).status shouldBe Suwayomi.COMPLETED
    }

    @Test
    fun `a progress update marks the unread chapters up to the read one, tracks, then reads the manga back`() = runBlocking<Unit> {
        http.answer(
            """{"data": {"chapters": {"nodes": [{"id": 100, "chapterNumber": 12.0}, {"id": 101, "chapterNumber": 13.0},
               {"id": 102, "chapterNumber": 14.0}]}}}""",
        )
        http.answer("""{"data": {"updateChapters": {"__typename": "UpdateChaptersPayload"}}}""")
        http.answer("""{"data": {"trackProgress": {"__typename": "TrackProgressPayload"}}}""")
        answerManga(unread = 367, latestRead = """{"lastReadAt": "1700000000", "chapterNumber": 13.0}""")

        val track = Track.create(9L).apply {
            remote_id = 42
            title = "Berserk"
            tracking_url = "$SERVER/manga/42"
            status = Suwayomi.READING
            last_chapter_read = 13.0
            total_chapters = 380
        }
        val updated = suwayomi.update(track, didReadChapter = true)

        val (chapters, mark, trackProgress, manga) = http.requests
        chapters.json().getValue("query").jsonPrimitive.content shouldContain "isRead: false"
        chapters.json().getValue("variables").jsonObject.getValue("mangaId").jsonPrimitive.int shouldBe 42
        val markQuery = mark.json().getValue("query").jsonPrimitive.content
        markQuery shouldContain "updateChapters"
        markQuery shouldNotContain "deleteDownloadedChapters"
        mark.json().getValue("variables").jsonObject.getValue("chapters").jsonArray.map { it.jsonPrimitive.int } shouldContainExactly
            listOf(100, 101)
        trackProgress.json().getValue("query").jsonPrimitive.content shouldContain "trackProgress"
        trackProgress.json().getValue("variables").jsonObject.getValue("mangaId").jsonPrimitive.int shouldBe 42
        manga.json().getValue("query").jsonPrimitive.content shouldStartWith "query GetManga("
        http.requests.forEach { it.url.toString() shouldBe "$SERVER/api/graphql" }
        updated.last_chapter_read shouldBe 13.0
    }

    @Test
    fun `with the source's delete setting on, read chapters are also deleted from the server`() = runBlocking<Unit> {
        deleteDownloads = true
        http.answer("""{"data": {"chapters": {"nodes": [{"id": 100, "chapterNumber": 1.0}]}}}""")
        http.answer("""{"data": {}}""")
        http.answer("""{"data": {}}""")
        answerManga()

        suwayomi.update(
            Track.create(9L).apply {
                remote_id = 42
                title = "Berserk"
                tracking_url = "$SERVER/manga/42"
                last_chapter_read = 1.0
            },
        )

        http.requests[1].json().getValue("query").jsonPrimitive.content shouldContain "deleteDownloadedChapters"
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        // Wrong basic auth
        http.answer("", code = 401)
        shouldThrow<HttpException> { suwayomi.api.getTrackSearch(42) }.code shouldBe 401

        // A GraphQL error in a 200 answer
        http.answer("""{"data": null, "errors": [{"message": "Manga with id 42 not found", "locations": []}]}""")
        shouldThrow<Exception> { suwayomi.api.getTrackSearch(42) }
    }

    private companion object {
        const val SERVER = "http://suwayomi.home.test:4567"
        const val PASSWORD = "suwayomi-password"
    }
}
