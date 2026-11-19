package zoukan.desktop.track

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import eu.kanade.tachiyomi.extension.all.komga.Komga
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.app.Toasts
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureKomga
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testkit.TestPreferenceStore
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Kavita, the self-hosted tracker: the plugin sign-in with the Kavita source's API key, then the
 * series, volumes and progress calls against the user's server, with recorded answers in place of
 * the server. Kavita has no search, list entry or unbind: it follows the Kavita source's entries.
 */
class KavitaApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private val kavita = Kavita(TrackerManager.KAVITA)

    @BeforeEach
    fun setUp() {
        env.register()
        // The first of the Kavita extension's three sources is set up with this server; the others are not
        val configured = mockk<SharedPreferences> {
            every { getString("APIURL", "") } returns API
            every { getString("APIKEY", "") } returns API_KEY
        }
        val empty = mockk<SharedPreferences> { every { getString(any(), any()) } returns "" }
        val application = mockk<Application>()
        every { application.getSharedPreferences(any<String>(), any<Int>()) } returns empty
        every { application.getSharedPreferences("source_${sourceId(1)}", any<Int>()) } returns configured
        Injekt.addSingleton<Application>(application)
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(any<Long>()) } answers { configurableSource(firstArg()) }
        Injekt.addSingleton<SourceManager>(sourceManager)
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(JWT, https = false)
    }

    private fun configurableSource(id: Long): ConfigurableSource = mockk { every { this@mockk.id } returns id }

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
            kavita.update(
                Track.create(TrackerManager.KAVITA).apply {
                    tracking_url = SERIES_URL
                    last_chapter_read = 1.0
                },
            )
        }.code shouldBe 500
    }

    private companion object {
        const val API = "http://kavita.home.test:5000/api"
        const val SERIES_URL = "$API/Series/42"
        const val API_KEY = "kavita-api-key"
        const val JWT = "kavita-jwt"
    }
}

/**
 * Suwayomi, the self-hosted tracker: the GraphQL calls it sends to the user's Suwayomi server
 * through the Suwayomi (Tachidesk) source's own client, and how it maps the answers, with recorded
 * answers in place of the server. Suwayomi has no search, list entry or unbind.
 */
class SuwayomiApiTest {

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val env = TrackerTestEnvironment()
    private val http = env.http
    private var deleteDownloads = false
    private val suwayomi = Suwayomi(9L)

