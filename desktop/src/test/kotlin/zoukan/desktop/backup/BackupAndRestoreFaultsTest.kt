package zoukan.desktop.backup

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.CustomMangaRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.testing.fakes.FailingSqlDriver
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.desktop.testing.fakes.WriteFaults
import zoukan.desktop.track.MapPreferenceStore
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Backups (automatic and from Settings) under injected faults: a backup folder that cannot be
 * written, a file where the folder should be, and a database read that fails while the backup is
 * built. For each: the user is told (an error notification or toast), the app keeps going (a backup
 * after the fault is healed works), and no backup is lost or half written (the existing ones, the
 * Android app's among them, keep their bytes, and no temporary file is left).
 *
 * The backup is built by the shared code over a real database (categories, saved searches, feeds)
 * through [FailingSqlDriver]; the parts with no rows here are stubs. A full disk (at the start or part
 * way through the file), a close that fails and a rename that fails go through
 * [DesktopBackups]' `openSink` and `move` seams ([WriteFaults]).
 */
class BackupFaultsTest {

    @TempDir
    lateinit var temp: File

    /** Swapped in before [start] and put back after [stop], also when either throws. */
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val messages = UserMessages()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
    private lateinit var real: JdbcSqliteDriver
    private lateinit var driver: FailingSqlDriver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val folder get() = File(temp, "storage/autobackup")
    private val androidDir get() = File(temp, "android")

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
        driver.execute(null, "INSERT INTO categories(name, sort, flags, manga_order, hidden) VALUES ('Reading', 1, 0, '', 0)", 0)

        // What the backup creators look up; the library and settings are empty here
        Injekt.addSingleton<DatabaseHandler>(handler)
        Injekt.addSingleton(GetCategories(CategoryRepositoryImpl(handler)))
        Injekt.addSingleton(mockk<GetFavorites> { coEvery { await() } returns emptyList() })
        Injekt.addSingleton(mockk<GetMergedManga> { coEvery { await() } returns emptyList() })
        Injekt.addSingleton(mockk<MangaRepository> { coEvery { getReadMangaNotInLibrary() } returns emptyList() })
        Injekt.addSingleton(mockk<GetExtensionStores> { coEvery { get() } returns emptyList() })
        Injekt.addSingleton(mockk<GetHistory>(relaxed = true))
        Injekt.addSingleton(mockk<GetCustomMangaInfo>(relaxed = true))
        Injekt.addSingleton(mockk<GetFlatMetadataById>(relaxed = true))
        Injekt.addSingleton<PreferenceStore>(InMemoryPreferenceStore())
        Injekt.addSingleton<SourceManager>(mockk<SourceManager>(relaxed = true))
        Injekt.addSingleton<StubSourceRepository>(NoStubSources)
    }

    @AfterEach
    fun stop() {
        scope.cancel()
        messages.close()
        real.close()
        executor.shutdownNow()
    }

    private val preferences = BackupPreferences(MapPreferenceStore())
    private val written = MapPreferenceStore().getString("written", "")

    private fun autoBackup(autoBackups: File = folder, backups: (() -> DesktopBackups)? = null) = AutoBackup(
        mockk<ZoukanStorage> {
            every { requireAvailable() } just runs
            every { this@mockk.autoBackups } returns autoBackups
        },
        androidDir,
        preferences,
        written,
        backups ?: { DesktopBackups(androidDir) },
    )

    // --- Writes that fail: a full disk, a close or a rename that fails ---

    private val writes = WriteFaults()

    @Volatile
    private var renameFails = false

    /** Where a backup's write fails. */
    enum class WriteFailure { DISK_FULL_AT_START, DISK_FULL_PART_WAY, CLOSE, RENAME }

    private fun inject(failure: WriteFailure) {
        when (failure) {
            WriteFailure.DISK_FULL_AT_START -> writes.fault = WriteFaults.Fault.AtOpen
            // Past the gzip header, inside the backup's data
            WriteFailure.DISK_FULL_PART_WAY -> writes.fault = WriteFaults.Fault.AfterBytes(20)
            WriteFailure.CLOSE -> writes.fault = WriteFaults.Fault.AtClose
            WriteFailure.RENAME -> renameFails = true
        }
    }

    private fun heal() {
        writes.fault = null
        renameFails = false
    }

    /** Backups written through [writes], whose move fails while [renameFails]. */
    private fun faultyBackups() = DesktopBackups(
        androidDir,
        NoStubSources,
        {},
        { null },
        openSink = writes::sink,
        move = { temp, file, replace ->
            if (renameFails) throw IOException("The process cannot access the file because it is being used by another process")
            DesktopBackups.moveIntoPlace(temp, file, replace)
        },
    )

    /** Backups already in the folder: two of Zoukan's and one of the Android app's, with their bytes. */
    private fun existingBackups(): Map<File, ByteArray> = listOf(
        "zoukan_2024-01-01_10-00.tachibk",
        "zoukan_2024-01-02_10-00.tachibk",
        "app.komikku_2024-01-03_10-00.tachibk",
    ).associate { name ->
        val bytes = Random(name.hashCode()).nextBytes(512)
        File(folder.apply { mkdirs() }, name).apply { writeBytes(bytes) } to bytes
    }

    private fun Map<File, ByteArray>.unchanged() {
        forEach { (file, bytes) -> file.readBytes() shouldBe bytes }
    }

    private fun assertFailedAndToldUser(result: File?) {
        result.shouldBeNull()
        messages.awaitError { it.title == localized(DMR.strings.automatic_backup_failed) }
        // A failure is not recorded as a backup
        preferences.lastAutoBackupTimestamp().get() shouldBe 0L
        written.get() shouldBe ""
    }

    /** A backup after the fault is healed works and holds the library. */
    private fun backupWorks(existing: Map<File, ByteArray>) = runBlocking {
        val file = autoBackup().run().shouldNotBeNull()
        (preferences.lastAutoBackupTimestamp().get() > 0L) shouldBe true
        written.get() shouldBe file.name
        DesktopBackups(androidDir, NoStubSources, {}, { null }).read(file).backupCategories.map { it.name } shouldBe listOf("Reading")
        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet() + file.name
    }

    @Test
    fun `an automatic backup into a read-only folder fails with a notification and leaves every backup as it was`() = runBlocking<Unit> {
        val existing = existingBackups()
        val readOnly = FileFaults.readOnly(folder)
        assumeTrue(readOnly != null, "This OS or user can write in a read-only folder")

        val result = readOnly!!.use { autoBackup().run() }

        assertFailedAndToldUser(result)
        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()
        backupWorks(existing)
    }

    @Test
    fun `an automatic backup whose folder is a file fails with a notification and leaves the file alone`() = runBlocking<Unit> {
        val blocker = FileFaults.fileInTheWay(folder)

        assertFailedAndToldUser(autoBackup().run())
        blocker.readText() shouldBe "A file where a folder is needed"
        folder.parentFile.list()!!.toList() shouldBe listOf(folder.name)

        blocker.delete() shouldBe true
        backupWorks(emptyMap())
    }

    @Test
    fun `a database read that fails while the backup is built writes no file and keeps the existing backups`() = runBlocking<Unit> {
        val existing = existingBackups()
        driver.failOn(FailingSqlDriver.selectFrom("categories"))

        assertFailedAndToldUser(autoBackup().run())
        driver.failures shouldBe 1
        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()

        backupWorks(existing)
    }

    @Test
    fun `a backup from Settings over an existing file in a read-only folder shows an error and keeps the old file`() = runBlocking<Unit> {
        val existing = existingBackups()
        val target = existing.keys.first()
        val readOnly = FileFaults.readOnly(folder)
        assumeTrue(readOnly != null, "This OS or user can write in a read-only folder")
        val jobs = BackupJobs(scope, { DesktopBackups(androidDir, NoStubSources, {}, { null }) }) { true }

        readOnly!!.use {
            jobs.create(target, BackupOptions())
            messages.awaitToast { it.startsWith(localized(MR.strings.creating_backup_error)) }
            withTimeout(10_000) { jobs.status.first { it == null } }
        }

        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()

        // Healed: the same backup replaces the file, as asked
        jobs.create(target, BackupOptions())
        messages.awaitToast { !it.startsWith(localized(MR.strings.creating_backup_error)) }
        withTimeout(10_000) { jobs.status.first { it == null } }
        DesktopBackups(androidDir, NoStubSources, {}, { null }).read(target).backupCategories.map { it.name } shouldBe listOf("Reading")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure::class)
    fun `an automatic backup whose write fails keeps every backup, rotates none out, and the next one works`(failure: WriteFailure) = runBlocking<Unit> {
        // Four of this install's automatic backups and the Android app's: a new one rotates the oldest out
        val own = (1..4).map { "zoukan_2024-01-0${it}_10-00.tachibk" }
        val existing = (own + "app.komikku_2024-01-05_10-00.tachibk").associate { name ->
            val bytes = Random(name.hashCode()).nextBytes(512)
            File(folder.apply { mkdirs() }, name).apply { writeBytes(bytes) } to bytes
        }
        written.set(own.joinToString("\n"))
        inject(failure)

        autoBackup(backups = ::faultyBackups).run().shouldBeNull()

        messages.awaitError { it.title == localized(DMR.strings.automatic_backup_failed) }
        preferences.lastAutoBackupTimestamp().get() shouldBe 0L
        // Nothing rotated out, nothing new recorded, no temporary file left
        written.get() shouldBe own.joinToString("\n")
        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()
        if (failure != WriteFailure.RENAME) writes.failures shouldBe 1

        // Healed: the backup is written and only then the oldest of this install's is rotated out
        heal()
        val file = autoBackup(backups = ::faultyBackups).run().shouldNotBeNull()
        DesktopBackups(androidDir, NoStubSources, {}, { null }).read(file).backupCategories.map { it.name } shouldBe listOf("Reading")
        written.get().lines() shouldBe own.drop(1) + file.name
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet() - own.first() + file.name
        existing.filterKeys { it.name != own.first() }.unchanged()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(WriteFailure::class)
    fun `a backup from Settings whose write fails keeps the file it would replace and leaves no temporary file`(failure: WriteFailure) = runBlocking<Unit> {
        val existing = existingBackups()
        val target = existing.keys.first()
        val jobs = BackupJobs(scope, ::faultyBackups) { true }
        inject(failure)

        jobs.create(target, BackupOptions())
        val toast = messages.awaitToast { it.startsWith(localized(MR.strings.creating_backup_error)) }
        withTimeout(10_000) { jobs.status.first { it == null } }

        if (failure != WriteFailure.RENAME) toast.contains(WriteFaults.DISK_FULL) shouldBe true
        existing.unchanged()
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()

        // Healed: the same backup replaces the file, as asked
        heal()
        jobs.create(target, BackupOptions())
        messages.awaitToast { !it.startsWith(localized(MR.strings.creating_backup_error)) }
        withTimeout(10_000) { jobs.status.first { it == null } }
        DesktopBackups(androidDir, NoStubSources, {}, { null }).read(target).backupCategories.map { it.name } shouldBe listOf("Reading")
        folder.list()!!.toSet() shouldBe existing.keys.map { it.name }.toSet()
    }
}

/**
 * Restores under injected faults.
 *
 * A database write that fails inside an entry's restore (the shared MangaRestorer over a real
 * database, through [FailingSqlDriver]): the entry's transaction is rolled back whole (no entry
 * without its chapters), the error is in the result the restore reports, the other entries are
 * restored, and restoring again after the fault is healed restores it without duplicating the rest.
 *
 * The custom info (`edits.json`, written by the entry's restore through the shared
 * CustomMangaRepositoryImpl) in a folder that cannot be written: the entry is rolled back whole and
 * reported, `edits.json` keeps its bytes, no other entry gets the custom info, and a second restore
 * after the fault is healed saves it.
 *
 * A backup file that is cut off, corrupt or not a backup (through [BackupJobs], as Settings starts
 * a restore): the user gets the error toast, nothing is restored and no safety backup is taken, the
 * file is left as it was, and a good file restores next.
 */
class RestoreFaultsTest {

    @TempDir
    lateinit var temp: File

    private val messages = UserMessages()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var real: JdbcSqliteDriver? = null

    @AfterEach
    fun stop() {
        scope.cancel()
        messages.close()
        real?.close()
        executor.shutdownNow()
    }

    // --- A database write fails inside an entry's restore ---

    /** The database through [FailingSqlDriver], and a new restorer per restore over it, as the app makes one per restore. */
    private fun database(setCustomMangaInfo: SetCustomMangaInfo = mockk(relaxed = true)): Pair<FailingSqlDriver, () -> BackupContentRestorer> {
        val jdbc = JdbcSqliteDriver(
            url = "jdbc:sqlite:${File(temp, "zoukan.db").absolutePath}",
            properties = DesktopDatabase.properties(ByteArray(32) { it.toByte() }),
            schema = Database.Schema,
        ).also { real = it }
        val driver = FailingSqlDriver(jdbc)
        val db = DesktopDatabase.createDatabase(driver)
        val dispatcher = executor.asCoroutineDispatcher()
        val handler = AndroidDatabaseHandler(db, driver, queryDispatcher = dispatcher, transactionDispatcher = dispatcher)
        val mangas = MangaRepositoryImpl(handler)
        val chapters = GetChaptersByMangaId(ChapterRepositoryImpl(handler))
        val tracks = TrackRepositoryImpl(handler)
        fun mangaRestorer() = MangaRestorer(
            isSync = false,
            handler = handler,
            getCategories = GetCategories(CategoryRepositoryImpl(handler)),
            getMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(mangas),
            getChaptersByMangaId = chapters,
            mangaRepository = mangas,
            getTracks = GetTracks(tracks),
            insertTrack = InsertTrack(tracks),
            fetchInterval = FetchInterval(chapters),
            setCustomMangaInfo = setCustomMangaInfo,
            insertFlatMetadata = mockk(relaxed = true),
            getFlatMetadataById = mockk(relaxed = true),
        )
        val restorer = {
            BackupContentRestorer(
                categoriesRestorer = mockk(relaxed = true),
                preferenceRestorer = mockk(relaxed = true),
                extensionStoreRestorer = mockk(relaxed = true),
                mangaRestorer = mangaRestorer(),
                savedSearchRestorer = mockk(relaxed = true),
                feedRestorer = mockk(relaxed = true),
            )
        }
        return driver to restorer
    }

    private fun entry(key: String, modified: Long) = BackupManga(
        source = 1L,
        url = "/$key",
        title = "Entry $key",
        lastModifiedAt = modified,
        // Not in the library: a favorite reads its custom title through the runtime, not started here
        favorite = false,
        chapters = (1..3).map { BackupChapter(url = "/$key/$it", name = "Chapter $it", chapterNumber = it.toFloat(), sourceOrder = it.toLong()) },
    )

    /** Each entry's URL and how many chapters it has, from the database. */
    private fun FailingSqlDriver.library(): Map<String, Long> = executeQuery(
        null,
        "SELECT m.url, (SELECT COUNT(*) FROM chapters c WHERE c.manga_id = m._id) FROM mangas m ORDER BY m.url",
        { cursor ->
            val rows = linkedMapOf<String, Long>()
            while (cursor.next().value) rows[cursor.getString(0)!!] = cursor.getLong(1)!!
            QueryResult.Value(rows)
        },
        0,
    ).value

    @Test
    fun `a database write that fails inside an entry rolls the entry back whole, is reported, and a second restore completes it`() = runBlocking<Unit> {
        val (driver, restorer) = database()
        // A is restored first (the newest), and its second chapter's insert fails
        val backup = Backup(backupManga = listOf(entry("a", modified = 2000), entry("b", modified = 1000)))
        driver.failOn(FailingSqlDriver.insertInto("chapters"), nth = 2)

        val errors = restorer().restore(backup, RestoreOptions())

        driver.failures shouldBe 1
        errors.size shouldBe 1
        errors.single().second shouldContain "Entry a"
        errors.single().second shouldContain "SQLITE_FULL"
        // Nothing of A is left half restored; B is whole
        driver.library() shouldBe mapOf("/b" to 3L)

        // Healed: a second restore restores A and leaves B as it was
        val again = restorer().restore(backup, RestoreOptions())
        again.shouldBeEmpty()
        driver.library() shouldBe mapOf("/a" to 3L, "/b" to 3L)
    }

    // --- The custom info cannot be saved ---

    private val editsFolder get() = File(temp, "files")

    private fun customInfo() = CustomMangaRepositoryImpl(mockk<Context> { every { getExternalFilesDir(null) } returns editsFolder })

    /** The database's id of the entry at [url]. */
    private fun FailingSqlDriver.idOf(url: String): Long = executeQuery(
        null,
        "SELECT _id FROM mangas WHERE url = '$url'",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else error("No entry $url")) },
        0,
    ).value

    @ParameterizedTest(name = "{0}")
    @MethodSource("customInfoFaults")
    fun `custom info that cannot be saved rolls its entry back, keeps edits_json, and goes to no other entry`(
        name: String,
        fault: (File) -> AutoCloseable?,
    ) = runBlocking<Unit> {
        editsFolder.mkdirs()
        // An edit the user made before
        customInfo().set(CustomMangaInfo(id = 99, title = "Edited before"))
        val edits = File(editsFolder, "edits.json")
        val editsBefore = edits.readBytes()
        val repository = customInfo()
        val (driver, restorer) = database(SetCustomMangaInfo(repository))
        // A is restored first (the newest), with a custom title
        val backup = Backup(backupManga = listOf(entry("a", modified = 2000).apply { customTitle = "Custom A" }, entry("b", modified = 1000)))
        val healed = fault(editsFolder)
        assumeTrue(healed != null, "This OS or user can write in a read-only folder")

        val errors = healed!!.use { restorer().restore(backup, RestoreOptions()) }

        errors.size shouldBe 1
        errors.single().second shouldContain "Entry a"
        driver.library() shouldBe mapOf("/b" to 3L)
        edits.readBytes() shouldBe editsBefore
        // B was given the id A had before A was rolled back: it must not show A's title, now or after a restart
        val b = driver.idOf("/b")
        repository.get(b).shouldBeNull()
        customInfo().get(b).shouldBeNull()
        repository.get(99)?.title shouldBe "Edited before"

        // Healed: a second restore saves A's custom title, for A only
        val again = restorer().restore(backup, RestoreOptions())
        again.shouldBeEmpty()
        driver.library() shouldBe mapOf("/a" to 3L, "/b" to 3L)
        customInfo().get(driver.idOf("/a"))?.title shouldBe "Custom A"
        customInfo().get(b).shouldBeNull()
        customInfo().get(99)?.title shouldBe "Edited before"
    }

    // --- A backup file that is cut off, corrupt or not a backup ---

    private val good: ByteArray by lazy { BackupCodec.encode(SyntheticBackup.create()) }

    private fun desktopBackups(): DesktopBackups =
        spyk(DesktopBackups(File(temp, "android"), NoStubSources, {}, { null })) {
            coEvery { restore(any(), any(), any(), any(), any()) } returns emptyList()
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFiles")
    fun `a bad backup file fails the restore with an error toast, restores nothing and is left as it was`(
        name: String,
        bytes: (ByteArray) -> ByteArray,
    ) = runBlocking<Unit> {
        val bad = File(temp, "bad.tachibk").apply { writeBytes(bytes(good)) }
        val badBytes = bad.readBytes()
        val backups = desktopBackups()
        val snapshots = mutableListOf<File>()
        val snapshot = SafetySnapshot(folder = { File(temp, "safety") }, write = { file -> snapshots += file.also { it.writeText("before") } })
        val jobs = BackupJobs(scope, { backups }, snapshot = snapshot) { true }
        val options = RestoreOptions(extensionStores = false)

        withTimeout(30_000) { jobs.restore(bad, options)!!.await() } shouldBe RestoreOutcome.FAILED

        messages.awaitToast { it.startsWith(localized(DMR.strings.restore_failed)) } shouldStartWith localized(DMR.strings.restore_failed)
        coVerify(exactly = 0) { backups.restore(any(), any(), any(), any(), any()) }
        snapshots.shouldBeEmpty()
        bad.readBytes() shouldBe badBytes
        jobs.status.value shouldBe null

        // The app goes on: a good file restores next
        val goodFile = File(temp, "good.tachibk").apply { writeBytes(good) }
        withTimeout(30_000) { jobs.restore(goodFile, options)!!.await() } shouldBe RestoreOutcome.OK
        coVerify(exactly = 1) { backups.restore(any(), any(), any(), any(), any()) }
    }

    companion object {
        /** Makes saving `edits.json` in the folder fail; the result heals it (null: cannot be made here). */
        @JvmStatic
        fun customInfoFaults() = listOf(
            arrayOf<Any>("a read-only folder", { dir: File -> FileFaults.readOnly(dir) }),
            arrayOf<Any>(
                "a folder where its temporary file goes",
                { dir: File ->
                    val inTheWay = File(dir, "edits.json.tmp").apply { mkdirs() }
                    File(inTheWay, "kept").writeText("Not the app's")
                    AutoCloseable {
                        File(inTheWay, "kept").delete()
                        inTheWay.delete()
                    }
                },
            ),
        )

        @JvmStatic
        fun badFiles() = listOf(
            arrayOf<Any>("cut off halfway", { b: ByteArray -> b.copyOf(b.size / 2) }),
            arrayOf<Any>("corrupt after the header", { b: ByteArray -> ByteArray(b.size) { i -> if (i < 16) b[i] else b[i].toInt().inv().toByte() } }),
            arrayOf<Any>("empty", { _: ByteArray -> ByteArray(0) }),
            arrayOf<Any>("not a backup", { _: ByteArray -> "{\"backupManga\": []}".toByteArray() }),
        )
    }
}

