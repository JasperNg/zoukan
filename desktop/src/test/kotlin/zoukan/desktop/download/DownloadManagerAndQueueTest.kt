package zoukan.desktop.download

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import logcat.LogPriority
import logcat.LogcatLogger
import net.lingala.zip4j.ZipFile
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.Slf4jLogcatLogger
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.tray.Notifications
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.random.Random

private fun <T> pref(value: T): Preference<T> = mockk { every { get() } returns value }

/** The download queue against a source on a local web server, with no network and no database. */
class DownloadManagerTest {

    @TempDir
    lateinit var temp: File

    private lateinit var server: HttpServer
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val status = if (exchange.requestURI.path.contains("broken")) 404 else 200
                val body = if (status == 200) png else ByteArray(0)
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private inner class FakeSource : HttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            val count = chapter.url.substringAfterLast('/').toInt()
            return (0 until count).map { Page(it, imageUrl = "$baseUrl${chapter.url}/$it.png") }
        }
    }

    private fun chapter(id: Long, url: String) = Chapter.create().copy(id = id, mangaId = 1, url = url, name = "Chapter $id", sourceOrder = 100 - id)

    @Test
    fun `downloads, encrypts, finds, fails and deletes chapters`() = runBlocking<Unit> {
        val source = FakeSource()
        val sourceManager = mockk<SourceManager> {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val manga = mockk<Manga>(relaxed = true) {
            every { id } returns 1L
            every { this@mockk.source } returns source.id
            every { title } returns "Test: Manga"
            every { ogTitle } returns "Test: Manga"
            every { genre } returns listOf("Action")
            every { description } returns null
            every { author } returns null
            every { artist } returns null
        }
        val dirs = ZoukanDirs(temp)
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(true)
            every { parallelSourceLimit() } returns pref(5)
            every { parallelPageLimit() } returns pref(5)
            every { splitTallImages() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val protect = mockk<Preference<Boolean>> { every { get() } returns false }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns protect
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val manager = DownloadManager(dirs, paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))

        val one = chapter(1, "/c/3")
        val two = chapter(2, "/c/5")
        val broken = chapter(3, "/broken/2")

        manager.enqueue(manga, listOf(one))
        withTimeout(30_000) { manager.awaitIdle() }
        manager.isDownloaded(manga, one) shouldBe true
        val file = manager.findChapter(manga, one)!!
        file.path shouldBe File(temp, "downloads/Fake (EN)/Test_ Manga/Chapter 1.cbz").path
        LocalFormat.open(file).use { it.pageCount shouldBe 3 }

        // Encrypted
        password.set("pw")
        every { protect.get() } returns true
        manager.enqueue(manga, listOf(two, broken))
        withTimeout(60_000) { manager.awaitIdle() }
        val encrypted = manager.findChapter(manga, two)!!
        ZipFile(encrypted).isEncrypted shouldBe true
        LocalFormat.open(encrypted) { password.get() }.use { it.pageCount shouldBe 5 }

        // The broken one stays in the queue with its error
        manager.queue.value.map { it.chapter.id } shouldBe listOf(3L)
        manager.queue.value.single().state.value shouldBe Download.State.ERROR
        manager.queue.value.single().error shouldNotBe null
        manager.isDownloaded(manga, broken) shouldBe false
        manager.cancel(setOf(3L))
        manager.queue.value.shouldBeEmpty()

        manager.downloadedIds(manga, listOf(one, two, broken)) shouldBe setOf(1L, 2L)
        manager.delete(manga, listOf(one, two))
        manager.downloadedIds(manga, listOf(one, two, broken)) shouldBe emptySet()
        File(temp, "downloads/Fake (EN)/Test_ Manga").exists() shouldBe false
        // Queue saves run in the background: one still writing when the test ends made the temp
        // folder's clean-up fail (as in DownloadQueueStatesTest)
        withTimeout(10_000) { manager.awaitBackgroundWork() }
    }
}

