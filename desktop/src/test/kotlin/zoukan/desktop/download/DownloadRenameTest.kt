package zoukan.desktop.download

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

/** Renaming a download after the source renamed its chapter, as Android's DownloadManager.renameChapter. */
class DownloadRenameTest {

    @TempDir
    lateinit var temp: File

    private fun <T> pref(value: T) = mockk<Preference<T>> { every { get() } returns value }

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 1L
        every { source } returns 7L
        every { ogTitle } returns "Manga"
    }

    private fun setup(): Pair<DownloadManager, DownloadPaths> {
        val source = mockk<Source> { every { this@mockk.toString() } returns "Fake (EN)" }
        val sourceManager = mockk<SourceManager> { every { getOrStub(7L) } returns source }
        val storage = mockk<ZoukanStorage> { every { downloads } returns File(temp, "downloads") }
        val libraryPreferences = mockk<LibraryPreferences> { every { disallowNonAsciiFilenames() } returns pref(false) }
        val downloadPreferences = mockk<DownloadPreferences> {
            every { includeChapterUrlHash() } returns pref(false)
            every { downloadCacheRenewInterval() } returns pref(1)
        }
        val paths = DownloadPaths(storage, sourceManager, libraryPreferences, downloadPreferences)
        val manager = DownloadManager(ZoukanDirs(temp), paths, sourceManager, downloadPreferences, mockk(), mockk(), mockk(), mockk(relaxed = true))
        return manager to paths
    }

    private fun chapter(name: String, scanlator: String? = null) =
        Chapter.create().copy(id = 1, mangaId = 1, url = "/1", name = name, scanlator = scanlator)

    @Test
    fun `a renamed chapter's CBZ follows the new name`() {
        val (manager, paths) = setup()
        val old = chapter("Ch. 1")
        val new = chapter("Chapter 1", "Group")
        val dir = paths.mangaDir(manga).apply { mkdirs() }
        File(dir, "Ch. 1.cbz").writeText("zip")
        manager.isDownloaded(manga, old) shouldBe true
        paths.isChapterDirNameChanged(old, new) shouldBe true

        manager.renameChapter(manga, old, new)

        File(dir, "Group_Chapter 1.cbz").readText() shouldBe "zip"
        File(dir, "Ch. 1.cbz").exists() shouldBe false
        manager.isDownloaded(manga, new) shouldBe true
        manager.isDownloaded(manga, old) shouldBe false
    }

    @Test
    fun `a renamed chapter's folder follows the new name`() {
        val (manager, paths) = setup()
        val old = chapter("Ch. 2")
        val new = chapter("Chapter 2")
        val dir = File(paths.mangaDir(manga), "Ch. 2").apply { mkdirs() }
        File(dir, "001.png").writeText("png")

        manager.renameChapter(manga, old, new)

        File(paths.mangaDir(manga), "Chapter 2/001.png").readText() shouldBe "png"
        manager.isDownloaded(manga, new) shouldBe true
    }
}
