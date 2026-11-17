package zoukan.desktop.track

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.anilist.AnilistInterceptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.Collections
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