/** The screens call the download manager from the UI dispatcher: reading download folders must not run there. */
class DownloadManagerMainSafetyTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource(override val name: String, override val id: Long) : HttpSource() {
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    private val fakeSource = FakeSource("A", 1)
    private val onEdt = CopyOnWriteArrayList<Boolean>()

    private fun manga(id: Long, title: String) = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.source } returns fakeSource.id
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { favorite } returns true
    }

    private fun chapter(id: Long, mangaId: Long, number: Double) =
        Chapter.create().copy(id = id, mangaId = mangaId, url = "/c$id", name = "Chapter $id", chapterNumber = number, sourceOrder = 1000 - id)

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun waitForSaves() = runBlocking {
        withTimeout(10_000) { managers.forEach { it.awaitBackgroundWork() } }
    }

    private fun manager(): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(fakeSource.id) } returns fakeSource
            every { getOrStub(fakeSource.id) } returns fakeSource
            every { getOnlineSources() } returns listOf(fakeSource)
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            // Read on every folder listing: records the thread it runs on
            every { downloadCacheRenewInterval() } answers {
                onEdt += EventQueue.isDispatchThread()
                pref(-1)
            }
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(false)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
    }

    @Test
    fun `queueing from the UI thread lists download folders off the UI thread`() = runBlocking<Unit> {
        val manager = manager()
        val one = manga(1, "One")
        val two = manga(2, "Two")
        withContext(Dispatchers.Swing) {
            manager.enqueueAll(listOf(one to listOf(chapter(1, 1, 1.0)), two to listOf(chapter(2, 2, 1.0))), start = false)
        }
        onEdt.shouldNotBeEmpty()
        onEdt.none { it } shouldBe true
        manager.queue.value.map { it.chapter.id } shouldBe listOf(1L, 2L)
    }
}

/** The download queue's states: pausing, resuming, queueing while it drains, reordering. */
class DownloadQueueStatesTest {

    @TempDir
    lateinit var temp: File

