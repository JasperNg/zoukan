package zoukan.desktop.download

import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
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
        val storage = ZoukanStorage(ZoukanDirs(temp), InMemoryPreferenceStore())
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

        // An existing library: no folder changed in the last seconds
        mangas.forEach { paths.mangaDir(it).setLastModified(System.currentTimeMillis() - 60_000) }

        fun manager() = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))
        fun ms(block: () -> Unit): Long {
            val t = System.nanoTime()
            block()
            return (System.nanoTime() - t) / 1_000_000
        }

        /** As the library and then the entries' pages do: the badges' counts for every entry, then each entry's chapters. */
        fun load(manager: DownloadManager): Pair<Long, Long> {
            var total = 0
            val counts = ms { total += manager.downloadCounts(mangas).values.sum() }
            val ids = ms { mangas.forEach { m -> total += manager.downloadedIds(m, chapters.getValue(m.id)).size } }
            total shouldBe entries * perEntry * 2
            return counts to ids
        }

        /** The previous index, for a before figure on the same machine at the same time: one listing per folder, names in a set. */
        fun oldLoad(): Pair<Long, Long> {
            var total = 0
            val sets = HashMap<Long, Set<String>>()
            val counts = ms {
                mangas.forEach { m ->
                    val names = paths.mangaDir(m).list()!!.toHashSet().also { sets[m.id] = it }
                    total += names.count { n -> !n.endsWith("_tmp") && !n.startsWith(".") && DownloadIndex.NOT_CHAPTER.none { n.endsWith(it, true) } }
                }
            }
            val ids = ms {
                mangas.forEach { m ->
                    val names = sets.getValue(m.id)
                    total += chapters.getValue(m.id).count { c ->
                        val current = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, false, false)
                        val v1 = FileNames.buildValidFilename(c.name)
                        val other = DownloadPaths.chapterDirName(c.name, c.scanlator, c.url, true, true)
                        listOf(current, v1, other).distinct().flatMap { listOf(it, "$it.cbz") }.any { it in names }
                    }
                }
            }
            total shouldBe entries * perEntry * 2
            return counts to ids
        }

        fun Pair<Long, Long>.text() = "${first + second}ms (counts $first + chapters $second)"

        // Warms the JIT and the disk cache, as an app that ran before
        load(manager())
        oldLoad()
        val oldCold = oldLoad()
        val manager = manager()
        val cold = load(manager)
        val warm = (1..3).map { load(manager) }.minBy { it.first + it.second }
        // Past the recheck time every folder's modified time is read again (nothing changed)
        Thread.sleep(DownloadIndex.RECHECK_MILLIS + 100)
        val readBefore = manager.foldersRead
        val rechecked = load(manager)
        println("DownloadIndexBenchmark recheck: ${manager.foldersRead - readBefore} folders read again")
        println(
            "DownloadIndexBenchmark: previous index ${oldCold.text()}; cold load ${cold.text()}, warm refresh ${warm.text()}, " +
                "refresh after the recheck time ${rechecked.text()} ($entries entries x $perEntry chapters)",
        )
    }
}
