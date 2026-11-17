package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

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
                onEdt += java.awt.EventQueue.isDispatchThread()
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
