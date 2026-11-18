package zoukan.desktop.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.fakes.FailingSqlDriver
import zoukan.desktop.track.MapPreferenceStore
import java.io.File
import java.util.concurrent.Executors

/**
 * The download manager's database reads under an injected database failure ([FailingSqlDriver] over
 * a real database), one test per read:
 *
 * - restoring the saved queue at start-up (the entries and chapters it names): a known defect, the
 *   test is disabled;
 * - the categories an automatic deletion checks ("Exclude categories");
 * - the entry a deletion of read chapters looks up;
 * - the other entries sharing a download folder, which a delete keeps the files of.
 *
 * The categories ComicInfo.xml lists are read too, and on purpose optional: DownloadFaultsTest
 * downloads with a categories read that fails. The queue is saved to a file, the downloaded chapters
 * are found on disk (DownloadIndex) and the desktop keeps no "downloaded" flag in the database, so
 * downloading writes nothing to the database; reading and history are the reader's, not the
 * download path's.
 */
class DownloadDatabaseFaultsTest {

    @TempDir
    lateinit var temp: File

    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "zoukan-database").apply { isDaemon = true } }
    private lateinit var real: JdbcSqliteDriver
    private lateinit var driver: FailingSqlDriver
    private lateinit var kit: DownloadKit
    private lateinit var manager: DownloadManager
    private lateinit var getManga: GetManga
    private lateinit var getChapter: GetChapter

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
        // An entry outside the library (a favorite reads its custom title through the runtime) with one read chapter
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, cover_last_modified, date_added) " +
                "VALUES (1, 1, '/m1', 'Manga', 0, 0, 1, 0, 0, 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (1, 1, '/c1', 'Chapter 1', 1, 0, 0, 1, 1, 0, 0)",
            0,
        )
        val mangas = MangaRepositoryImpl(handler)
        val chapters = ChapterRepositoryImpl(handler)
        // What the delete's look-up of the entries sharing a folder reads
        Injekt.addSingleton<MangaRepository>(mangas)
        Injekt.addSingleton<ChapterRepository>(chapters)
        getManga = GetManga(mangas)
        getChapter = GetChapter(chapters)
        kit = DownloadKit(temp)
        manager = DownloadManager(
            ZoukanDirs(temp),
            kit.paths,
            kit.sourceManager,
            // The kit's in-memory store has no string sets, which the automatic deletion reads
            DownloadPreferences(MapPreferenceStore()),
            io.mockk.mockk(),
            getManga,
            getChapter,
            GetCategories(CategoryRepositoryImpl(handler)),
        )
    }

    @AfterEach
    fun stop() {
        runBlocking { manager.awaitBackgroundWork() }
        real.close()
        executor.shutdownNow()
    }

    private fun manga(): Manga = runBlocking { getManga.await(1)!! }

    private fun chapter(): Chapter = runBlocking { getChapter.await(1)!! }

    /** Chapter 1 downloaded, with its bytes. */
    private fun downloaded(): Pair<File, ByteArray> {
        val bytes = kotlin.random.Random(1).nextBytes(256)
        val file = File(kit.paths.mangaDir(manga()), "${kit.paths.chapterDirName(chapter())}.cbz")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return file to bytes
    }

    private val queueFile get() = File(temp, "downloads-queue.json")

    @Test
    @Disabled(
        "Known defect: GetManga and GetChapter return null on a database error, so restore() drops " +
            "every saved download as if its entry or chapter were deleted, and the next save of the " +
            "queue overwrites the saved file. The user is not told.",
    )
    fun `a database read that fails while the saved queue is restored loses no saved download`() = runBlocking<Unit> {
        queueFile.writeText("""[{"mangaId":1,"chapterId":1}]""")
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))

        manager.restore(start = false)
        // The queue is saved again on any change: here a chapter queued and cancelled
        manager.enqueue(manga(), listOf(chapter().copy(id = 2, url = "/c2")), start = false)
        manager.cancel(setOf(2L))
        manager.awaitBackgroundWork()

        // The next start restores it
        driver.heal()
        val next = DownloadManager(ZoukanDirs(temp), kit.paths, kit.sourceManager, DownloadPreferences(MapPreferenceStore()), io.mockk.mockk(), getManga, getChapter, io.mockk.mockk())
        next.restore(start = false)
        next.queue.value.map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `a categories read that fails stops an automatic deletion, which keeps the chapter`() = runBlocking<Unit> {
        val (file, bytes) = downloaded()
        driver.failOn(FailingSqlDriver.selectFrom("categories"))

        manager.deleteRead(listOf(chapter()))

        driver.failures shouldBe 1
        file.readBytes() shouldBe bytes

        // Healed: the next automatic deletion deletes it
        manager.deleteRead(listOf(chapter()))
        file.exists() shouldBe false
    }

    @Test
    fun `an entry read that fails stops a deletion of read chapters, which keeps the chapter`() = runBlocking<Unit> {
        val (file, bytes) = downloaded()
        val chapter = chapter()
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))

        // GetManga logs the error and finds no entry: nothing is deleted, and marking as read goes on
        manager.deleteRead(listOf(chapter))

        driver.failures shouldBe 1
        file.readBytes() shouldBe bytes
        manager.deleteRead(listOf(chapter))
        file.exists() shouldBe false
    }

    @Test
    fun `a read of the entries sharing the folder that fails stops a delete, which keeps every file`() = runBlocking<Unit> {
        val (file, bytes) = downloaded()
        val manga = manga()
        val chapter = chapter()
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))

        // The delete fails rather than guess which files are another entry's; the screens show its error
        shouldThrow<Exception> { manager.delete(manga, listOf(chapter)) }.message shouldContain "SQLITE_FULL"

        file.readBytes() shouldBe bytes
        manager.delete(manga, listOf(chapter))
        file.exists() shouldBe false
    }
}
