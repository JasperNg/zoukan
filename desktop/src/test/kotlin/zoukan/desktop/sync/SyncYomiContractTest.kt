package zoukan.desktop.sync

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extension.interactor.GetExtensionStores
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.NoStubSources
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureSyncServer
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.track.MapPreferenceStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end SyncYomi contract via [SyncManager.syncData] against [FixtureSyncServer] and a real
 * SQLite database: initial upload, merging a remote device's backup, invalid API key error, and
 * 412 ETag conflict leaving the library untouched until the next sync succeeds.
 */
class SyncYomiContractTest {
    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val server = FixtureServer()
    private val sync = FixtureSyncServer(server)
    private val messages = UserMessages()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-syncyomi-db").apply { isDaemon = true } }
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: AndroidDatabaseHandler
    private val store = MapPreferenceStore()
    private val preferences = SyncPreferences(store)

    @BeforeEach
    fun start() {
        driver = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        val db = DesktopDatabase.createDatabase(driver)
        val dispatcher = executor.asCoroutineDispatcher()
        handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)

        driver.execute(
            null,
            "INSERT INTO mangas(source, url, title, status, favorite, initialized, viewer, chapter_flags, cover_last_modified, date_added, favorite_modified_at) " +
                "VALUES (1, '/formats', 'Formats', 0, 1, 1, 0, 0, 0, 0, 1000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload, last_modified_at, version) " +
                "VALUES (1, '/formats/1', 'Chapter 1', 1, 0, 0, 1.0, 0, 0, 0, 0, 0), " +
                "(1, '/formats/2', 'Chapter 2', 0, 0, 0, 2.0, 1, 0, 0, 0, 0)",
            0,
        )

        val mangas = MangaRepositoryImpl(handler)
        val categories = GetCategories(CategoryRepositoryImpl(handler))
        val chapters = GetChaptersByMangaId(ChapterRepositoryImpl(handler))
        val tracks = TrackRepositoryImpl(handler)
        Injekt.addSingleton<DatabaseHandler>(handler)
        Injekt.addSingleton(categories)
        Injekt.addSingleton<MangaRepository>(mangas)
        Injekt.addSingleton(GetMangaByUrlAndSourceId(mangas))
        Injekt.addSingleton(chapters)
        Injekt.addSingleton(GetTracks(tracks))
        Injekt.addSingleton(InsertTrack(tracks))
        Injekt.addSingleton(FetchInterval(chapters))
        Injekt.addSingleton(GetFavorites(mangas))
        Injekt.addSingleton(mockk<GetMergedManga> { coEvery { await() } returns emptyList() })
        Injekt.addSingleton(mockk<GetExtensionStores> { coEvery { get() } returns emptyList() })
        Injekt.addSingleton(mockk<GetHistory>(relaxed = true))
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
        Injekt.addSingleton(mockk<SetCustomMangaInfo>(relaxed = true))
        Injekt.addSingleton(mockk<GetFlatMetadataById>(relaxed = true))
        Injekt.addSingleton(mockk<InsertFlatMetadata>(relaxed = true))
        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton<SourceManager>(mockk(relaxed = true))
        Injekt.addSingleton<StubSourceRepository>(NoStubSources)
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        Injekt.addSingleton<NetworkHelper>(mockk { every { plainClient } returns client })
        Injekt.addSingleton(ProtoBuf)

