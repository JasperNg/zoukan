package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.lingala.zip4j.ZipFile
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.e2e.FixtureCatalog
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.testing.UserMessages
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.desktop.testing.fakes.MemorySecretStore
import java.io.File

/**
 * The download queue under injected faults: the image server failing (an error status, a connection
 * cut mid-body, no answer, an HTML page instead of an image), a folder that cannot be written, and a
 * locked secret store. For each, three things:
 *
 * 1. the user sees it: the chapter is in the error state with a message, and an error notification
 *    names it;
 * 2. the app keeps running: the queue stops cleanly, and a retry after the fault is healed downloads
 *    the chapter;
 * 3. nothing is lost or half written: no archive under the final name or its part name, no partial
 *    page, and a chapter already on disk keeps its bytes.
 */
class DownloadFaultsTest {

    @TempDir
    lateinit var temp: File

    private val server = FixtureServer()
    private val messages = UserMessages()
    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun stop() {
        runBlocking {
            withTimeout(10_000) {
                managers.forEach {
                    it.pause()
                    it.awaitBackgroundWork()
                }
            }
        }
        messages.close()
        server.close()
    }

    private val pages = FixtureCatalog.pages("png").map { it.file }

    private inner class FixtureSource : HttpSource() {
        override val name = "Faults"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl get() = server.baseUrl
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            pages.mapIndexed { i, file -> Page(i, imageUrl = "$baseUrl/images/$file") }
    }

    private val source by lazy { FixtureSource() }

