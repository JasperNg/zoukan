package zoukan.desktop.ui.stats

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

class StatsScreenModelTest {

    @TempDir
    lateinit var temp: File

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    private fun manga(id: Long, title: String) = mockk<Manga>(relaxed = true) {
        every { this@mockk.id } returns id
        every { source } returns 7L
        every { ogTitle } returns title
    }

    private fun setup(): Pair<DownloadManager, DownloadPaths> {
        val source = mockk<Source> { every { this@mockk.toString() } returns "Fake (EN)" }
        val sourceManager = mockk<SourceManager> { every { getOrStub(7L) } returns source }
        val storage = mockk<ZoukanStorage> {
            every { downloads } returns File(temp, "downloads")
            every { isAvailable } returns true
        }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))
        return manager to paths
    }

    /** Every folder and file under [root] with its bytes, to prove nothing was touched. */
    private fun snapshot(root: File): Map<String, List<Byte>?> =
        root.walk().filter { it != root }.associate {
            it.relativeTo(root).path to if (it.isFile) it.readBytes().toList() else null
        }

    @Test
    fun `only finished chapters of library entries count as downloaded`() {
        val (manager, paths) = setup()
        val inLibrary = manga(1L, "Manga")
        val removed = manga(2L, "Removed")
        paths.mangaDir(inLibrary).apply { mkdirs() }.let { dir ->
            File(dir, "Chapter 1").mkdirs()
            File(dir, "Chapter 1/001.png").writeText("png")
            // An empty folder is no download
            File(dir, "Chapter 4").mkdirs()
            File(dir, "Chapter 2_tmp").mkdirs()
            File(dir, "Chapter 3.cbz_tmp").writeText("partial")
        }
        paths.mangaDir(removed).apply { mkdirs() }.let { File(it, "Chapter 1.cbz").writeText("zip") }
        val downloads = File(temp, "downloads")
        val before = snapshot(downloads)

        countDownloadedChapters(listOf(inLibrary), manager) shouldBe 1

        snapshot(downloads) shouldBe before
    }
}