    private lateinit var server: HttpServer
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    /** Optional gate invoked on each page request so tests can pause deterministically mid-chapter. */
    @Volatile
    private var pageGate: (() -> Unit)? = null

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/") { exchange ->
                pageGate?.invoke()
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            start()
        }
    }

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun stopServer() {
        pageGate = null
        // Queue saves run in the background; one still writing when the test ends made the
        // temp folder's clean-up fail (DirectoryNotEmptyException)
        runBlocking {
            withTimeout(10_000) {
                managers.forEach {
                    it.pause()
                    it.awaitBackgroundWork()
                }
            }
        }
        server.stop(0)
    }

    private inner class FakeSource : HttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()

        override suspend fun getPageList(chapter: SChapter): List<Page> {
            val count = chapter.url.substringAfterLast('/').toInt()
            return (0 until count).map { Page(it, imageUrl = "$baseUrl${chapter.url}/$it.png") }
        }
    }

    private fun chapter(id: Long, pages: Int) =
        Chapter.create().copy(id = id, mangaId = 1, url = "/c$id/$pages", name = "Chapter $id", sourceOrder = 1000 - id)

    private val source by lazy { FakeSource() }

    private val manga by lazy {
        mockk<Manga>(relaxed = true) {
            every { id } returns 1L
            every { this@mockk.source } returns this@DownloadQueueStatesTest.source.id
            every { title } returns "Manga"
            every { ogTitle } returns "Manga"
            every { genre } returns null
            every { description } returns null
            every { author } returns null
            every { artist } returns null
        }
    }

    private fun manager(asCbz: Boolean = true): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(asCbz)
            every { parallelSourceLimit() } returns pref(1)
            every { parallelPageLimit() } returns pref(1)
            every { splitTallImages() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(false)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
    }

    @Test
    fun `a chapter copied into the folder after it was listed keeps its bytes when the same chapter downloads`() = runBlocking<Unit> {
        val manager = manager()
        val chapter = chapter(1, pages = 3)
        manager.isDownloaded(manga, chapter) shouldBe false // lists the (empty) folder into the cache
        val dir = File(temp, "downloads/$source/Manga").apply { mkdirs() }
        val bytes = Random(2).nextBytes(8192)
        File(dir, "Chapter 1.cbz").writeBytes(bytes) // a synced copy, written outside the manager

        manager.enqueue(manga, listOf(chapter))
        withTimeout(30_000) { manager.awaitIdle() }

        File(dir, "Chapter 1.cbz").readBytes() shouldBe bytes
        dir.list()!!.toList() shouldBe listOf("Chapter 1.cbz")
        manager.isDownloaded(manga, chapter) shouldBe true
        manager.queue.value.shouldBeEmpty()
    }

    @Test
    fun `a chapter folder copied in after it was listed keeps its files when the same chapter downloads`() = runBlocking<Unit> {
        val manager = manager(asCbz = false)
        val chapter = chapter(1, pages = 3)
        manager.isDownloaded(manga, chapter) shouldBe false
        val dir = File(temp, "downloads/$source/Manga").apply { mkdirs() }
        val bytes = Random(3).nextBytes(8192)
        File(dir, "Chapter 1").mkdirs()
        File(dir, "Chapter 1/001.png").writeBytes(bytes)

        manager.enqueue(manga, listOf(chapter))
        withTimeout(30_000) { manager.awaitIdle() }

        File(dir, "Chapter 1").list()!!.toList() shouldBe listOf("001.png")
        File(dir, "Chapter 1/001.png").readBytes() shouldBe bytes
        manager.isDownloaded(manga, chapter) shouldBe true
        manager.queue.value.shouldBeEmpty()
    }

    /**
     * Enqueues [chapter] and holds the server response on the ([completedPages] + 1)-th page
     * request until [block] finishes, so [block] runs deterministically mid-chapter while
     * [Download.State.DOWNLOADING] is active.
     */
    private suspend fun withMidChapterGate(
        manager: DownloadManager,
        chapter: Chapter,
        completedPages: Int = 2,
        block: suspend (Download) -> Unit,
    ): Download {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger(0)
        pageGate = {
            if (requests.incrementAndGet() == completedPages + 1) {
                reached.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        return try {
            manager.enqueue(manga, listOf(chapter))
            val download = manager.queue.value.single()
            withTimeout(10_000) { download.state.first { it == Download.State.DOWNLOADING } }
            withTimeout(10_000) { download.progress.first { it.first >= completedPages } }
            reached.await(10, TimeUnit.SECONDS) shouldBe true
            block(download)
            download
        } finally {
            pageGate = null
            release.countDown()
        }
    }

    @Test
    fun `pausing and starting again keeps the queue running until it is done`() = runBlocking<Unit> {
        val manager = manager()
        val chapter = chapter(1, 12)
        val download = withMidChapterGate(manager, chapter) {
            manager.pause()
            manager.running.value shouldBe false
            manager.start()
            manager.running.value shouldBe true
        }
        // The paused run ending must not mark the new one as stopped. The new run starts on the
        // chapter only after the paused run has ended
        withTimeout(10_000) { download.state.first { it != Download.State.QUEUED } }
        manager.running.value shouldBe true

        withTimeout(30_000) { manager.awaitIdle() }
        manager.queue.value.shouldBeEmpty()
        manager.isDownloaded(manga, chapter) shouldBe true
    }

    @Test
    fun `chapters queued while the queue drains are downloaded`() = runBlocking<Unit> {
        val manager = manager()
        val chapters = (1L..40L).map { chapter(it, 1) }
        chapters.forEach { chapter ->
            manager.enqueue(manga, listOf(chapter))
            // Queue the next one around the moment the previous run finds nothing left
            delay(chapter.id % 4)
        }
        // A chapter left queued with nothing running is the stuck state this guards against: the
        // queue would never empty
        val drained = withTimeoutOrNull(60_000) { manager.queue.first { it.isEmpty() } }
        if (drained == null) {
            manager.running.value shouldBe false
            manager.queue.value.filter { it.state.value == Download.State.QUEUED }.shouldBeEmpty()
            error("The queue did not drain: ${manager.queue.value.map { it.state.value }}")
        }
        manager.downloadedIds(manga, chapters) shouldBe chapters.map { it.id }.toSet()
    }

    @Test
    fun `moving a finished download to the top does not put it back`() = runBlocking<Unit> {
        val manager = manager()
        manager.enqueue(manga, listOf(chapter(1, 1)))
        val download = manager.queue.value.single()
        withTimeout(30_000) { manager.awaitIdle() }
        manager.queue.value.shouldBeEmpty()

        manager.moveToTop(download)
        manager.queue.value.shouldBeEmpty()
    }

    @Test
    fun `background saves are done when awaited and keep the latest queue`() = runBlocking<Unit> {
        val manager = manager()
        val chapters = (1L..3L).map { chapter(it, 1) }
        manager.enqueue(manga, chapters, start = false)
        val downloads = manager.queue.value
        // Saves run on IO threads and may take the lock out of order; the last one written must
        // still be the latest queue
        repeat(30) { round ->
            repeat(50) { manager.moveToTop(downloads[(it + round) % downloads.size]) }
            manager.awaitBackgroundWork()

            temp.listFiles { file -> file.name.endsWith(".tmp") }!!.toList().shouldBeEmpty()
            val saved = File(temp, "downloads-queue.json").readText()
            saved.substringAfter("chapterId\":").substringBefore('}').trim() shouldBe
                manager.queue.value.first().chapter.id.toString()
        }
    }

    @Test
    fun `a saved queue that does not parse is kept aside with a warning`() = runBlocking<Unit> {
        val garbage = "[{\"mangaId\":1,\"chap".toByteArray()
        File(temp, "downloads-queue.json").writeBytes(garbage)
        val posted = mutableListOf<Notifications.Message>()
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { Notifications.flow.toList(posted) }
        yield()

        val manager = manager()
        manager.restore(start = false)
        manager.enqueue(manga, listOf(chapter(1, 1)), start = false) // a save after the restore
        manager.awaitBackgroundWork()
        collector.cancelAndJoin()

        val kept = temp.listFiles()!!.filter { it.name.startsWith("downloads-queue") && it.name.contains("unreadable") }
        kept.single().readBytes() shouldBe garbage
        posted.filter { it.kind == Notifications.Kind.WARNING }.shouldHaveSize(1)
    }

    @Test
    fun `deleting a chapter while it downloads leaves nothing behind`() = runBlocking<Unit> {
        val manager = manager()
        val chapter = chapter(1, 6)
        withMidChapterGate(manager, chapter) {
            manager.delete(manga, listOf(chapter))
        }
        withTimeout(10_000) { manager.awaitIdle() }
        // The clean-up of the cancelled chapter's files
        withTimeout(10_000) { manager.awaitBackgroundWork() }
        manager.queue.value.shouldBeEmpty()
        manager.invalidateCache()
        manager.isDownloaded(manga, chapter) shouldBe false
        File(temp, "downloads").walkTopDown().filter { it.isFile }.toList().shouldBeEmpty()
    }

    @Test
    fun `retrying a failed chapter while others download picks it up`() = runBlocking<Unit> {
        val manager = manager()
        val good = chapter(1, 1)
        manager.enqueue(manga, listOf(good), start = false)
        val download = manager.queue.value.single()
        download.state.value = Download.State.ERROR
        download.error = "Earlier failure"
        manager.start()
        withTimeout(30_000) { manager.awaitIdle() }
        manager.queue.value.shouldBeEmpty()
        manager.isDownloaded(manga, good) shouldBe true
    }
}

/** Android's download queue actions (sort, move, cancel a series) and SY's download clean-up. */
class DownloadQueueOrderTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource(override val name: String, override val id: Long) : HttpSource() {
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    private val sourceA = FakeSource("A", 1)
    private val sourceB = FakeSource("B", 2)

    // A real Manga reads its custom title through Injekt, which these tests do not start
    private fun manga(id: Long, source: Long, title: String, favorite: Boolean = true) = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.source } returns source
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } returns favorite
    }

    private fun chapter(id: Long, mangaId: Long, number: Double, uploaded: Long = 0, read: Boolean = false) =
        Chapter.create().copy(
            id = id,
            mangaId = mangaId,
            url = "/c$id",
            name = "Chapter $id",
            chapterNumber = number,
            dateUpload = uploaded,
            read = read,
            sourceOrder = 1000 - id,
        )

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun waitForSaves() = runBlocking {
        withTimeout(10_000) { managers.forEach { it.awaitBackgroundWork() } }
    }

    private fun manager(): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            listOf(sourceA, sourceB).forEach { s ->
                every { get(s.id) } returns s
                every { getOrStub(s.id) } returns s
            }
            every { getOnlineSources() } returns listOf(sourceA, sourceB)
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(-1)
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(false)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val password = CbzPassword(FileSecretStore(File(temp, "secrets")), securityPreferences)
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
    }

    private fun DownloadManager.ids() = queue.value.map { it.chapter.id }

    @Test
    fun `moves downloads and series to the top and bottom, and cancels a series`() {
        val manager = manager()
        val one = manga(1, sourceA.id, "One")
        val two = manga(2, sourceA.id, "Two")
        manager.enqueue(one, listOf(chapter(1, 1, 1.0), chapter(2, 1, 2.0)), start = false)
        manager.enqueue(two, listOf(chapter(3, 2, 1.0), chapter(4, 2, 2.0)), start = false)
        manager.ids() shouldBe listOf(1L, 2L, 3L, 4L)

        manager.moveToBottom(manager.queue.value.first())
        manager.ids() shouldBe listOf(2L, 3L, 4L, 1L)

        manager.moveSeries(2, toTop = true)
        manager.ids() shouldBe listOf(3L, 4L, 2L, 1L)

        manager.moveSeries(2, toTop = false)
        manager.ids() shouldBe listOf(2L, 1L, 3L, 4L)

        manager.cancelSeries(1)
        manager.ids() shouldBe listOf(3L, 4L)
    }

    @Test
    fun `sorts within each source, keeping the sources in order`() {
        val manager = manager()
        val a = manga(1, sourceA.id, "One")
        val b = manga(2, sourceB.id, "Two")
        manager.enqueue(b, listOf(chapter(10, 2, 1.0, uploaded = 300), chapter(11, 2, 2.0, uploaded = 100)), start = false)
        manager.enqueue(a, listOf(chapter(1, 1, 3.0, uploaded = 200), chapter(2, 1, 1.0, uploaded = 400)), start = false)
        manager.ids() shouldBe listOf(10L, 11L, 1L, 2L)

        manager.sortQueue(descending = true) { it.chapter.dateUpload }
        manager.ids() shouldBe listOf(10L, 11L, 2L, 1L)

        manager.sortQueue(descending = false) { it.chapter.dateUpload }
        manager.ids() shouldBe listOf(11L, 10L, 1L, 2L)

        manager.sortQueue(descending = false) { it.chapter.chapterNumber }
        manager.ids() shouldBe listOf(10L, 11L, 2L, 1L)
    }

    @Test
    fun `clean up deletes unfinished, read and non-library downloads, and keeps unknown files and orphaned folders`() = runBlocking<Unit> {
        val manager = manager()
        val downloads = File(temp, "downloads/A (EN)")
        fun make(path: String) = File(downloads, path).apply {
            parentFile.mkdirs()
            if (path.endsWith("/")) mkdirs() else writeText("x")
        }
        val kept = manga(1, sourceA.id, "Kept")
        val left = manga(2, sourceA.id, "Left library", favorite = false)
        val allRead = manga(3, sourceA.id, "All read")
        val chapters = mapOf(
            1L to listOf(chapter(1, 1, 1.0, read = true), chapter(2, 1, 2.0)),
            2L to listOf(chapter(3, 2, 1.0)),
            3L to listOf(chapter(4, 3, 1.0, read = true)),
        )
        make("Kept/Chapter 1.cbz")
        make("Kept/Chapter 2.cbz")
        make("Kept/Chapter 9.cbz") // no chapter the desktop knows: maybe the Android app's, kept
        make("Kept/Chapter 2_tmp/") // an unfinished download
        make("Kept/cover.jpg")
        make("Left library/Chapter 3.cbz")
        make("All read/Chapter 4.cbz")
        make("Nobody/Chapter 1.cbz")

        manager.isDownloaded(kept, chapters.getValue(1L)[0]) shouldBe true

        val cleared = manager.cleanup(
            removeRead = true,
            removeNonFavorite = true,
            allManga = { listOf(kept, left, allRead) },
            chaptersOf = { chapters.getValue(it) },
        )

        // Nobody, a folder of no entry, is only deleted when the user ticks it in the preview
        downloads.list()!!.toSet() shouldBe setOf("Kept", "Nobody")
        File(downloads, "Kept").list()!!.toSet() shouldBe setOf("Chapter 2.cbz", "Chapter 9.cbz", "cover.jpg")
        // Left library: chapter + folder; All read: chapter + folder; Kept: the unfinished one and Chapter 1
        cleared shouldBe 2 + 2 + 2
        manager.isDownloaded(kept, chapters.getValue(1L)[0]) shouldBe false
        manager.isDownloaded(kept, chapters.getValue(1L)[1]) shouldBe true
    }

    @Test
    fun `clean up keeps read chapters unless asked, and leaves entries in the queue alone`() = runBlocking<Unit> {
        val manager = manager()
        val downloads = File(temp, "downloads/A (EN)")
        val read = manga(1, sourceA.id, "Read")
        val queued = manga(2, sourceA.id, "Queued")
        File(downloads, "Read").mkdirs()
        File(downloads, "Read/Chapter 1.cbz").writeText("x")
        File(downloads, "Queued/Chapter 3_tmp").mkdirs()
        val chapters = mapOf(1L to listOf(chapter(1, 1, 1.0, read = true)), 2L to listOf(chapter(3, 2, 1.0)))
        manager.enqueue(queued, chapters.getValue(2L), start = false)

        val cleared = manager.cleanup(
            removeRead = false,
            removeNonFavorite = false,
            allManga = { listOf(read, queued) },
            chaptersOf = { chapters.getValue(it) },
        )

        cleared shouldBe 0
        File(downloads, "Read/Chapter 1.cbz").exists() shouldBe true
        File(downloads, "Queued/Chapter 3_tmp").exists() shouldBe true
    }
}

