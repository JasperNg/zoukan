package zoukan.desktop.sync

import android.app.Application
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
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
import tachiyomi.core.common.preference.Preference
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
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.NoStubSources
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureSyncServer
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.testing.fakes.FailingSqlDriver
import zoukan.desktop.track.MapPreferenceStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The whole of [SyncManager.syncData] (WebDAV) against the fixture sync server, over a real database
 * through [FailingSqlDriver]: the library is read from the database, merged with the server's,
 * uploaded, and the merged entries are restored into the database. Its service is made inside
 * `doSync`, but it takes its HTTP client from [NetworkHelper], which the test registers.
 *
 * - A database read that fails while the local library is built: the sync fails with an error the
 *   user sees, nothing is uploaded, and neither the saved ETag nor the last sync time moves on. The
 *   next sync works.
 * - A database write that fails while the merged library is restored: the merged library (built
 *   whole before the restore) is uploaded, the entry that failed is rolled back whole, the user is
 *   told, and the next sync restores it.
 */
class SyncDatabaseFaultsTest {

    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val server = FixtureServer()
    private val sync = FixtureSyncServer(server)
    private val messages = UserMessages()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
    private lateinit var real: JdbcSqliteDriver
    private lateinit var driver: FailingSqlDriver
    /** The app's one preference store: the sync settings and what a restore writes share it, as in the app. */
    private val store = MapPreferenceStore()
    private val preferences = SyncPreferences(store)

    /** Another device's library, already on the server: one entry in the library, with chapters. */
    private val remote = Backup(
        backupManga = listOf(
            BackupManga(
                source = 1,
                url = "/remote",
                title = "From the other device",
                favorite = true,
                chapters = (1..3).map { BackupChapter(url = "/remote/$it", name = "Chapter $it", chapterNumber = it.toFloat(), sourceOrder = it.toLong()) },
            ),
        ),
    )

    @BeforeEach
    fun start() {
        real = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        )
        driver = FailingSqlDriver(real)
        val db = DesktopDatabase.createDatabase(driver)
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        // This computer's library: one entry
        driver.execute(
            null,
            "INSERT INTO mangas(source, url, title, status, favorite, initialized, viewer, chapter_flags, cover_last_modified, date_added, favorite_modified_at) " +
                "VALUES (1, '/local', 'From this computer', 0, 1, 1, 0, 0, 0, 0, 1000)",
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
        Injekt.addSingleton(mockk<GetFavorites> { coEvery { await() } returns emptyList() })
        Injekt.addSingleton(mockk<GetMergedManga> { coEvery { await() } returns emptyList() })
        Injekt.addSingleton(
            mockk<GetExtensionStores> {
                coEvery { get() } answers {
                    // Only in the restore: the backup of this computer's library reads them too
                    if (storesFail && RestoreGate.isRestoring) throw IllegalStateException("The stores could not be read") else emptyList()
                }
            },
        )
        Injekt.addSingleton(mockk<GetHistory>(relaxed = true))
        Injekt.addSingleton(mockk<GetCustomMangaInfo> { every { this@mockk.get(any()) } returns null })
        Injekt.addSingleton(mockk<SetCustomMangaInfo>(relaxed = true))
        Injekt.addSingleton(mockk<GetFlatMetadataById>(relaxed = true))
        Injekt.addSingleton(mockk<InsertFlatMetadata>(relaxed = true))
        Injekt.addSingleton<PreferenceStore>(store)
        Injekt.addSingleton(LibraryPreferences(store))
        Injekt.addSingleton<SourceManager>(mockk<SourceManager>(relaxed = true))
        Injekt.addSingleton<StubSourceRepository>(NoStubSources)
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        Injekt.addSingleton<NetworkHelper>(mockk { every { plainClient } returns client })
        Injekt.addSingleton<ProtoBuf>(ProtoBuf)

        preferences.syncService().set(SyncManager.SyncService.WEB_DAV.value)
        preferences.webDavUrl().set(sync.webDavUrl)
        preferences.webDavUsername().set(sync.user)
        preferences.webDavPassword().set(sync.password)
        preferences.webDavFolder().set(sync.davFolder)
        sync.seed(BackupCodec.encode(remote))
        sync.calls.clear()
    }

    @AfterEach
    fun stop() {
        messages.close()
        sync.close()
        server.close()
        real.close()
        executor.shutdownNow()
    }

    private val snapshots = mutableListOf<File>()

    /** The library as each safety snapshot saw it. */
    private val librariesAtSnapshot = mutableListOf<Map<String, Long>>()

    @Volatile
    private var snapshotFails = false

