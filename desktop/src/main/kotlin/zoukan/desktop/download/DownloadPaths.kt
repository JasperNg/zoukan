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
 * Android app's DownloadProvider names them, including the names older versions used. A name
 * Android builds that is not valid on every OS ([FileNames]) is written in its portable form, and
 * Android's form is still looked up.
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
     * non-ASCII filenames" setting gives it, so downloads made before the setting changed are found,
     * then Android's names where they are not valid everywhere ([legacyNames]), in the source's
     * folder and, if Android named that differently, in Android's source folder too.
     */
    fun mangaDirs(manga: Manga): List<File> {
        val names = mangaDirNames(manga.ogTitle)
        return sourceDirNames(manga.source).flatMap { source -> names.map { File(File(storage.downloads, source), it) } }
    }

    /** The source folder names [mangaDirs] looks in: [sourceDirName], then Android's if it differs. */
    private fun sourceDirNames(sourceId: Long): List<String> {
        val name = sourceManager.getOrStub(sourceId).toString()
        return listOf(FileNames.buildValidFilename(name, disallowNonAscii = disallowNonAscii)) +
            legacyNames(FileNames.androidValidFilename(name, disallowNonAscii = disallowNonAscii))
    }

    /** The entry folder names [mangaDirs] looks in, the current one first. */
    fun mangaDirNames(mangaTitle: String): List<String> =
        (
            listOf(
                FileNames.buildValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii),
                FileNames.buildValidFilename(mangaTitle, disallowNonAscii = !disallowNonAscii),
            ) +
                legacyNames(FileNames.androidValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii)) +
                legacyNames(FileNames.androidValidFilename(mangaTitle, disallowNonAscii = !disallowNonAscii))
            ).distinct()

    /**
     * The entry folder of [mangaTitle] that exists in the source's folder: the one [mangaDirName]
     * names, else Android's name for it when that is not valid everywhere and is on disk (found by
     * listing the folder, never by opening the name: on Windows `NUL` is a device).
     */
    fun existingMangaDir(sourceId: Long, mangaTitle: String): File {
        val sourceDir = sourceDir(sourceId)
        val current = File(sourceDir, mangaDirName(mangaTitle))
        if (current.exists()) return current
        val legacy = legacyNames(FileNames.androidValidFilename(mangaTitle, disallowNonAscii = disallowNonAscii))
        if (legacy.isEmpty()) return current
        val onDisk = sourceDir.list().orEmpty().toSet()
        return legacy.firstOrNull { it in onDisk }?.let { File(sourceDir, it) } ?: current
    }

    fun chapterDirName(chapter: Chapter): String =
        chapterDirName(chapter.name, chapter.scanlator, chapter.url, disallowNonAscii, includeHash)

    /** Whether a chapter's download would be named differently after the source changed it, as Android's DownloadProvider. */
    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean =
        chapterDirName(oldChapter) != chapterDirName(newChapter)

    /**
     * The names a downloaded chapter may have: the current one, then older ones, each as a folder or
     * a CBZ. Android looks for the current settings, its v1 name and both settings flipped; this
     * also tries each setting flipped on its own, so every combination is found, and then each of
     * these as Android names it where that is not valid everywhere ([legacyNames]).
     */
    fun validChapterNames(chapter: Chapter): List<String> {
        val ascii = disallowNonAscii
        val hash = includeHash
        val key = NamesKey(chapter.name, chapter.scanlator, chapter.url, ascii, hash)
        synchronized(namesCache) { namesCache[key] }?.let { return it }
        val v1Input = if (!chapter.scanlator.isNullOrBlank()) "${chapter.scanlator}_${chapter.name.ifBlank { "Chapter" }}" else chapter.name.ifBlank { "Chapter" }
        val md5 by lazy { Hash.md5(chapter.url).take(6) }
        fun name(a: Boolean, h: Boolean) = chapterDirName(chapter.name, chapter.scanlator, a, if (h) md5 else null)
        fun android(a: Boolean, h: Boolean) = androidChapterDirName(chapter.name, chapter.scanlator, a, if (h) md5 else null)
        val current = listOf(name(ascii, hash), FileNames.buildValidFilename(v1Input), name(!ascii, !hash), name(!ascii, hash), name(ascii, !hash))
        // Android's names where they are not valid everywhere, after the desktop's own
        val legacy = listOf(android(ascii, hash), FileNames.androidValidFilename(v1Input), android(!ascii, !hash), android(!ascii, hash), android(ascii, !hash))
            .flatMap { legacyNames(it) }
        val names = (current + legacy)
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

    /** The chapter's download, a folder of images or a CBZ, if it exists, in any of [mangaDirs]. */
    fun findChapter(manga: Manga, chapter: Chapter): File? = mangaDirs(manga).firstNotNullOfOrNull { dir ->
        // Android's names that are not valid everywhere only when listed: on Windows `NUL` is a device
        val listed = dir.list()?.toSet() ?: return@firstNotNullOfOrNull null
        validChapterNames(chapter).asSequence()
            .filter { FileNames.isPortable(it) || it in listed }
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
    }

    companion object {
        const val TMP_SUFFIX = "_tmp"
        private const val NAMES_CACHE_SIZE = 20_000

        /**
         * The chapter's folder name: Android's DownloadProvider.getChapterDirName when that is a
         * valid name on every OS, else the same built from [FileNames]' portable name.
         */
        fun chapterDirName(
            name: String,
            scanlator: String?,
            url: String,
            disallowNonAscii: Boolean,
            includeHash: Boolean,
        ): String = chapterDirName(name, scanlator, disallowNonAscii, if (includeHash) Hash.md5(url).take(6) else null)

        private fun chapterDirName(name: String, scanlator: String?, disallowNonAscii: Boolean, hash: String?): String {
            val android = androidChapterDirName(name, scanlator, disallowNonAscii, hash)
            if (FileNames.isPortable(android)) return android
            // Valid with the hash too: the portable part ends in neither a dot nor a space, and a
            // device name's base (before the first dot) stays changed or gets the hash
            val portable = FileNames.portableFilename(chapterInput(name, scanlator), CHAPTER_NAME_BYTES, disallowNonAscii)
            return if (hash != null) portable + "_" + hash else portable
        }

        /** DownloadProvider.getChapterDirName as Android has it, with the URL's hash already made. */
        private fun androidChapterDirName(name: String, scanlator: String?, disallowNonAscii: Boolean, hash: String?): String {
            val dirName = FileNames.androidValidFilename(chapterInput(name, scanlator), CHAPTER_NAME_BYTES, disallowNonAscii)
            return if (hash != null) dirName + "_" + hash else dirName
        }

        private fun chapterInput(name: String, scanlator: String?): String {
            val dirName = name.ifBlank { "Chapter" }
            return if (!scanlator.isNullOrBlank()) scanlator + "_" + dirName else dirName
        }

        // Less 7 bytes for the hash and underscore, 4 bytes for .cbz
        private const val CHAPTER_NAME_BYTES = FileNames.MAX_FILE_NAME_BYTES - 11

        /**
         * Android's [name] as it may be on disk ([FileNames.spellingsOnDisk]) when it is not valid
         * everywhere; none when it is valid (the desktop then writes it too). Only looked up: never
         * written, and found by listing a folder, so on Windows a device name is never opened. A
         * spelling this OS cannot have as a path (`?` on Windows) cannot be on disk and is left out,
         * so no lookup fails on it. Nor can a Windows device name (`NUL`, `com1.txt`) on [windows]:
         * it is a valid path there, but names the device, never a folder.
         */
        internal fun legacyNames(name: String, windows: Boolean = IS_WINDOWS): List<String> =
            if (FileNames.isPortable(name)) {
                emptyList()
            } else {
                FileNames.spellingsOnDisk(name).filter { isPathHere(it) && !(windows && FileNames.isWindowsDeviceName(it)) }
            }

        private val IS_WINDOWS = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        private fun isPathHere(name: String): Boolean = try {
            java.nio.file.Path.of(name)
            true
        } catch (_: java.nio.file.InvalidPathException) {
            false
        }
    }
}