    private val manga by lazy {
        mockk<Manga>(relaxed = true) {
            every { id } returns 1L
            every { this@mockk.source } returns this@DownloadFaultsTest.source.id
            every { title } returns "Manga"
            every { ogTitle } returns "Manga"
            every { genre } returns null
            every { description } returns null
            every { author } returns null
            every { artist } returns null
        }
    }

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = 1, url = "/c$id", name = "Chapter $id", sourceOrder = 1000 - id)

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    private val sourceDir get() = File(temp, "downloads/$source")
    private val mangaDir get() = File(sourceDir, "Manga")

    private fun manager(secrets: SecretStore = MemorySecretStore(), protect: Boolean = false): DownloadManager {
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
            every { saveChaptersAsCBZ() } returns pref(true)
            every { parallelSourceLimit() } returns pref(1)
            every { parallelPageLimit() } returns pref(1)
            every { splitTallImages() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val securityPreferences = mockk<SecurityPreferences> {
            every { passwordProtectDownloads() } returns pref(protect)
            every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, CbzPassword(secrets, securityPreferences), mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
    }

    /** A chapter already downloaded, with its bytes. */
    private fun existingChapter(): Pair<File, ByteArray> {
        val bytes = kotlin.random.Random(1).nextBytes(4096)
        return File(mangaDir.apply { mkdirs() }, "Chapter 1.cbz").apply { writeBytes(bytes) } to bytes
    }

    /** Enqueues [chapter] and waits for it to fail; returns the download, still queued in the error state. */
    private suspend fun DownloadManager.failing(chapter: Chapter): Download {
        enqueue(manga, listOf(chapter))
        val download = queue.value.single { it.chapter.id == chapter.id }
        withTimeout(30_000) { download.state.first { it == Download.State.ERROR } }
        withTimeout(10_000) { awaitIdle() }
        return download
    }

    /** What the user sees for a failed [download]: its error in the queue and an error notification naming it. */
    private fun shownToUser(download: Download) {
        download.error.shouldNotBeBlank()
        messages.awaitError { it.text.contains(download.chapter.name) }.text shouldContain download.error!!
    }

    /** No archive and no part of one under the chapter's names, and no partial page in a resume folder. */
    private fun nothingHalfWritten(name: String) {
        File(mangaDir, "$name.cbz").exists() shouldBe false
        File(mangaDir, "$name.cbz${DownloadPaths.TMP_SUFFIX}").exists() shouldBe false
        // The page folder may stay for a resume (as designed), but only with whole pages
        File(mangaDir, name + DownloadPaths.TMP_SUFFIX).listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.shouldBeEmpty()
    }

    /** A retry after the fault is healed downloads [chapter] whole. */
    private suspend fun DownloadManager.retrySucceeds(chapter: Chapter) {
        start()
        withTimeout(30_000) { awaitIdle() }
        queue.value.shouldBeEmpty()
        isDownloaded(manga, chapter) shouldBe true
        ZipFile(File(mangaDir, "${chapter.name}.cbz")).use { zip ->
            zip.fileHeaders.map { it.fileName }.filter { it != ComicInfoXml.FILE }.size shouldBe pages.size
        }
        File(mangaDir, chapter.name + DownloadPaths.TMP_SUFFIX).exists() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serverFaults")
    fun `an image server fault fails the chapter with an error, keeps nothing half written, and a retry works`(
        name: String,
        failure: FixtureFailure,
    ) = runBlocking<Unit> {
        val manager = manager()
        val (existing, existingBytes) = existingChapter()
        // The second page fails, so the first is already in the page folder
        server.fail("/images/${pages[1]}", failure)
        val chapter = chapter(2)

        val download = manager.failing(chapter)

        shownToUser(download)
        nothingHalfWritten(chapter.name)
        existing.readBytes() shouldBe existingBytes

        server.clearFailures()
        manager.retrySucceeds(chapter)
        existing.readBytes() shouldBe existingBytes
    }

    @Test
    fun `a manga folder the app cannot write fails the chapter with an error, and the existing chapter keeps its bytes`() = runBlocking<Unit> {
        val manager = manager()
        val (existing, existingBytes) = existingChapter()
        val readOnly = FileFaults.readOnly(mangaDir)
        assumeTrue(readOnly != null, "This OS or user can write in a read-only folder")
        val chapter = chapter(2)
        val download = readOnly!!.use { manager.failing(chapter) }

        shownToUser(download)
        nothingHalfWritten(chapter.name)
        existing.readBytes() shouldBe existingBytes
        mangaDir.list()!!.toList() shouldBe listOf("Chapter 1.cbz")

        manager.retrySucceeds(chapter)
        existing.readBytes() shouldBe existingBytes
    }

    @Test
    fun `a file where the source folder should be fails the chapter with an error, and deleting it lets a retry work`() = runBlocking<Unit> {
        val manager = manager()
        val blocker = FileFaults.fileInTheWay(sourceDir)
        val blockerText = blocker.readText()
        val chapter = chapter(2)

        val download = manager.failing(chapter)

        shownToUser(download)
        // The file in the way is not the app's to change
        blocker.readText() shouldBe blockerText
        File(temp, "downloads").list()!!.toList() shouldBe listOf(sourceDir.name)

        blocker.delete() shouldBe true
        manager.retrySucceeds(chapter)
    }

    @Test
    fun `a locked secret store fails a protected download with an error and writes nothing, and a retry after unlocking works`() = runBlocking<Unit> {
        val store = FailingSecretStore().apply { backing.values[CbzPassword.KEY] = "download password" }
        store.failAll(FailingSecretStore.Failure.LOCKED)
        val manager = manager(secrets = store, protect = true)
        val chapter = chapter(2)

        val download = manager.failing(chapter)

        shownToUser(download)
        download.error!! shouldContain "secret store"
        File(temp, "downloads").walkTopDown().filter { it.isFile }.toList().shouldBeEmpty()

        store.heal()
        manager.retrySucceeds(chapter)
        // Encrypted, as protection asks: the entries need the password
        ZipFile(File(mangaDir, "${chapter.name}.cbz")).use { it.isEncrypted shouldBe true }
    }

    companion object {
        @JvmStatic
        fun serverFaults() = listOf(
            arrayOf("HTTP 503", FixtureFailure.Status(503)),
            arrayOf("connection cut mid-body", FixtureFailure.Truncate(keepBytes = 64)),
            arrayOf("connection closed without an answer", FixtureFailure.Drop),
            arrayOf("an HTML page with status 200", FixtureFailure.Answer(200, "text/html", "<html><body>Rate limited</body></html>".toByteArray())),
        )
    }
}
