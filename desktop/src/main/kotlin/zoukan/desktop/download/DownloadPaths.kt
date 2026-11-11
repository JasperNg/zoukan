package zoukan.desktop.download

import eu.kanade.tachiyomi.util.lang.Hash
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.storage.ZoukanStorage
import java.io.File

/**
 * Where a chapter's download lives: `downloads/<source>/<manga>/<chapter>[.cbz]`, named as the
 * Android app's DownloadProvider names them, including the names older versions used.
 */
class DownloadPaths(
    private val storage: ZoukanStorage,
    private val sourceManager: SourceManager,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
) {
    private val disallowNonAscii get() = libraryPreferences.disallowNonAsciiFilenames().get()
    private val includeHash get() = downloadPreferences.includeChapterUrlHash().get()

    /** The source's folder name: "Name (LANG)", or the source id when the source is unknown. */
    fun sourceDirName(sourceId: Long): String =
        FileNames.buildValidFilename(sourceManager.getOrStub(sourceId).toString(), disallowNonAscii = disallowNonAscii)

    fun mangaDirName(mangaTitle: String): String =
        FileNames.buildValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii)

    fun sourceDir(sourceId: Long): File = File(storage.downloads, sourceDirName(sourceId))

    /** Android uses the source's own title (SY's ogTitle), not a custom one. */
    fun mangaDir(manga: Manga): File = File(sourceDir(manga.source), mangaDirName(manga.ogTitle))

    fun chapterDirName(chapter: Chapter): String =
        chapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii, includeHash)

    /** The names a downloaded chapter may have: the current one, then older ones, each as a folder or a CBZ. */
    fun validChapterNames(chapter: Chapter): List<String> {
        val current = chapterDirName(chapter)
        val v1 = FileNames.buildValidFilename(
            if (!chapter.scanlator.isNullOrBlank()) "${chapter.scanlator}_${chapter.name.ifBlank { "Chapter" }}" else chapter.name.ifBlank { "Chapter" },
        )
        val otherSettings = chapterDirName(chapter.name, chapter.scanlator, chapter.url, !disallowNonAscii, !includeHash)
        return listOf(current, v1, otherSettings).distinct().flatMap { listOf(it, "$it.cbz") }
    }

    /** The chapter's download, a folder of images or a CBZ, if it exists. */
    fun findChapter(manga: Manga, chapter: Chapter): File? {
        val dir = mangaDir(manga)
        if (!dir.isDirectory) return null
        return validChapterNames(chapter).asSequence().map { File(dir, it) }.firstOrNull { it.exists() }
    }

    companion object {
        const val TMP_SUFFIX = "_tmp"

        fun chapterDirName(
            name: String,
            scanlator: String?,
            url: String,
            disallowNonAscii: Boolean,
            includeHash: Boolean,
        ): String {
            var dirName = name.ifBlank { "Chapter" }
            if (!scanlator.isNullOrBlank()) dirName = scanlator + "_" + dirName
            // Less 7 bytes for the hash and underscore, 4 bytes for .cbz
            dirName = FileNames.buildValidFilename(dirName, FileNames.MAX_FILE_NAME_BYTES - 11, disallowNonAscii)
            if (includeHash) dirName += "_" + Hash.md5(url).take(6)
            return dirName
        }
    }
}