    /** Makes the restore throw before it writes anything (its read of the extension stores). */
    @Volatile
    private var storesFail = false

    private fun manager() = SyncManager(
        context = mockk<Application>(relaxed = true),
        handler = Injekt.getInstance(DatabaseHandler::class.java),
        syncPreferences = preferences,
        getCategories = Injekt.getInstance(GetCategories::class.java),
        dirs = ZoukanDirs(temp),
        safetySnapshot = SafetySnapshot(
            folder = { File(temp, "safety") },
            write = { file ->
                if (snapshotFails) throw java.io.IOException("No space left on device")
                librariesAtSnapshot += library()
                snapshots += file.also { it.writeText("before") }
            },
        ),
    )

    /** Each entry's URL and how many chapters it has, from the database. */
    private fun library(): Map<String, Long> = driver.executeQuery(
        null,
        "SELECT m.url, (SELECT COUNT(*) FROM chapters c WHERE c.manga_id = m._id) FROM mangas m ORDER BY m.url",
        { cursor ->
            val rows = linkedMapOf<String, Long>()
            while (cursor.next().value) rows[cursor.getString(0)!!] = cursor.getLong(1)!!
            QueryResult.Value(rows)
        },
        0,
    ).value

    private fun remoteUrls(): Set<String> = BackupCodec.decode(sync.remoteBackup!!).backupManga.map { it.url }.toSet()

    /** Set while the server may hold a merged library this computer has not restored (SyncManager.doSync). */
    private val restorePending get() = store.getBoolean(Preference.appStateKey("zoukan_sync_restore_pending"), false)

    @Test
    fun `an ETag saved by a sync that quit before its restore is dropped, and the next sync merges and restores`() = runBlocking<Unit> {
        // The app quit after the service saved the server's ETag and before the merged library was restored
        preferences.lastSyncTimestamp().set(1L)
        preferences.lastSyncEtag().set(sync.etag!!)
        restorePending.set(true)

        manager().syncData()

        // Without the ETag the server sends its library ("not modified" would upload this computer's over it)
        sync.calls.first { it.method == "GET" }.ifNoneMatch shouldBe null
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe sync.etag
        restorePending.get() shouldBe false
    }

    @Test
    fun `a completed sync leaves no restore pending, and a failed download sets none`() = runBlocking<Unit> {
        preferences.lastSyncTimestamp().set(1L)
        server.fail("/dav/${sync.davFolder}/backup.proto", FixtureFailure.Status(503), times = 1)

        manager().syncData()

        messages.awaitError()
        sync.calls.filter { it.method == "PUT" }.shouldBeEmpty()
        restorePending.get() shouldBe false
        restorePending.isSet() shouldBe false

        manager().syncData()

        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe sync.etag
        restorePending.get() shouldBe false
    }

    @Test
    fun `another device's sync ETag in the synced settings is never saved here`() = runBlocking<Unit> {
        // The other device's settings carry its ETag: the one the server will have after this upload
        // (the server is at v1; this seed makes v2 and the upload v3)
        sync.seed(
            BackupCodec.encode(
                remote.copy(backupPreferences = listOf(BackupPreference("sync_etag", StringPreferenceValue("v3")))),
            ),
        )
        sync.calls.clear()
        preferences.lastSyncTimestamp().set(1L)
        // The restore ends with an error, so this computer does not keep the server's ETag
        driver.failOn(FailingSqlDriver.insertInto("chapters"), nth = 2)

        manager().syncData()

        messages.awaitError { it.text.contains("SQLITE_FULL") }
        sync.etag shouldBe "v3"
        preferences.lastSyncEtag().get() shouldBe ""
        restorePending.get() shouldBe true

        // So the next sync downloads and merges again, and restores the entry
        driver.heal()
        sync.calls.clear()
        manager().syncData()
        sync.calls.first { it.method == "GET" }.ifNoneMatch shouldBe null
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        remoteUrls() shouldBe setOf("/local", "/remote")
        preferences.lastSyncEtag().get() shouldBe sync.etag
    }

    @Test
    fun `an upload the server confirms without an ETag leaves none saved`() = runBlocking<Unit> {
        preferences.lastSyncTimestamp().set(1L)
        preferences.lastSyncEtag().set("an older one")
        // The download goes through (a pass-through rule used once); the upload answers 201 with no ETag
        server.fail("/dav/${sync.davFolder}/backup.proto", FixtureFailure.Slow(0), times = 1)
        server.fail("/dav/${sync.davFolder}/backup.proto", FixtureFailure.Status(201, ""), times = 1)

        manager().syncData()

        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe ""
        preferences.lastSyncEtag().isSet() shouldBe false
    }