    @BeforeEach
    fun setUp() {
        env.register()
        val preferences = mockk<SharedPreferences> {
            every { getBoolean("Tracker Delete", false) } answers { deleteDownloads }
        }
        val application = mockk<Application>()
        every { application.getSharedPreferences(any<String>(), any<Int>()) } returns preferences
        Injekt.addSingleton<Application>(application)
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
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(any<Long>()) } returns source
        Injekt.addSingleton<SourceManager>(sourceManager)
    }

    @AfterEach
    fun tearDown() {
        http.checkAllAnswered(PASSWORD, https = false)
    }

    private fun answerManga(
        unread: Int = 368,
        latestRead: String = """{"lastReadAt": "1700000000", "chapterNumber": 12.0}""",
        thumbnail: String = "/api/v1/manga/42/thumbnail",
    ) {
        http.answer(
            """
            {"data": {"manga": {"artist": "Kentarou Miura", "author": "Kentarou Miura", "description": "Guts, a mercenary",
              "id": 42, "status": "ONGOING", "thumbnailUrl": "$thumbnail", "title": "Berserk",
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
    fun `the cover address joins the server and the thumbnail path with one slash`() = runBlocking<Unit> {
        // The server's thumbnail path starts with a slash; the source's address ends with one
        answerManga()
        suwayomi.api.getTrackSearch(42).cover_url shouldBe "$SERVER/api/v1/manga/42/thumbnail"

        answerManga(thumbnail = "api/v1/manga/42/thumbnail")
        suwayomi.api.getTrackSearch(42).cover_url shouldBe "$SERVER/api/v1/manga/42/thumbnail"

        // An absolute address is kept as it is
        answerManga(thumbnail = "https://covers.example.test/42.jpg")
        suwayomi.api.getTrackSearch(42).cover_url shouldBe "https://covers.example.test/42.jpg"
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

/**
 * Komga tracking contract against [FixtureKomga] and a real SQLite database (no full app boot):
 * auto-binding the enhanced Komga tracker on add-to-library, pushing read progress via
 * [TrackChapters] and [TrackingModel], handling server errors without corrupting local progress,
 * retrying after recovery, and unbinding cleanly.
 */
class KomgaTrackingContractTest {
    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val server = FixtureServer()
    private val komga = FixtureKomga(server)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-komga-db").apply { isDaemon = true } }
    private val mainDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: AndroidDatabaseHandler
    private lateinit var source: Komga
    private lateinit var trackerManager: TrackerManager
    private lateinit var trackPreferences: TrackPreferences
    private lateinit var getTracks: GetTracks
    private lateinit var addToLibrary: AddToLibrary
    private lateinit var trackChapters: TrackChapters
    private var previousCookies: CookieHandler? = null

    @BeforeEach
    fun setUp() {
        previousCookies = CookieHandler.getDefault()
        CookieHandler.setDefault(CookieManager())
        Dispatchers.setMain(mainDispatcher)
        komga.series(SERIES_ID, "Komga Title", books = 4)
        source = Komga(server.baseUrl, OkHttpClient())

        driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "komga.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        val dbDispatcher = executor.asCoroutineDispatcher()
        handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dbDispatcher, transactionDispatcher = dbDispatcher)

        val store = TestPreferenceStore()
        val networkPreferences = NetworkPreferences(store)
        val context = mockk<Context> { every { cacheDir } returns temp }
        val networkHelper = NetworkHelper(context, networkPreferences)
        trackPreferences = TrackPreferences(store)

        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        Injekt.addSingleton<Application>(mockk(relaxed = true))
        Injekt.addSingleton(networkPreferences)
        Injekt.addSingleton(networkHelper)
        Injekt.addSingleton<DatabaseHandler>(handler)
        Injekt.addSingleton(trackPreferences)
        Injekt.addSingleton(LibraryPreferences(store))

        trackerManager = TrackerManager()
        trackerManager.komga.loginNoop()
        Injekt.addSingleton(trackerManager)

        val mangaRepo = MangaRepositoryImpl(handler)
        val chapterRepo = ChapterRepositoryImpl(handler)
        val trackRepo = TrackRepositoryImpl(handler)
        val categoryRepo = CategoryRepositoryImpl(handler)
        val historyRepo = HistoryRepositoryImpl(handler)

        getTracks = GetTracks(trackRepo)
        val insertTrack = InsertTrack(trackRepo)
        val deleteTrack = DeleteTrack(trackRepo)
        val getChapters = GetChaptersByMangaId(chapterRepo)
        val updateChapter = UpdateChapter(chapterRepo)
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val syncProgress = SyncChapterProgressWithTrack(
            updateChapter = updateChapter,
            insertTrack = insertTrack,
            getChaptersByMangaId = getChapters,
        )
        val addTracks = AddTracks(
            insertTrack = insertTrack,
            syncChapterProgressWithTrack = syncProgress,
            getChaptersByMangaId = getChapters,
            trackerManager = trackerManager,
        )
        val refreshTracks = RefreshTracks(getTracks, trackerManager, insertTrack, syncProgress)
        val delayedStore = mockk<DelayedTrackingStore>(relaxed = true)

        Injekt.addSingleton<MangaRepository>(mangaRepo)
        Injekt.addSingleton(getTracks)
        Injekt.addSingleton(insertTrack)
        Injekt.addSingleton(deleteTrack)
        Injekt.addSingleton(sourceManager)
        Injekt.addSingleton(mockk<GetFlatMetadataById> { coEvery { await(any()) } returns null })

        addToLibrary = AddToLibrary(
            getCategories = GetCategories(categoryRepo),
            setMangaCategories = SetMangaCategories(mangaRepo),
            mangaRepository = mangaRepo,
            getDuplicateLibraryManga = GetDuplicateLibraryManga(mangaRepo),
            libraryPreferences = LibraryPreferences(store),
            addTracks = addTracks,
            refreshManga = mockk(relaxed = true),
            sourceManager = sourceManager,
        )
        trackChapters = TrackChapters(getTracks, trackerManager, insertTrack, delayedStore, trackPreferences)
        Injekt.addSingleton(refreshTracks)
        Injekt.addSingleton(trackChapters)
    }

    @AfterEach
    fun tearDown() {
        CookieHandler.setDefault(previousCookies)
        Dispatchers.resetMain()
        mainDispatcher.close()
        komga.close()
        server.close()
        driver.close()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun insertKomgaManga(url: String = komga.seriesUrl(SERIES_ID)): Long = runBlocking {
        handler.awaitOneExecutable(true) {
            mangasQueries.insert(
                source = source.id,
                url = url,
                artist = null,
                author = null,
                description = null,
                genre = null,
                title = "Komga Title",
                status = 0L,
                thumbnailUrl = null,
                favorite = false,
                lastUpdate = 0L,
                nextUpdate = 0L,
                calculateInterval = 0L,
                initialized = true,
                viewerFlags = 0L,
                chapterFlags = 0L,
                coverLastModified = 0L,
                dateAdded = 0L,
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                version = 0L,
                notes = "",
                memo = JsonObject(emptyMap()),
            )
            mangasQueries.selectLastInsertedRowId()
        }
    }

    @Test
    fun `binds enhanced Komga tracker, syncs progress, preserves state on 500 error, retries, and unbinds`() {
        val mangaId = insertKomgaManga()
        val manga = runBlocking { GetManga(MangaRepositoryImpl(handler)).await(mangaId)!! }

        // 1. Add to library binds the Komga enhanced tracker automatically
        runBlocking { addToLibrary.add(manga, emptyList(), fetchOnAdd = false) }
        Await.until("Komga track inserted") { runBlocking { getTracks.await(mangaId) }.isNotEmpty() }
        val bound = runBlocking { getTracks.await(mangaId) }.single()
        bound.trackerId shouldBe trackerManager.komga.id
        bound.remoteUrl shouldBe komga.seriesUrl(SERIES_ID)
        bound.lastChapterRead shouldBe 0.0

        // 2. Reading Book 2 sends progress to FixtureKomga and stores it in DB
        runBlocking { trackChapters.afterReading(mangaId, 2.0) }
        komga.progressUpdates shouldContainExactly listOf(SERIES_ID to 2.0)
        runBlocking { getTracks.await(mangaId) }.single().lastChapterRead shouldBe 2.0

        // 3. Server 500 via TrackingModel leaves stored progress at 2.0 and surfaces error toast
        val toasts = CopyOnWriteArrayList<String>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
        val model = TrackingModel(manga)
        try {
            Await.until("TrackingModel loaded item") { model.state.value.items.singleOrNull()?.track != null }
            val currentItem = model.state.value.items.single()

            server.fail("/api/v2/", FixtureFailure.Status(500))
            val callsBeforeError = server.requests.count { it.startsWith("/api/") }
            model.setChapter(currentItem, 3)
            Await.until("500 error toast") { toasts.any { "500" in it } }
            server.requests.count { it.startsWith("/api/") } shouldBe callsBeforeError + 1
            komga.progressUpdates shouldHaveSize 1
            runBlocking { getTracks.await(mangaId) }.single().lastChapterRead shouldBe 2.0

            // 4. Server recovers: retrying succeeds
            server.clearFailures()
            model.setChapter(currentItem, 3)
            Await.until("progress updated to 3.0") {
                runBlocking { getTracks.await(mangaId) }.singleOrNull()?.lastChapterRead == 3.0
            }
            komga.progressUpdates shouldContainExactly listOf(SERIES_ID to 2.0, SERIES_ID to 3.0)

            // 5. Stopping tracking removes the track and sends no more updates
            model.remove(currentItem, remote = false)
            Await.until("track removed") { runBlocking { getTracks.await(mangaId) }.isEmpty() }
            val callsAfterRemove = server.requests.count { it.startsWith("/api/") }
            runBlocking { trackChapters.afterReading(mangaId, 4.0) }
            server.requests.count { it.startsWith("/api/") } shouldBe callsAfterRemove
            komga.progressUpdates shouldHaveSize 2

            // 6. With trackOnAddingToLibrary off, adding another Komga entry still binds enhanced tracker
            trackPreferences.trackOnAddingToLibrary().set(false)
            runBlocking { addToLibrary.remove(manga) }
            runBlocking { addToLibrary.add(manga, emptyList(), fetchOnAdd = false) }
            Await.until("enhanced Komga track re-bound") { runBlocking { getTracks.await(mangaId) }.isNotEmpty() }
        } finally {
            model.onDispose()
            runBlocking {
                withTimeout(5_000) {
                    val job = model.screenModelScope.coroutineContext[Job]!!
                    job.cancelChildren()
                    job.children.toList().joinAll()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    private companion object {
        const val SERIES_ID = "1"
    }
}
