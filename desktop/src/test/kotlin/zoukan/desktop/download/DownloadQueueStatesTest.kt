package zoukan.desktop.download

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** The download queue's states: pausing, resuming, queueing while it drains, reordering. */
class DownloadQueueStatesTest {

    @TempDir
    lateinit var temp: File

    private lateinit var server: HttpServer
    private val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    /** How long the server takes per image */
    @Volatile
    private var pageDelayMs = 0L

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/") { exchange ->
                if (pageDelayMs > 0) Thread.sleep(pageDelayMs)
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            start()
        }
    }

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun stopServer() {
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

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

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

    private fun manager(): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(true)
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
    fun `pausing and starting again keeps the queue running until it is done`() = runBlocking<Unit> {
        pageDelayMs = 100
        val manager = manager()
        val chapter = chapter(1, 12)
        manager.enqueue(manga, listOf(chapter))
        withTimeout(10_000) { manager.queue.value.single().state.first { it == Download.State.DOWNLOADING } }
        delay(250)

        manager.pause()
        manager.running.value shouldBe false
        manager.start()
        manager.running.value shouldBe true
        // The paused run ending must not mark the new one as stopped
        delay(500)
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
        withTimeout(60_000) {
            while (manager.queue.value.isNotEmpty()) {
                // A chapter left queued with nothing running is the stuck state this guards against
                if (!manager.running.value) {
                    delay(200)
                    manager.queue.value.filter { it.state.value == Download.State.QUEUED }.shouldBeEmpty()
                }
                delay(20)
            }
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
    fun `deleting a chapter while it downloads leaves nothing behind`() = runBlocking<Unit> {
        pageDelayMs = 50
        val manager = manager()
        val chapter = chapter(1, 6)
        manager.enqueue(manga, listOf(chapter))
        withTimeout(10_000) { manager.queue.value.single().state.first { it == Download.State.DOWNLOADING } }
        delay(120)

        manager.delete(manga, listOf(chapter))
        withTimeout(10_000) { manager.awaitIdle() }
        delay(300)
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
