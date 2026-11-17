package zoukan.desktop.track

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.ConfigurableSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.IOException
import java.security.MessageDigest

/**
 * Kavita, the self-hosted tracker: the plugin sign-in with the Kavita source's API key, then the
 * series, volumes and progress calls against the user's server, with recorded answers in place of
 * the server. Kavita has no search, list entry or unbind: it follows the Kavita source's entries.
 */
class KavitaApiTest {

    private val env = TrackerTestEnvironment()
    private val http = env.http

    init {
        // The first of the Kavita extension's three sources is set up with this server; the others are not
        val configured = mockk<SharedPreferences> {
            every { getString("APIURL", "") } returns API
            every { getString("APIKEY", "") } returns API_KEY
        }
        val empty = mockk<SharedPreferences> { every { getString(any(), any()) } returns "" }
        Injekt.addSingleton<Application>(
            mockk {
                every { getSharedPreferences(any(), any()) } returns empty
                every { getSharedPreferences("source_${sourceId(1)}", any()) } returns configured
            },
        )
        Injekt.addSingleton<SourceManager>(
            mockk {
                every { get(any()) } answers {
                    val id = firstArg<Long>()
                    mockk<ConfigurableSource> { every { this@mockk.id } returns id }
                }
            },
        )
    }

    private val kavita = Kavita(TrackerManager.KAVITA)

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(JWT, https = false)
        env.close()
    }

    private fun sourceId(n: Int): Long {
        val bytes = MessageDigest.getInstance("MD5").digest("kavita_$n/all/1".toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    private fun answerSignIn() = http.answer("""{"username": "reader", "token": "$JWT", "apiKey": "$API_KEY", "refreshToken": "r"}""")

    private fun answerSeries(pagesRead: Int = 500, latest: String? = "12") {
        http.answer(
            """{"id": 42, "name": "Berserk", "originalName": "ベルセルク", "localizedName": "", "sortName": "Berserk",
               "pages": 2000, "coverImageLocked": false, "pagesRead": $pagesRead, "userRating": 0, "userReview": null,
               "format": 1, "created": "2024-01-01T00:00:00", "libraryId": 1, "libraryName": "Manga"}""",
        )
        http.answer(
            """[
              {"id": 1, "number": 1, "name": "1", "pages": 200, "pagesRead": 200, "lastModified": "2024-01-01T00:00:00",
               "created": "2024-01-01T00:00:00", "seriesId": 42,
               "chapters": [{"id": 10, "range": "1", "number": "0", "pages": 200, "isSpecial": false, "title": "", "pagesRead": 200, "volumeId": 1}]},
              {"id": 2, "number": 0, "name": "0", "pages": 300, "pagesRead": 100, "lastModified": "2024-01-01T00:00:00",
               "created": "2024-01-01T00:00:00", "seriesId": 42,
               "chapters": [{"id": 11, "range": "12", "number": "12", "pages": 20, "volumeId": 2},
                            {"id": 12, "range": "13.5", "number": "13.5", "pages": 20, "volumeId": 2}]}
            ]""",
        )
        if (latest == null) http.answer("", code = 204) else http.answer("""{"id": 11, "number": "$latest", "pages": 20, "volumeId": 2}""")
    }

    @Test
    fun `matching a series signs in with the API key, then reads the series, its chapters and progress`() = runBlocking<Unit> {
        answerSignIn()
        answerSeries()

        val found = kavita.api.getTrackSearch(SERIES_URL)

        val (signIn, series, volumes, latest) = http.requests
        signIn.method shouldBe "POST"
        signIn.url.toString() shouldBe "$API/Plugin/authenticate?apiKey=$API_KEY&pluginName=Tachiyomi-Kavita"
        series.method shouldBe "GET"
        series.url.toString() shouldBe SERIES_URL
        volumes.url.toString() shouldBe "$API/Series/volumes?seriesId=42"
        latest.url.toString() shouldBe "$API/Tachiyomi/latest-chapter?seriesId=42"
        listOf(series, volumes, latest).forEach { it.headers["Authorization"] shouldBe "Bearer $JWT" }
        // The API key goes only to the sign-in, as Kavita's plugin API takes it
        listOf(series, volumes, latest).forEach { it.url.toString() shouldNotContain API_KEY }

        found.tracker_id shouldBe TrackerManager.KAVITA
        found.title shouldBe "Berserk"
        found.tracking_url shouldBe SERIES_URL
        // Volume 1 is a single-file volume (chapter 0), so it counts as one; chapter 13.5 counts as 13
        found.total_chapters shouldBe 13L
        found.last_chapter_read shouldBe 12.0
        found.status shouldBe Kavita.READING
    }

    @Test
    fun `the read pages give the status, and no progress yet reads as chapter zero`() = runBlocking<Unit> {
        answerSignIn()
        answerSeries(pagesRead = 0, latest = null)
        val unread = kavita.api.getTrackSearch(SERIES_URL)
        unread.status shouldBe Kavita.UNREAD
        unread.last_chapter_read shouldBe 0.0

        answerSeries(pagesRead = 2000)
        kavita.api.getTrackSearch(SERIES_URL).status shouldBe Kavita.COMPLETED
        // Signed in once for both
        http.requests.count { it.path.endsWith("/Plugin/authenticate") } shouldBe 1
    }

    @Test
    fun `a progress update marks the chapters read on the server, then reads the series back`() = runBlocking<Unit> {
        answerSignIn()
        http.answer("{}")
        answerSeries(latest = "40")

        val track = Track.create(TrackerManager.KAVITA).apply {
            title = "Berserk"
            tracking_url = SERIES_URL
            status = Kavita.READING
            last_chapter_read = 40.0
            total_chapters = 13
        }
        val updated = kavita.update(track, didReadChapter = true)

        val mark = http.requests[1]
        mark.method shouldBe "POST"
        mark.path shouldBe "/api/Tachiyomi/mark-chapter-until-as-read"
        mark.url.queryParameter("seriesId") shouldBe "42"
        mark.url.queryParameter("chapterNumber") shouldBe "40.0"
        mark.headers["Authorization"] shouldBe "Bearer $JWT"
        http.requests.drop(2).map { it.path } shouldContainExactly
            listOf("/api/Series/42", "/api/Series/volumes", "/api/Tachiyomi/latest-chapter")
        updated.last_chapter_read shouldBe 40.0
    }

    @Test
    fun `a refused API key fails the call without a crash and sends nothing else`() = runBlocking<Unit> {
        http.answer("""{"title":"Unauthorized"}""", code = 401)
        shouldThrow<IOException> { kavita.api.getTrackSearch(SERIES_URL) }
        http.requests.map { it.path } shouldContainExactly listOf("/api/Plugin/authenticate")
    }

    @Test
    fun `error answers fail the call`() = runBlocking<Unit> {
        answerSignIn()
        http.answer("""{"title":"Not Found"}""", code = 404)
        shouldThrow<HttpException> { kavita.api.getTrackSearch(SERIES_URL) }.code shouldBe 404

        http.answer("""{"title":"Server Error"}""", code = 500)
        shouldThrow<HttpException> {
            kavita.update(Track.create(TrackerManager.KAVITA).apply { tracking_url = SERIES_URL; last_chapter_read = 1.0 })
        }.code shouldBe 500
    }

    private companion object {
        const val API = "http://kavita.home.test:5000/api"
        const val SERIES_URL = "$API/Series/42"
        const val API_KEY = "kavita-api-key"
        const val JWT = "kavita-jwt"
    }
}