/**
 * An entry whose extension is not installed (a library restored before its extensions) cannot
 * download. That is an expected state: the chapter fails with a message, and nothing is logged as
 * an error, which the button crawl and the crash reports treat as a bug.
 */
class DownloadSourceMissingTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private var installedLogger = false

    @AfterEach
    fun tearDown() {
        root.detachAppender(appender)
        if (installedLogger) LogcatLogger.uninstall()
        // Queue saves run in the background; let them finish before the temp folder goes
        kits.forEach { it.awaitBackground() }
    }

    private fun installLogger() {
        // As the app does at start (ZoukanRuntime): logcat goes to SLF4J
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(Slf4jLogcatLogger(LogPriority.INFO))
            installedLogger = true
        }
        root.addAppender(appender)
    }

    @Test
    fun `an entry whose source is not installed queues nothing, as on Android`() = runBlocking<Unit> {
        installLogger()
        val kit = DownloadKit(temp).also { kits += it }

        kit.manager.enqueue(kit.manga(1, "Alpha", source = 42), listOf(kit.chapter(1, 1)))
        withTimeout(30_000) { kit.manager.awaitIdle() }

        kit.manager.queue.value.shouldBeEmpty()
        appender.list.filter { it.level == Level.ERROR }.shouldBeEmpty()
    }

    @Test
    fun `a source uninstalled after its chapters were queued fails them without logging an error`() = runBlocking<Unit> {
        installLogger()
        val kit = DownloadKit(temp, sources = listOf(DownloadKit.TestSource("Gone", 42))).also { kits += it }
        val manga = kit.manga(1, "Alpha", source = 42)

        kit.manager.enqueue(manga, listOf(kit.chapter(1, 1)), start = false)
        kit.byId.remove(42)
        kit.manager.start()
        withTimeout(30_000) { kit.manager.awaitIdle() }

        val download = kit.manager.queue.value.single()
        download.state.value shouldBe Download.State.ERROR
        download.error!! shouldContain "Alpha"
        val logs = appender.list.filter { "Alpha" in it.formattedMessage }
        logs.shouldNotBeEmpty()
        logs.filter { it.level == Level.ERROR }.shouldBeEmpty()
    }

    @Test
    fun `a restored queue drops the chapters whose source can no longer download, as on Android`() = runBlocking<Unit> {
        installLogger()
        val kit = DownloadKit(temp).also { kits += it }
        val kept = kit.manga(1, "Alpha", source = 1)
        val gone = kit.manga(2, "Bravo", source = 42)
        val chapters = mapOf(1L to kit.chapter(1, 1), 2L to kit.chapter(2, 2))
        File(temp, "downloads-queue.json").writeText("""[{"mangaId":1,"chapterId":1},{"mangaId":2,"chapterId":2}]""")
        val manager = DownloadManager(
            ZoukanDirs(temp),
            kit.paths,
            kit.sourceManager,
            kit.downloadPreferences,
            mockk(),
            mockk<GetManga> { coEvery { await(any<Long>()) } answers { listOf(kept, gone).firstOrNull { it.id == firstArg<Long>() } } },
            mockk<GetChapter> { coEvery { await(any<Long>()) } answers { chapters[firstArg<Long>()] } },
            mockk(relaxed = true),
            coOwners = { emptyList() },
        )

        manager.restore(start = false)

        manager.queue.value.map { it.manga.title } shouldBe listOf("Alpha")
        appender.list.filter { it.level == Level.ERROR }.shouldBeEmpty()
        manager.awaitBackgroundWork()
    }
}

