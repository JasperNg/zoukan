package zoukan.desktop.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import zoukan.desktop.tray.Notifications
import java.io.File
import java.util.concurrent.Executors

/**
 * The download manager's database reads under an injected database failure ([FailingSqlDriver] over
 * a real database), one test per read:
 *
 * - restoring the saved queue at start-up (the entries and chapters it names): a failed read keeps
 *   the downloads not yet restored in the saved file, apart from an entry or chapter that was deleted;
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

    /** Adds chapter [id] of entry 1. */
    private fun addChapter(id: Long) {
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES ($id, 1, '/c$id', 'Chapter $id', 0, 0, 0, $id, $id, 0, 0)",
            0,
        )
    }

    private fun saved(vararg downloads: Pair<Long, Long>) =
        downloads.joinToString(",", "[", "]") { (manga, chapter) -> """{"mangaId":$manga,"chapterId":$chapter}""" }

    /** The chapter ids in the saved queue file, in order. */
    private fun savedChapterIds(): List<Long> =
        Regex(""""chapterId":(\d+)""").findAll(queueFile.readText()).map { it.groupValues[1].toLong() }.toList()

    /** A manager on the same data folder and database, as at the next start. */
    private fun nextStart() = DownloadManager(
        ZoukanDirs(temp),
        kit.paths,
        kit.sourceManager,
        DownloadPreferences(MapPreferenceStore()),
        io.mockk.mockk(),
        getManga,
        getChapter,
        io.mockk.mockk(),
    )

    @Test
    fun `a restore keeps the saved order and queues every chapter`() = runBlocking<Unit> {
        addChapter(2)
        addChapter(3)
        queueFile.writeText(saved(1L to 3L, 1L to 1L, 1L to 2L))

        manager.restore(start = false)

        manager.queue.value.map { it.chapter.id } shouldBe listOf(3L, 1L, 2L)
        manager.queue.value.map { it.state.value }.toSet() shouldBe setOf(Download.State.QUEUED)
        manager.queue.value.map { it.manga.id }.toSet() shouldBe setOf(1L)
    }

    @Test
    fun `a saved download whose entry or chapter was deleted is dropped, and the next save leaves it out`() = runBlocking<Unit> {
        addChapter(2)
        // Entry 7 and chapter 9 do not exist (deleted since the queue was saved)
        queueFile.writeText(saved(1L to 1L, 7L to 70L, 1L to 9L, 1L to 2L))

        manager.restore(start = false)
        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L, 2L)

        manager.cancel(setOf(2L))
        manager.awaitBackgroundWork()
        savedChapterIds() shouldBe listOf(1L)
    }

    @Test
    fun `downloads queued before the restore keep their place, and a chapter is never queued twice`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        manager.enqueue(manga(), listOf(getChapter.await(2)!!), start = false)

        manager.restore(start = false)

        manager.queue.value.map { it.chapter.id } shouldBe listOf(2L, 1L)
    }

    /**
     * Runs [block] and returns the notifications it posted. The collector is unconfined, so a post
     * is in the list when it returns, and cancelling the collector afterwards loses none.
     */
    private suspend fun posted(block: suspend () -> Unit): List<Notifications.Message> = coroutineScope {
        val posted = java.util.Collections.synchronizedList(mutableListOf<Notifications.Message>())
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(posted) }
        yield()
        try {
            block()
            manager.awaitBackgroundWork()
        } finally {
            collector.cancelAndJoin()
        }
        posted
    }

    @Test
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
        val next = nextStart()
        next.restore(start = false)
        next.queue.value.map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `a restore whose database read fails leaves the saved file as it was and tells the user`() = runBlocking<Unit> {
        addChapter(2)
        val before = saved(1L to 1L, 1L to 2L)
        queueFile.writeText(before)
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))

        val messages = posted { manager.restore(start = false) }

        driver.failures shouldBe 1
        manager.queue.value.shouldBeEmpty()
        queueFile.readText() shouldBe before
        messages.filter { it.kind == Notifications.Kind.WARNING }.shouldHaveSize(1)
    }

    @Test
    fun `a read that fails part way restores the downloads before it and keeps the rest saved`() = runBlocking<Unit> {
        addChapter(2)
        addChapter(3)
        queueFile.writeText(saved(1L to 1L, 1L to 2L, 1L to 3L))
        // The second chapter's read fails; the third would succeed
        driver.failOn(FailingSqlDriver.selectFrom("chapters"), nth = 2)

        manager.restore(start = false)
        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L)

        // A save (chapter 1 cancelled) keeps the downloads not restored
        manager.cancel(setOf(1L))
        manager.awaitBackgroundWork()
        savedChapterIds() shouldBe listOf(2L, 3L)

        val next = nextStart()
        next.restore(start = false)
        next.queue.value.map { it.chapter.id } shouldBe listOf(2L, 3L)
    }

    @Test
    fun `a download the user queues while the saved queue is held is saved with it`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))
        manager.restore(start = false)

        manager.enqueue(manga(), listOf(getChapter.await(2)!!), start = false)
        manager.awaitBackgroundWork()

        manager.queue.value.map { it.chapter.id } shouldBe listOf(2L)
        savedChapterIds() shouldBe listOf(2L, 1L)
        val next = nextStart()
        next.restore(start = false)
        next.queue.value.map { it.chapter.id } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `a restore cancelled part way is not a database failure, and the saved queue stays`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L))
        driver.failOn(FailingSqlDriver.selectFrom("chapters"), error = { CancellationException("The app is quitting") })

        val messages = posted {
            shouldThrow<CancellationException> { manager.restore(start = false) }
            // A save after it keeps the saved download
            manager.enqueue(manga(), listOf(getChapter.await(2)!!), start = false)
        }

        messages.shouldBeEmpty()
        savedChapterIds() shouldBe listOf(2L, 1L)
    }

    @Test
    fun `a save before the restore keeps the saved queue`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L))
        // The library update queues a chapter while the sources still load, before the restore
        manager.enqueue(manga(), listOf(getChapter.await(2)!!), start = false)
        manager.awaitBackgroundWork()
        savedChapterIds() shouldBe listOf(2L, 1L)

        manager.restore(start = false)
        manager.queue.value.map { it.chapter.id } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `a held download the user queues again and cancels is not kept`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))
        manager.restore(start = false)

        manager.enqueue(manga(), listOf(chapter()), start = false)
        manager.cancel(setOf(1L))
        manager.awaitBackgroundWork()

        savedChapterIds() shouldBe listOf(2L)
    }

    @Test
    fun `clearing the queue removes every queued download and the saved file is left empty`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        manager.restore(start = false)

        manager.clearQueue()
        manager.awaitBackgroundWork()

        manager.queue.value.shouldBeEmpty()
        savedChapterIds().shouldBeEmpty()
    }

    @Test
    fun `cancelling a download that is only held takes it out of the saved queue`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))
        manager.restore(start = false)
        manager.queue.value.shouldBeEmpty()

        manager.cancel(setOf(2L))
        manager.awaitBackgroundWork()

        savedChapterIds() shouldBe listOf(1L)
        // Nor does it come back when the held queue is read again
        manager.start()
        manager.awaitBackgroundWork()
        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L)
    }

    @Test
    fun `clearing the queue clears the held downloads too, also in the saved file`() = runBlocking<Unit> {
        addChapter(2)
        addChapter(3)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))
        manager.restore(start = false)
        manager.enqueue(manga(), listOf(getChapter.await(3)!!), start = false)

        manager.clearQueue()
        manager.awaitBackgroundWork()
        savedChapterIds().shouldBeEmpty()

        // They do not come back in the same run when the queue starts again
        manager.start()
        manager.awaitBackgroundWork()
        manager.queue.value.shouldBeEmpty()
        savedChapterIds().shouldBeEmpty()
    }

    @Test
    fun `a download cancelled while the saved queue is read from the database is not restored`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        val reached = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        // Chapter 1 is read; the read of chapter 2 waits for the cancel, then fails
        driver.failOn(FailingSqlDriver.selectFrom("chapters"), nth = 2, error = {
            reached.countDown()
            go.await()
            java.sql.SQLException("[SQLITE_BUSY] database is locked (injected)")
        })

        val restore = launch(Dispatchers.IO) { manager.restore(start = false) }
        reached.await()
        manager.cancel(setOf(1L, 2L))
        go.countDown()
        restore.join()
        manager.awaitBackgroundWork()

        manager.queue.value.shouldBeEmpty()
        savedChapterIds().shouldBeEmpty()
        manager.start()
        manager.awaitBackgroundWork()
        manager.queue.value.shouldBeEmpty()
    }

    @Test
    fun `a restore whose database read fails does not read the held queue again at once`() = runBlocking<Unit> {
        queueFile.writeText(saved(1L to 1L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"), times = Int.MAX_VALUE)

        manager.restore(start = true)
        manager.awaitBackgroundWork()

        driver.failures shouldBe 1
        savedChapterIds() shouldBe listOf(1L)
    }

    @Test
    fun `a held queue is restored in the same run when the queue is started after the database reads again`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        // The database keeps failing until it is healed
        driver.failOn(FailingSqlDriver.selectFrom("mangas"), times = Int.MAX_VALUE)
        manager.restore(start = false)
        manager.queue.value.shouldBeEmpty()

        // Started while the database still fails: nothing is lost and nothing is queued twice
        manager.start()
        manager.awaitBackgroundWork()
        manager.queue.value.shouldBeEmpty()
        savedChapterIds() shouldBe listOf(1L, 2L)

        driver.heal()
        manager.start()
        manager.awaitBackgroundWork()

        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L, 2L)
        savedChapterIds() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a held download the user queued again meanwhile is not queued twice when the held queue is restored`() = runBlocking<Unit> {
        addChapter(2)
        queueFile.writeText(saved(1L to 1L, 1L to 2L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))
        manager.restore(start = false)
        driver.heal()

        // The user queues chapter 2 again, which starts the queue and with it the restore of the held ones
        manager.enqueue(manga(), listOf(getChapter.await(2)!!))
        manager.awaitBackgroundWork()

        manager.queue.value.map { it.chapter.id }.sorted() shouldBe listOf(1L, 2L)
        savedChapterIds().sorted() shouldBe listOf(1L, 2L)
    }

    @Test
    fun `a saved download naming another entry than its chapter's does not drop the entry's others`() = runBlocking<Unit> {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags, cover_last_modified, date_added) " +
                "VALUES (2, 1, '/m2', 'Other', 0, 0, 1, 0, 0, 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (20, 2, '/c20', 'Chapter 20', 0, 0, 0, 20, 20, 0, 0)",
            0,
        )
        // Chapter 20 is entry 2's, saved under entry 1; the entry's read fails once
        queueFile.writeText(saved(1L to 20L, 1L to 1L))
        driver.failOn(FailingSqlDriver.selectFrom("mangas"))

        manager.restore(start = false)

        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L)
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
