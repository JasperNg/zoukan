package zoukan.desktop.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

/** A download manager on a temporary folder, with real preferences and sources, for the download tests. */
internal class DownloadKit(
    val temp: File,
    /** The sources by id; [installed] are the "online sources" a clean-up walks. */
    sources: List<HttpSource> = listOf(TestSource("A", 1)),
    installed: List<HttpSource> = sources,
    available: () -> Boolean = { true },
    /** Whether names ignore case (see [DownloadIndex.ignoreCase]); null checks the disk. */
    ignoreCase: Boolean? = null,
    /** The other entries in an entry's folder, with their chapters (see [DownloadManager]); null: none (no database here). */
    coOwners: (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = null,
) {
    class TestSource(override val name: String, override val id: Long, override val lang: String = "en") : HttpSource() {
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
    val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
    val downloads = File(temp, "downloads")

    val storage = mockk<ZoukanStorage> {
        every { this@mockk.downloads } returns this@DownloadKit.downloads
        every { isAvailable } answers { available() }
        every { root } returns temp
    }

    /** The installed sources by id; a test removes one to uninstall its extension. */
    val byId = sources.associateBy { it.id }.toMutableMap()

    val sourceManager: SourceManager = object : SourceManager by mockk<SourceManager>() {
        override fun get(sourceKey: Long) = byId[sourceKey]
        override fun getOrStub(sourceKey: Long): Source = byId[sourceKey] ?: mockk<Source> { every { this@mockk.toString() } returns "$sourceKey" }
        override fun getOnlineSources() = installed
    }

    val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)

    val manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true), coOwners ?: { emptyList() })
        .also { it.index.ignoreCase = ignoreCase }

    // A real favorite Manga reads its custom title through Injekt, which these tests do not start
    fun manga(id: Long, title: String, source: Long = 1, favorite: Boolean = true): Manga = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.source } returns source
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } returns favorite
    }

    fun chapter(id: Long, mangaId: Long, name: String = "Chapter $id", read: Boolean = false, scanlator: String? = null): Chapter =
        Chapter.create().copy(id = id, mangaId = mangaId, url = "/c$id", name = name, read = read, scanlator = scanlator, sourceOrder = 1000 - id)

    /** Writes a file (bytes) or, for a path ending in "/", a folder, under the downloads folder. */
    fun write(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File = File(downloads, path).also {
        if (path.endsWith("/")) {
            it.mkdirs()
        } else {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }
    }

    /** A chapter folder with one page in it. */
    fun chapterFolder(path: String): File = write("$path/001.png").parentFile

    fun awaitBackground() = runBlocking { withTimeout(10_000) { manager.awaitBackgroundWork() } }
}
