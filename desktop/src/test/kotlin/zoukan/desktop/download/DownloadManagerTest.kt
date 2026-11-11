package zoukan.desktop.download

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.lingala.zip4j.ZipFile
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
import zoukan.desktop.local.LocalFormat
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import javax.imageio.ImageIO

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

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

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
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
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
    }
}