/** A restore reports a failing section and still restores everything else. */
class RestorePartialFailureTest {

    private fun mangaRestorer() = mockk<MangaRestorer> {
        coEvery { sortByNew(any()) } answers { firstArg() }
        coEvery { restore(any(), any()) } just Runs
    }

    private val entries = listOf("A", "B", "C").map { BackupManga(source = 1L, url = "/$it", title = it) }

    @Test
    fun `a failing section is reported and every entry is still restored`() {
        val mangaRestorer = mangaRestorer()
        val restorer = BackupContentRestorer(
            categoriesRestorer = mockk(relaxed = true),
            preferenceRestorer = mockk(relaxed = true),
            extensionStoreRestorer = mockk(relaxed = true),
            mangaRestorer = mangaRestorer,
            savedSearchRestorer = mockk<SavedSearchRestorer> {
                coEvery { restoreSavedSearches(any()) } throws IllegalStateException("database is locked")
            },
            feedRestorer = mockk(relaxed = true),
        )
        val backup = Backup(
            backupManga = entries,
            backupSavedSearches = listOf(BackupSavedSearch(name = "s", source = 1L)),
        )

        val errors = runBlocking { restorer.restore(backup, RestoreOptions()) }

        coVerify(exactly = 3) { mangaRestorer.restore(any(), any()) }
        errors.map { it.second }.any { "database is locked" in it } shouldBe true
    }

    @Test
    fun `a failing category section does not stop the restore`() {
        val mangaRestorer = mangaRestorer()
        val restorer = BackupContentRestorer(
            categoriesRestorer = mockk<CategoriesRestorer> {
                coEvery { this@mockk.invoke(any()) } throws IllegalStateException("category insert failed")
            },
            preferenceRestorer = mockk(relaxed = true),
            extensionStoreRestorer = mockk(relaxed = true),
            mangaRestorer = mangaRestorer,
            savedSearchRestorer = mockk(relaxed = true),
            feedRestorer = mockk(relaxed = true),
        )
        val backup = Backup(
            backupManga = entries,
            backupCategories = listOf(BackupCategory(name = "c", order = 0)),
        )

        val errors = runBlocking { restorer.restore(backup, RestoreOptions()) }

        coVerify(exactly = 3) { mangaRestorer.restore(any(), any()) }
        errors.map { it.second }.any { "category insert failed" in it } shouldBe true
    }
}
