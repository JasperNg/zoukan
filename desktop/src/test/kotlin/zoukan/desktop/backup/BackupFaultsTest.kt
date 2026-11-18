package zoukan.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
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
import zoukan.desktop.track.MapPreferenceStore
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.Executors

/**
 * Backups (automatic and from Settings) under injected faults: a backup folder that cannot be
 * written, a file where the folder should be, and a database read that fails while the backup is
 * built. For each: the user is told (an error notification or toast), the app keeps going (a backup
 * after the fault is healed works), and no backup is lost or half written (the existing ones, the
 * Android app's among them, keep their bytes, and no temporary file is left).
 *
 * The backup is built by the shared code over a real database (categories, saved searches, feeds)
 * through [FailingSqlDriver]; the parts with no rows here are stubs. A full disk cannot be
 * simulated: [DesktopBackups.create] writes with `java.io.File` and has no file-system seam.
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

    private fun autoBackup(autoBackups: File = folder) = AutoBackup(
        mockk<ZoukanStorage> {
            every { requireAvailable() } just runs
            every { this@mockk.autoBackups } returns autoBackups
        },
        androidDir,
        preferences,
        written,
    )

    /** Backups already in the folder: two of Zoukan's and one of the Android app's, with their bytes. */
    private fun existingBackups(): Map<File, ByteArray> = listOf(
        "zoukan_2024-01-01_10-00.tachibk",
        "zoukan_2024-01-02_10-00.tachibk",
        "app.komikku_2024-01-03_10-00.tachibk",
    ).associate { name ->
        val bytes = kotlin.random.Random(name.hashCode()).nextBytes(512)
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
}