class DownloadAheadTest {

    private fun chapter(id: Long, read: Boolean = false, manga: Long = 1) =
        Chapter.create().copy(id = id, mangaId = manga, chapterNumber = id.toDouble(), read = read)

    private val mangas = mapOf(1L to Manga.create().copy(id = 1), 2L to Manga.create().copy(id = 2))
    private val readerManga = mangas.getValue(1)
    private val mangaOf: suspend (Chapter) -> Manga? = { mangas[it.mangaId] }

    private fun queue(
        navigable: List<Chapter>,
        nextId: Long,
        amount: Int,
        downloaded: Set<Long>,
    ) = runBlocking {
        DownloadAhead { _, c -> c.id in downloaded }
            .chaptersToQueue(readerManga, navigable.first { it.id == nextId }, navigable, amount, mangaOf)
    }

    @Test
    fun `nothing is queued when the next chapter is not downloaded`() {
        queue((1L..5L).map { chapter(it) }, nextId = 2, amount = 2, downloaded = emptySet()).shouldBeEmpty()
    }

    @Test
    fun `only unread chapters are queued`() {
        val navigable = listOf(chapter(1), chapter(2), chapter(3, read = true), chapter(4), chapter(5))
        val result = queue(navigable, nextId = 2, amount = 2, downloaded = setOf(2))
        result.single().second.map { it.id } shouldContainExactly listOf(2L, 4L)
    }

    @Test
    fun `chapters are grouped by entry for a merged entry`() {
        val navigable = listOf(chapter(1, manga = 1), chapter(2, manga = 1), chapter(3, manga = 2), chapter(4, manga = 2))
        val result = queue(navigable, nextId = 2, amount = 3, downloaded = setOf(2))
        result.map { it.first.id to it.second.map { c -> c.id } } shouldContainExactly
            listOf(1L to listOf(2L), 2L to listOf(3L, 4L))
    }
}
