package zoukan.desktop.download

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

/**
 * How long the library's download checks take on a big downloads folder: 500 entries of 200
 * downloaded chapters each. Runs only with `ZOUKAN_BENCHMARK=1` in the environment.
 */
class DownloadIndexBenchmarkTest {

    @TempDir
    lateinit var temp: File

    private class FakeSource : HttpSource() {
        override val name = "Bench"
        override val id = 1L
        override val lang = "en"
        override val supportsLatest = false
        override val baseUrl = "http://127.0.0.1:9"
        override val client = OkHttpClient()
        override fun headersBuilder() = Headers.Builder()
    }

    @Test
    fun `cold load and warm refresh of 500 entries x 200 chapters`() {
        assumeTrue(System.getenv("ZOUKAN_BENCHMARK") != null, "set ZOUKAN_BENCHMARK=1 to run")
        val entries = 500
        val perEntry = 200
        val source = FakeSource()
        // Real objects, not mocks: a mocked call costs more than the disk work being measured
        val sourceManager = object : SourceManager by mockk<SourceManager>() {
            override fun get(sourceKey: Long) = source
            override fun getOrStub(sourceKey: Long) = source
        }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val mangas = (1..entries).map { id -> Manga.create().copy(id = id.toLong(), source = 1L, ogTitle = "Entry $id") }
        val chapters = mangas.associate { m ->
            m.id to (1..perEntry).map { c ->
                Chapter.create().copy(id = m.id * 1000 + c, mangaId = m.id, url = "/m${m.id}/c$c", name = "Chapter $c")
            }
        }
        mangas.forEach { m ->
            val dir = paths.mangaDir(m).apply { mkdirs() }
            chapters.getValue(m.id).forEach { File(dir, "${it.name}.cbz").writeBytes(byteArrayOf(1)) }
        }

        fun manager() = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))
        fun load(manager: DownloadManager): Long {
            val start = System.nanoTime()
            var total = 0
            mangas.forEach { m ->
                total += manager.downloadCount(m)
                total += manager.downloadedIds(m, chapters.getValue(m.id)).size
            }
            total shouldBe entries * perEntry * 2
            return (System.nanoTime() - start) / 1_000_000
        }

        // The first manager warms the JIT and the disk cache, as an app that ran before
        load(manager())
        val manager = manager()
        val cold = load(manager)
        val warm = (1..3).minOf { load(manager) }
        println("DownloadIndexBenchmark: cold load ${cold}ms, warm refresh ${warm}ms ($entries entries x $perEntry chapters)")
    }
}