    /** The GET and PUT of one sync: what the app sent and what it got. */
    private fun syncCalls() = sync.calls.filter { it.method == "GET" || it.method == "PUT" }.also { sync.calls.clear() }

    @Test
    fun `the first sync only uploads, the second restores the other device's entries, the third is not modified`() = runBlocking<Unit> {
        preferences.lastSyncTimestamp().get() shouldBe 0L

        // 1: merges both libraries on the server, restores nothing here, keeps no ETag
        manager().syncData()
        syncCalls().map { it.method to it.ifNoneMatch } shouldBe listOf("GET" to null, "PUT" to null)
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L)
        preferences.lastSyncEtag().get() shouldBe ""
        snapshots.shouldBeEmpty()

        // 2: sends no If-None-Match, so it gets the server's library and restores it; keeps the ETag
        manager().syncData()
        syncCalls().map { it.method to it.ifNoneMatch } shouldBe listOf("GET" to null, "PUT" to null)
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe sync.etag
        // The safety snapshot came before the restore
        librariesAtSnapshot shouldBe listOf(mapOf("/local" to 0L))

        // 3: "not modified", and the upload of this computer's library keeps both entries on the server
        val etag = sync.etag
        manager().syncData()
        val calls = syncCalls()
        calls.map { it.method to it.status } shouldBe listOf("GET" to 304, "PUT" to 204)
        calls.first().ifNoneMatch?.trim('"') shouldBe etag
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe sync.etag

