package zoukan.desktop.sync

import android.app.Application
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
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
import mihon.domain.extension.interactor.GetExtensionStores
import kotlinx.serialization.protobuf.ProtoBuf
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
    private val preferences = SyncPreferences(MapPreferenceStore())

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
        val store = InMemoryPreferenceStore()
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