        preferences.syncService().set(SyncManager.SyncService.SYNCYOMI.value)
        preferences.clientHost().set(sync.clientHost)
        preferences.clientAPIKey().set(sync.apiKey)
    }

    @AfterEach
    fun stop() {
        messages.close()
        sync.close()
        server.close()
        driver.close()
        executor.shutdownNow()
    }

    private fun manager() = SyncManager(
        context = mockk<Application>(relaxed = true),
        handler = handler,
        syncPreferences = preferences,
        getCategories = Injekt.get<GetCategories>(),
        dirs = ZoukanDirs(temp),
        safetySnapshot = SafetySnapshot(folder = { File(temp, "safety") }, write = { it.writeText("snap") }),
    )

    private fun favoriteTitles(): List<String> = runBlocking { Injekt.get<GetFavorites>().await() }.map { it.title }

    private fun readChapters(mangaId: Long): List<Int> =
        runBlocking { Injekt.get<GetChaptersByMangaId>().await(mangaId) }.filter { it.read }.map { it.chapterNumber.toInt() }

    @Test
    fun `sync uploads library, merges remote changes, rejects bad key, and recovers from 412 conflict`() {
        val syncManager = manager()

        // 1. Initial sync: GET 404 -> PUT 200 uploads local library and reports SyncYomi events
        runBlocking { syncManager.syncData() }
        sync.calls.filter { it.path == "/api/sync/content" }.map { it.method to it.status } shouldContainExactly
            listOf("GET" to 404, "PUT" to 200)
        val uploaded = BackupCodec.decode(sync.remoteBackup!!)
        uploaded.backupManga.map { it.title } shouldContainExactly listOf("Formats")
        uploaded.backupManga.single().chapters.filter { it.read }.map { it.chapterNumber.toInt() } shouldContainExactly listOf(1)
        sync.events.any { "SYNC_STARTED" in it } shouldBe true
        sync.events.any { "SYNC_SUCCESS" in it } shouldBe true

        // 2. Another device adds a second title and marks chapter 2 read; sync merges both
        val existingManga = uploaded.backupManga.single().apply {
            chapters.first { it.chapterNumber.toInt() == 2 }.read = true
        }
        val remote = uploaded.copy(
            backupManga = listOf(
                existingManga,
                BackupManga(
                    source = 1,
                    url = "/second",
                    title = "Fixture Second",
                    favorite = true,
                    chapters = listOf(BackupChapter(url = "/second/1", name = "Chapter 1", chapterNumber = 1f, sourceOrder = 0L)),
                ),
            ),
        )
        sync.seed(BackupCodec.encode(remote))
        runBlocking { syncManager.syncData() }
        favoriteTitles() shouldContainExactlyInAnyOrder listOf("Formats", "Fixture Second")
        readChapters(1L) shouldContainExactly listOf(1, 2)

        // 3. Invalid API key fails without altering library
        val beforeBadKey = preferences.lastSyncTimestamp().get()
        preferences.clientAPIKey().set("not-the-key")
        runBlocking { syncManager.syncData() }
        messages.awaitError().text shouldContain "Invalid API token"
        favoriteTitles() shouldContainExactlyInAnyOrder listOf("Formats", "Fixture Second")
        readChapters(1L) shouldContainExactly listOf(1, 2)
        preferences.lastSyncTimestamp().get() shouldBe beforeBadKey

        // 4. 412 conflict on PUT leaves library and lastSyncTimestamp untouched; next sync succeeds
        preferences.clientAPIKey().set(sync.apiKey)
        preferences.lastSyncTimestamp().set(beforeBadKey - 1_000L)
        val beforeConflict = preferences.lastSyncTimestamp().get()
        // Seed a different ETag so server returns 412 or fail PUT once with 412
        server.fail("/api/sync/content", FixtureFailure.Status(412, "ETag mismatch"), times = 1)
        // Force a PUT by clearing ETag and seeding remote
        preferences.lastSyncEtag().set("")
        // First request hits GET (fail times = 1 would consume on GET if scoped to path).
        // Seed a remote change, let GET succeed, and fail only PUT via extraRoutes.
        server.clearFailures()
        var conflictRoute: (com.sun.net.httpserver.HttpExchange, List<String>) -> Boolean = { _, _ -> false }
        conflictRoute = { exchange, parts ->
            if (exchange.requestMethod == "PUT" && parts == listOf("api", "sync", "content")) {
                server.extraRoutes -= conflictRoute
                val body = "ETag mismatch".toByteArray()
                exchange.sendResponseHeaders(412, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                true
            } else {
                false
            }
        }
        server.extraRoutes.add(0, conflictRoute)
        runBlocking { syncManager.syncData() }
        favoriteTitles() shouldContainExactlyInAnyOrder listOf("Formats", "Fixture Second")
        preferences.lastSyncTimestamp().get() shouldBe beforeConflict

        // Next sync succeeds
        runBlocking { syncManager.syncData() }
        (preferences.lastSyncTimestamp().get() > beforeConflict) shouldBe true
    }
}
