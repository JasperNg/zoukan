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
    internal val storage: ZoukanStorage,
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

    /** The downloads folder; every delete must stay inside it (see [SafeDelete]). */
    val downloadsRoot: File get() = storage.downloads

    /** False when the chosen storage folder is missing (an unplugged drive): nothing may be written or deleted. */
    val isStorageAvailable: Boolean get() = storage.isAvailable

    /** @throws zoukan.desktop.storage.StorageUnavailableException when the chosen storage folder is missing */
    fun requireStorage() {
        if (!storage.isAvailable) throw zoukan.desktop.storage.StorageUnavailableException(storage.root)
    }

    /** Android uses the source's own title (SY's ogTitle), not a custom one. */
    fun mangaDir(manga: Manga): File = File(sourceDir(manga.source), mangaDirName(manga.ogTitle))

    /**
     * The folders an entry's downloads may be in: [mangaDir], then the name the other "Disallow
     * non-ASCII filenames" setting gives it, so downloads made before the setting changed are found.
     */
    fun mangaDirs(manga: Manga): List<File> {
        val sourceDir = sourceDir(manga.source)
        val names = mangaDirNames(manga.ogTitle)
        return names.map { File(sourceDir, it) }
    }

    /** The entry folder names [mangaDirs] looks in, the current one first. */
    fun mangaDirNames(mangaTitle: String): List<String> =
        listOf(
            FileNames.buildValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii),
            FileNames.buildValidFilename(mangaTitle, disallowNonAscii = !disallowNonAscii),
        ).distinct()

    fun chapterDirName(chapter: Chapter): String =
        chapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii, includeHash)

    /** Whether a chapter's download would be named differently after the source changed it, as Android's DownloadProvider. */
    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean =
        chapterDirName(oldChapter) != chapterDirName(newChapter)

    /**
     * The names a downloaded chapter may have: the current one, then older ones, each as a folder or
     * a CBZ. Android looks for the current settings, its v1 name and both settings flipped; this
     * also tries each setting flipped on its own, so every combination is found.
     */
    fun validChapterNames(chapter: Chapter): List<String> {
        val ascii = disallowNonAscii
        val hash = includeHash
        val key = NamesKey(chapter.name, chapter.scanlator, chapter.url, ascii, hash)
        synchronized(namesCache) { namesCache[key] }?.let { return it }
        val v1 = FileNames.buildValidFilename(
            if (!chapter.scanlator.isNullOrBlank()) "${chapter.scanlator}_${chapter.name.ifBlank { "Chapter" }}" else chapter.name.ifBlank { "Chapter" },
        )
        fun name(a: Boolean, h: Boolean) = chapterDirName(chapter.name, chapter.scanlator, chapter.url, a, h)
        val names = listOf(name(ascii, hash), v1, name(!ascii, !hash), name(!ascii, hash), name(ascii, !hash))
            .distinct()
            .flatMap { listOf(it, "$it.cbz") }
        synchronized(namesCache) { namesCache[key] = names }
        return names
    }

    /**
     * [validChapterNames] in the same order, made only as far as they are read: a lookup that finds
     * the current name never builds the older ones (each costs an MD5 of the URL).
     */
    fun chapterNameCandidates(chapter: Chapter): Sequence<String> = sequence {
        val current = chapterDirName(chapter)
        yield(current)
        yield("$current.cbz")
        yieldAll(validChapterNames(chapter).asSequence().drop(2))
    }

    private data class NamesKey(val name: String, val scanlator: String?, val url: String, val ascii: Boolean, val hash: Boolean)

    /** The last few thousand chapters' names, so a library refresh does not hash every URL again. */
    private val namesCache = object : LinkedHashMap<NamesKey, List<String>>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NamesKey, List<String>>?) = size > NAMES_CACHE_SIZE
    }

    /** The chapter's download, a folder of images or a CBZ, if it exists. */
    fun findChapter(manga: Manga, chapter: Chapter): File? {
        val dir = mangaDir(manga)
        if (!dir.isDirectory) return null
        return validChapterNames(chapter).asSequence().map { File(dir, it) }.firstOrNull { it.exists() }
    }

    companion object {
        const val TMP_SUFFIX = "_tmp"
        private const val NAMES_CACHE_SIZE = 20_000

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
