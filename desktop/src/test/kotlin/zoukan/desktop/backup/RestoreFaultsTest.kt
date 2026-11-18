package zoukan.desktop.backup

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.CustomMangaRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import zoukan.desktop.compat.localized
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.testing.fakes.FailingSqlDriver
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.i18n.desktop.DMR
import java.io.File
import java.util.concurrent.Executors

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
