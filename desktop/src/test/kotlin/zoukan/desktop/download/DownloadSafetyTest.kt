package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException

/** Downloads fail closed: no plain CBZ when the password can't be read, no deletions without categories, no duplicates. */
class DownloadSafetyTest {

    @TempDir
    lateinit var temp: File

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
    }

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    private class MapStore(private val failing: Boolean) : SecretStore {
        val values = HashMap<String, String>()
        var reads = 0
        override val name = "Test store"
        override fun get(key: String): String? {
            reads++
            return if (failing) throw IOException("The Keychain is locked") else values[key]
        }
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
    }

    private fun security(protect: Boolean) = mockk<SecurityPreferences> {
        every { passwordProtectDownloads() } returns pref(protect)
        every { encryptionType() } returns pref(SecurityPreferences.EncryptionType.AES_256)
    }

    /** A source no download may reach: the password check comes first. */
    private class UnreachableSource : HttpSource() {
        override val name = "Fake"
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
        override suspend fun getPageList(chapter: SChapter): List<Page> = error("The download went ahead")
    }

    private val source = UnreachableSource()

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { this@mockk.source } returns this@DownloadSafetyTest.source.id
        every { title } returns "Manga"
        every { ogTitle } returns "Manga"
    }

    private fun chapter(id: Long) =
        Chapter.create().copy(id = id, mangaId = 1, url = "/c$id", name = "Chapter $id", sourceOrder = 1000 - id, read = true)

    private fun manager(
        password: CbzPassword = CbzPassword(MapStore(failing = false), security(false)),
        getCategories: GetCategories = mockk(relaxed = true),
        excluded: Set<String> = emptySet(),
        asCbz: Boolean = true,
    ): DownloadManager {
        val sourceManager = mockk<SourceManager> {
            every { get(source.id) } returns source
            every { getOrStub(source.id) } returns source
        }
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { saveChaptersAsCBZ() } returns pref(asCbz)
            every { parallelSourceLimit() } returns pref(1)
            every { parallelPageLimit() } returns pref(1)
            every { splitTallImages() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
            every { removeExcludeCategories() } returns pref(excluded)
            every { removeBookmarkedChapters() } returns pref(true)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        return DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), getCategories)
            .also { managers += it }
    }

    @Test
    fun `a secret store error with password protection on fails instead of skipping encryption`() {
        val password = CbzPassword(MapStore(failing = true), security(protect = true))
        shouldThrow<CbzPasswordUnavailableException> { password.encryption() }
        password.comicInfoPadding(null) shouldBe null
        // Readers still just find no password
        password.get() shouldBe null
    }

    @Test
    fun `no encryption when protection is off or no password is set`() {
        CbzPassword(MapStore(failing = true), security(protect = false)).encryption() shouldBe null
        CbzPassword(MapStore(failing = false), security(protect = true)).encryption() shouldBe null
        val store = MapStore(failing = false).apply { values[CbzPassword.KEY] = "pw" }
        CbzPassword(store, security(protect = true)).encryption()!!.password.concatToString() shouldBe "pw"
    }

    @Test
    fun `a download fails with an error and writes nothing when the password can't be read`() = runBlocking<Unit> {
        val manager = manager(password = CbzPassword(MapStore(failing = true), security(protect = true)))
        manager.enqueue(manga, listOf(chapter(1)))
        val download = manager.queue.value.single()
        withTimeout(10_000) { download.state.first { it == Download.State.ERROR } }
        download.error!! shouldContain "secret store"
        File(temp, "downloads").walkTopDown().filter { it.isFile }.toList().shouldBeEmpty()
    }

    @Test
    fun `a folder download never reads the password, and a CBZ download reads it once`() = runBlocking<Unit> {
        // A folder is never encrypted: a locked store must not fail it
        val locked = MapStore(failing = true)
        val folder = manager(password = CbzPassword(locked, security(protect = true)), asCbz = false)
        folder.enqueue(manga, listOf(chapter(1)))
        val folderDownload = folder.queue.value.single()
        withTimeout(10_000) { folderDownload.state.first { it == Download.State.ERROR } }
        folderDownload.error!! shouldContain "went ahead"
        locked.reads shouldBe 0
        folder.pause()
        folder.awaitBackgroundWork()

        // The encryption and the ComicInfo padding share one read
        val store = MapStore(failing = false).apply { values[CbzPassword.KEY] = "pw" }
        val cbz = manager(password = CbzPassword(store, security(protect = true)))
        cbz.enqueue(manga, listOf(chapter(2)))
        val cbzDownload = cbz.queue.value.single()
        withTimeout(10_000) { cbzDownload.state.first { it == Download.State.ERROR } }
        cbzDownload.error!! shouldContain "went ahead"
        store.reads shouldBe 1
    }

    @Test
    fun `automatic deletion deletes nothing when the categories can't be read`() = runBlocking<Unit> {
        val getCategories = mockk<GetCategories> { coEvery { await(any<Long>()) } throws IOException("The database is busy") }
        val manager = manager(getCategories = getCategories, excluded = setOf("5"))
        manager.chaptersToDelete(manga, listOf(chapter(1), chapter(2))).shouldBeEmpty()
    }

    @Test
    fun `enqueueing the same chapters at once queues each only once`() = runBlocking<Unit> {
        val manager = manager()
        val chapters = (1L..20L).map(::chapter)
        (1..16).map {
            async(Dispatchers.Default) { manager.enqueue(manga, chapters + chapters.take(3), start = false) }
        }.awaitAll()
        manager.queue.value.map { it.chapter.id }.sorted() shouldBe (1L..20L).toList()
    }
}
