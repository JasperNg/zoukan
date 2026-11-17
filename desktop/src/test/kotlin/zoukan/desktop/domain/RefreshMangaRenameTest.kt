package zoukan.desktop.domain

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import kotlin.random.Random

/** A title the source changes must not strand the entry's downloads under the old folder name. */
class RefreshMangaRenameTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource : HttpSource() {
        override val name = "Fake"
        override val id = 7L
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    private val fake = FakeSource()

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    // A real Manga reads its custom title through Injekt, which this test does not start
    private fun manga(title: String) = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { source } returns 7L
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { favorite } returns true
    }

    private val old = manga("Old title")
    private val renamed = manga("New title")
    private val chapter = Chapter.create().copy(id = 1, mangaId = 1, url = "/c1", name = "Chapter 1")

    private val managers = mutableListOf<DownloadManager>()

    @AfterEach
    fun waitForSaves() = runBlocking {
        withTimeout(10_000) { managers.forEach { it.awaitBackgroundWork() } }
    }

    private lateinit var paths: DownloadPaths
    private lateinit var manager: DownloadManager

    private fun setUp() {
        val sourceManager = mockk<SourceManager> {
            every { get(7L) } returns fake
            every { getOrStub(7L) } returns fake
            every { getOnlineSources() } returns listOf(fake)
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
        paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, password, mockk(), mockk(), mockk(relaxed = true))
            .also { managers += it }
        this.sourceManager = sourceManager
        this.libraryPreferences = libraryPreferences
    }

    private lateinit var sourceManager: SourceManager
    private lateinit var libraryPreferences: LibraryPreferences

    private val updates = slot<MangaUpdate>()

    private fun refreshManga(): RefreshManga {
        val chapterRepository = mockk<ChapterRepository> {
            coEvery { getChapterByMangaId(1) } returns listOf(chapter)
        }
        val mangaRepository = mockk<MangaRepository> {
            coEvery { update(capture(updates)) } returns true
            coEvery { getMangaById(1) } returns renamed
        }
        every { libraryPreferences.updateMangaTitles() } returns pref(true)
        return RefreshManga(sourceManager, chapterRepository, mangaRepository, mockk(), libraryPreferences, manager)
    }

    private fun sourceWithNewTitle() = mockk<Source>(relaxed = true) {
        coEvery { getMangaUpdate(any(), any(), any(), any()) } returns
            SMangaUpdate(SManga.create().apply { title = "New title" }, emptyList())
    }

    private fun write(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).also {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }

    @Test
    fun `a title the source changed keeps the entry's downloads, and clean up deletes none of them`() = runBlocking<Unit> {
        setUp()
        val bytes = Random(1).nextBytes(4096)
        val oldFile = write(paths.mangaDir(old), "Chapter 1.cbz", bytes)
        oldFile.exists() shouldBe true
        val refresh = refreshManga()

        refresh(old, fetchDetails = true, fetchChapters = false, source = sourceWithNewTitle())

        updates.captured.title shouldBe "New title"
        manager.isDownloaded(renamed, chapter) shouldBe true
        manager.cleanup(
            removeRead = false,
            removeNonFavorite = false,
            allManga = { listOf(renamed) },
            chaptersOf = { listOf(chapter) },
        ) shouldBe 0
        val file = paths.findChapter(renamed, chapter).shouldNotBeNull()
        file.readBytes() shouldBe bytes
    }

    @Test
    fun `a title whose folder name is taken stays unchanged and no download is touched`() = runBlocking<Unit> {
        setUp()
        val bytes = Random(1).nextBytes(4096)
        val otherBytes = Random(2).nextBytes(2048)
        val oldFile = write(paths.mangaDir(old), "Chapter 1.cbz", bytes)
        val otherFile = write(paths.mangaDir(renamed), "Other.cbz", otherBytes)
        val refresh = refreshManga()

        refresh(old, fetchDetails = true, fetchChapters = false, source = sourceWithNewTitle())

        updates.captured.title.shouldBeNull()
        oldFile.readBytes() shouldBe bytes
        otherFile.readBytes() shouldBe otherBytes
        manager.isDownloaded(old, chapter) shouldBe true
    }

    @Test
    fun `clean up keeps a folder whose chapters belong to an entry whose own folder has another name`() = runBlocking<Unit> {
        setUp()
        val bytes = Random(3).nextBytes(4096)
        val file = write(paths.mangaDir(old), "Chapter 1.cbz", bytes)

        manager.cleanup(
            removeRead = false,
            removeNonFavorite = false,
            allManga = { listOf(renamed) },
            chaptersOf = { listOf(chapter) },
        ) shouldBe 0

        file.readBytes() shouldBe bytes
    }

    @Test
    fun `clean up keeps a folder under the old title while the entry's folder under the new title exists`() = runBlocking<Unit> {
        setUp()
        val bytes = Random(4).nextBytes(4096)
        val newBytes = Random(5).nextBytes(2048)
        val chapter2 = Chapter.create().copy(id = 2, mangaId = 1, url = "/c2", name = "Chapter 2")
        // A download queued with the entry as read before the refresh lands in the old folder
        val stranded = write(paths.mangaDir(old), "Chapter 2.cbz", bytes)
        val moved = write(paths.mangaDir(renamed), "Chapter 1.cbz", newBytes)

        manager.cleanup(
            removeRead = false,
            removeNonFavorite = false,
            allManga = { listOf(renamed) },
            chaptersOf = { listOf(chapter, chapter2) },
        ) shouldBe 0

        stranded.readBytes() shouldBe bytes
        moved.readBytes() shouldBe newBytes
    }

    @Test
    fun `the chapter sync after a title change works in the moved folder`() = runBlocking<Unit> {
        setUp()
        write(paths.mangaDir(old), "Chapter 1.cbz", Random(6).nextBytes(1024))
        val synced = slot<Manga>()
        val sync = mockk<SyncChapters> { coEvery { await(any(), capture(synced), any()) } returns emptyList() }
        val chapterRepository = mockk<ChapterRepository> { coEvery { getChapterByMangaId(1) } returns listOf(chapter) }
        val mangaRepository = mockk<MangaRepository> {
            coEvery { update(capture(updates)) } returns true
            coEvery { getMangaById(1) } returns renamed
        }
        every { libraryPreferences.updateMangaTitles() } returns pref(true)
        val refresh = RefreshManga(sourceManager, chapterRepository, mangaRepository, sync, libraryPreferences, manager)

        refresh(old, fetchDetails = true, fetchChapters = true, source = sourceWithNewTitle())

        // SyncChapters renames chapter downloads in paths.mangaDir(manga): it must be the new folder
        paths.mangaDir(synced.captured) shouldBe paths.mangaDir(renamed)
        paths.mangaDir(renamed).isDirectory shouldBe true
    }
}
