package zoukan.desktop.track

import android.app.Application
import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.all.komga.Komga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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

    @BeforeEach
    fun setUp() {
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

        val store = InMemoryPreferenceStore()
        val networkPreferences = NetworkPreferences(store)
        val context = mockk<Context> { every { cacheDir } returns temp }
        val networkHelper = NetworkHelper(context, networkPreferences)
        trackPreferences = TrackPreferences(store)

        Injekt.addSingleton<PreferenceStore>(store)
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
        val getManga = GetManga(mangaRepo)
        val sourceManager = mockk<SourceManager>(relaxed = true) {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val addTracks = AddTracks(
            insertTrack = insertTrack,
            syncChapterProgressWithTrack = SyncChapterProgressWithTrack(updateChapter, getChapters),
            getChaptersByMangaId = getChapters,
            trackerManager = trackerManager,
        )
        val syncProgress = SyncChapterProgressWithTrack(updateChapter, getChapters)
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
        Dispatchers.resetMain()
        mainDispatcher.close()
        komga.close()
        server.close()
        driver.close()
        executor.shutdownNow()
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
                updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
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
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { Toasts.flow.collect { toasts += it } }
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
            collector.cancel()
        }
    }

    private companion object {
        const val SERIES_ID = "1"
    }
}