        // 4: not modified again: no download and merge each time
        manager().syncData()
        syncCalls().first().status shouldBe 304
        restorePending.get() shouldBe false
    }

    @Test
    fun `SyncYomi - the first syncs upload, then restore, then are not modified, and a quit before the restore is recovered`() = runBlocking<Unit> {
        preferences.syncService().set(SyncManager.SyncService.SYNCYOMI.value)
        preferences.clientHost().set(sync.clientHost)
        preferences.clientAPIKey().set(sync.apiKey)

        // 1: only uploads; the ETag the service saved is set aside
        manager().syncData()
        syncCalls().map { it.method to it.ifNoneMatch } shouldBe listOf("GET" to null, "PUT" to null)
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L)
        preferences.lastSyncEtag().get() shouldBe ""

        // 2: downloads, restores, keeps the ETag
        manager().syncData()
        syncCalls().first().ifNoneMatch shouldBe null
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get().trim('"') shouldBe sync.etag

        // 3: not modified
        manager().syncData()
        syncCalls().map { it.method to it.status } shouldBe listOf("GET" to 304, "PUT" to 200)
        remoteUrls() shouldBe setOf("/local", "/remote")

        // The app quit after SyncYomi saved the new ETag, while it reported the sync to the server
        restorePending.set(true)
        manager().syncData()
        syncCalls().first().ifNoneMatch shouldBe null
        remoteUrls() shouldBe setOf("/local", "/remote")
        restorePending.get() shouldBe false
        sync.events.any { "SYNC_SUCCESS" in it } shouldBe true
    }

    /** Each chapter of [url] (by its URL) as read, last page read and version, from the database. */
    private fun progress(url: String): Map<String, Triple<Boolean, Long, Long>> = driver.executeQuery(
        null,
        "SELECT c.url, c.read, c.last_page_read, c.version FROM chapters c JOIN mangas m ON m._id = c.manga_id WHERE m.url = ? ORDER BY c.url",
        { cursor ->
            val rows = linkedMapOf<String, Triple<Boolean, Long, Long>>()
            while (cursor.next().value) rows[cursor.getString(0)!!] = Triple(cursor.getLong(1)!! == 1L, cursor.getLong(2)!!, cursor.getLong(3)!!)
            QueryResult.Value(rows)
        },
        1,
    ) { bindString(0, url) }.value

    @Test
    fun `the second sync keeps this computer's newer reading progress and takes the other device's newer one`() = runBlocking<Unit> {
        // This computer has read chapter 1 (version 5); the other device has read chapter 2 (version 7)
        listOf("('/local/1', 'Chapter 1', 1, 12, 1, 5)", "('/local/2', 'Chapter 2', 0, 0, 2, 1)").forEach { values ->
            driver.execute(
                null,
                "INSERT INTO chapters(url, name, read, last_page_read, chapter_number, version, manga_id, bookmark, source_order, date_fetch, date_upload) " +
                    "SELECT c.*, m._id, 0, 0, 0, 0 FROM (SELECT * FROM (VALUES $values)) c, mangas m WHERE m.url = '/local'",
                0,
            )
        }
        val shared = BackupManga(
            source = 1,
            url = "/local",
            title = "From this computer",
            favorite = true,
            chapters = listOf(
                BackupChapter(url = "/local/1", name = "Chapter 1", chapterNumber = 1f, read = false, lastPageRead = 0, version = 2),
                BackupChapter(url = "/local/2", name = "Chapter 2", chapterNumber = 2f, read = true, lastPageRead = 30, version = 7),
            ),
        )
        sync.seed(BackupCodec.encode(remote.copy(backupManga = remote.backupManga + shared)))

        manager().syncData() // the first sync only uploads
        progress("/local").mapValues { it.value.first } shouldBe mapOf("/local/1" to true, "/local/2" to false)

        manager().syncData()

        val after = progress("/local")
        // Newer here: kept
        after.getValue("/local/1").first shouldBe true
        after.getValue("/local/1").second shouldBe 12L
        // Newer on the other device: upstream's rule takes it (the safety snapshot is the way back)
        after.getValue("/local/2").first shouldBe true
        after.getValue("/local/2").second shouldBe 30L
        snapshots.size shouldBe 1
        librariesAtSnapshot.single() shouldBe mapOf("/local" to 2L)
    }

    @Test
    fun `a database read that fails while the library is built uploads nothing and keeps the ETag and sync time`() = runBlocking<Unit> {
        val remoteBefore = sync.remoteBackup
        driver.failOn(FailingSqlDriver.selectFrom("excluded_scanlators"))

        manager().syncData()

        driver.failures shouldBe 1
        messages.awaitError()
        sync.calls.filter { it.method == "PUT" }.shouldBeEmpty()
        sync.remoteBackup shouldBe remoteBefore
        preferences.lastSyncEtag().get() shouldBe ""
        preferences.lastSyncTimestamp().get() shouldBe 0L
        library() shouldBe mapOf("/local" to 0L)

        // Healed: the first sync merges both libraries and uploads them
        driver.heal()
        manager().syncData()
        Await.until("the healed sync's notification") { messages.notifications.size >= 2 }
        withClue(messages.notifications.map { it.title + ": " + it.text }) { remoteUrls() shouldBe setOf("/local", "/remote") }
        (preferences.lastSyncTimestamp().get() > 0L) shouldBe true
        // The first sync only uploads; this computer has not got the other device's entry yet
        library() shouldBe mapOf("/local" to 0L)
        preferences.lastSyncEtag().get() shouldBe ""

        // So the next sync merges the server's library again and restores it, and keeps it on the server
        manager().syncData()
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
        preferences.lastSyncEtag().get() shouldBe sync.etag
    }

    @Test
    fun `a database write that fails while the merged library is restored rolls the entry back, tells the user, and the next sync restores it`() = runBlocking<Unit> {
        // Synced before, so this sync restores the other device's entries here
        preferences.lastSyncTimestamp().set(1L)
        driver.failOn(FailingSqlDriver.insertInto("chapters"), nth = 2)

        manager().syncData()

        Await.until("the sync's notification") { messages.notifications.isNotEmpty() }
        withClue(messages.notifications.map { it.title + ": " + it.text }) { driver.failures shouldBe 1 }
        messages.awaitError { it.text.contains("SQLITE_FULL") }.text shouldContain "1 errors"
        // The merged library was built whole before the restore, and that is what was uploaded
        remoteUrls() shouldBe setOf("/local", "/remote")
        // The entry is not half restored
        library() shouldBe mapOf("/local" to 0L)
        snapshots.size shouldBe 1

        // Healed: the next sync keeps both entries on the server and restores the one that failed
        driver.heal()
        manager().syncData()
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
    }

    @Test
    fun `a sync whose safety backup fails restores nothing, and the next sync restores the other device's entries`() = runBlocking<Unit> {
        preferences.lastSyncTimestamp().set(1L)
        snapshotFails = true

        manager().syncData()

        messages.awaitError { it.text.contains("No space left on device") }
        // Uploaded before the snapshot was tried; nothing written here
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L)

        snapshotFails = false
        manager().syncData()
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
    }

    @Test
    fun `a restore that throws keeps the server's entries, and the next sync restores them`() = runBlocking<Unit> {
        preferences.lastSyncTimestamp().set(1L)
        storesFail = true

        manager().syncData()

        messages.awaitError { it.text.contains("The stores could not be read") }
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L)

        storesFail = false
        manager().syncData()
        remoteUrls() shouldBe setOf("/local", "/remote")
        library() shouldBe mapOf("/local" to 0L, "/remote" to 3L)
    }
}
