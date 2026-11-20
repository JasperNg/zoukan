package zoukan.desktop.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager.FolderMove
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.download_folder_not_moved_back_text
import zoukan.i18n.desktop.download_folder_shared_not_renamed_text
import zoukan.i18n.desktop.download_folders_not_renamed
import zoukan.i18n.desktop.download_folders_not_renamed_text
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Chapter and manga download directory resolution, folder renames/moves, and filesystem checks.
 *
 * Owns the [DownloadIndex] cache and coordinates entry rename locks so concurrent metadata
 * refreshes never move the same entry's folders at once.
 */
internal class DownloadFolderOps(
    internal val paths: DownloadPaths,
    private val preferences: DownloadPreferences,
    internal val index: DownloadIndex = DownloadIndex(),
    private val coOwnerLookup: () -> (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = { null },
    private val isEntryQueued: (Long) -> Boolean = { false },
    private val onCacheChanged: () -> Unit = {},
) {
    /** How many folders were listed, for the benchmark. */
    val foldersRead: Int get() = index.listingsRead

    /** When [index] was last emptied, for the download cache renew interval. */
    @Volatile
    private var lastRenew = System.currentTimeMillis()

    val root: File get() = paths.downloadsRoot

    /**
     * One lock per entry held across a title change's rename of its download folders, the save of
     * the new title and a move back ([withRenameLock]), so two refreshes of one entry never move
     * its folders at once. It is a lock of its own, not entryLock: a refresh does not wait for a
     * chapter that is downloading. Nothing takes another lock while waiting for it, and nothing
     * under it takes an entry lock or another rename lock, so it cannot deadlock with them.
     */
    // Internal so a test can put in a lock that records who asks for it
    internal val renameLocks = ConcurrentHashMap<Long, Mutex>()

    /** Moves a download folder; tests make it fail. Never replaces anything (no REPLACE_EXISTING). */
    @Volatile
    internal var moveFolder: (Path, Path) -> Unit = { from, to -> Files.move(from, to) }

    /** The entries and titles whose folders could not follow a new title and the user was told about, in this run. */
    private val renameWarned = ConcurrentHashMap.newKeySet<Pair<Long, String>>()

    /** Runs [block] holding entry [mangaId]'s rename lock (see [renameLocks]). */
    suspend fun <T> withRenameLock(mangaId: Long, block: suspend () -> T): T =
        renameLocks.getOrPut(mangaId) { Mutex() }.withLock { block() }

    /**
     * Reads the download folders again once "Download cache renew interval" (KMK) has passed. Folders
     * are also checked by their modified time every few seconds (see [DownloadIndex]), so this is
     * only an upper bound. "Manual" (-1) only renews from Reindex downloads.
     */
    private fun renewIfDue() {
        val hours = preferences.downloadCacheRenewInterval().get()
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastRenew < hours * 60L * 60 * 1000) return
        lastRenew = now
        index.clear()
        onCacheChanged()
    }

    /** Forgets what was read from disk, for example after the storage folder moved. */
    fun invalidateCache() {
        lastRenew = System.currentTimeMillis()
        index.clear()
        onCacheChanged()
    }

    fun invalidate(mangaDir: File) {
        index.invalidate(mangaDir)
        onCacheChanged()
    }

    fun invalidateEntryDirs(manga: Manga) {
        dirsOf(manga).forEach { index.invalidate(it) }
    }

    fun resolveMangaDir(manga: Manga): File = index.resolve(root, paths.mangaDir(manga))

    /**
     * The entry's download folders ([DownloadPaths.mangaDirs]) as named on disk: a source or entry
     * folder named in another Unicode form or case is the same folder (see [DownloadIndex.resolve]).
     */
    fun dirsOf(manga: Manga): List<File> = resolveMangaDirs(paths, index, manga)

    /**
     * The chapter's download, a folder with files in it or a CBZ with bytes in it, in the entry's
     * folder (or under the name the other non-ASCII setting gives it). Reads the folder on a cache
     * miss: call off the UI thread.
     */
    fun findChapter(manga: Manga, chapter: Chapter): File? {
        renewIfDue()
        return findChapter(paths, index, manga, chapter)
    }

    /** Reads the entry's download folder on a cache miss: call off the UI thread. */
    fun isDownloaded(manga: Manga, chapter: Chapter): Boolean = findChapter(manga, chapter) != null

    /** Reads the entry's download folder on a cache miss: call off the UI thread. */
    fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> {
        renewIfDue()
        // The folders and the downloads folder once per entry, not per chapter
        val dirs = dirsOf(manga)
        return chapters.filter { findChapterIn(paths, index, dirs, it) != null }.mapTo(HashSet()) { it.id }
    }

    /**
     * How many chapters of [manga] are downloaded, for the library's download badge and filter:
     * folders with files and CBZs with bytes, a chapter with both counted once; unfinished
     * downloads, covers and other files are not chapters.
     * Reads the entry's download folder on a cache miss: call off the UI thread.
     */
    fun downloadCount(manga: Manga): Int {
        renewIfDue()
        val root = root
        return dirsOf(manga).flatMapTo(HashSet()) { index.chapterKeys(root, it) }.size
    }

    /**
     * [downloadCount] of each of [mangas], for the library's badges and the statistics: the folders
     * are read on several threads at once (the common pool, one per core), as a first read is mostly
     * waiting for the disk. An entry whose folder cannot be read counts 0.
     * Reads the folders on a cache miss: call off the UI thread.
     */
    fun downloadCounts(mangas: List<Manga>): Map<Long, Int> {
        renewIfDue()
        val counts = ConcurrentHashMap<Long, Int>()
        mangas.distinctBy { it.id }.parallelStream().forEach { manga ->
            counts[manga.id] = try {
                downloadCount(manga)
            } catch (e: RuntimeException) {
                logcat(LogPriority.WARN, e) { "Counting the downloads of ${manga.ogTitle} failed" }
                0
            }
        }
        return counts
    }

    /**
     * Whether the downloads of [a] and [b] go to the same folder: sources with the same name and
     * language, and titles that give the same folder name. Deleting one's chapters there could then
     * delete the other's.
     */
    fun sharesDownloadFolder(a: Manga, b: Manga): Boolean {
        val root = root
        fun keys(m: Manga) = paths.mangaDirs(m).mapTo(HashSet()) { index.key(root, it.parentFile.name) + "/" + index.key(root, it.name) }
        return keys(a).any { it in keys(b) }
    }

    /** The names on disk of every copy of [chapter] in [dir], complete or not, unfinished ones included. */
    fun namesOnDisk(dir: File, chapter: Chapter): List<String> {
        val candidates = paths.validChapterNames(chapter) +
            paths.validChapterNames(chapter).map { it + DownloadPaths.TMP_SUFFIX }
        return index.matching(root, dir, candidates)
    }

    /** [namesOnDisk] of each of [chapters]. */
    fun namesOnDisk(dir: File, chapters: List<Chapter>): Set<String> =
        chapters.flatMapTo(HashSet()) { namesOnDisk(dir, it) }

    /**
     * Gives a downloaded chapter the name its new name or scanlator calls for, as Android's
     * renameChapter after a source sync, so the download keeps showing and Clean up downloaded
     * chapters does not take it for an unknown file.
     *
     * Never replaces anything: when a download of the new name exists, or the move fails, the
     * download keeps its old name and this returns false. True when renamed or nothing to rename.
     */
    fun renameChapter(manga: Manga, oldChapter: Chapter, newChapter: Chapter): Boolean {
        val dir = resolveMangaDir(manga)
        if (!dir.isDirectory) return true
        // Assume there's only 1 version of the chapter name formats present
        index.invalidate(dir)
        val oldDownload = index.matching(root, dir, paths.validChapterNames(oldChapter))
            .map { File(dir, it) }
            .firstOrNull { !it.name.endsWith(DownloadPaths.TMP_SUFFIX) } ?: return true

        var newName = paths.chapterDirName(newChapter)
        if (oldDownload.isFile && oldDownload.extension == "cbz") newName += ".cbz"
        if (oldDownload.name == newName) return true

        val target = File(dir, newName)
        if (target.exists() && !runCatching { Files.isSameFile(oldDownload.toPath(), target.toPath()) }.getOrDefault(false)) {
            logcat(LogPriority.WARN) { "Not renaming ${oldDownload.name} to $newName: a download of that name exists" }
            return false
        }
        return try {
            // No REPLACE_EXISTING: a target created in between makes the move fail
            Files.move(oldDownload.toPath(), target.toPath())
            invalidate(dir)
            true
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, e) { "Could not rename downloaded chapter: ${oldDownload.name}" }
            false
        }
    }

    /**
     * Moves an entry's download folders to the names [newTitle] gives them, as Android's renameManga
     * ([renameMangaFolders]). True when they moved or there was nothing to move.
     */
    suspend fun renameManga(manga: Manga, newTitle: String): Boolean = renameMangaFolders(manga, newTitle) != null

    /**
     * Moves each of [manga]'s download folders to the name [newTitle] gives a folder of its kind
     * ([DownloadPaths.existingMangaDirRenames]), as Android's renameManga: an entry can have several
     * (downloads made before and after "Disallow non-ASCII filenames" changed, Android's name beside
     * the desktop's).
     *
     * All or none, and never over anything on disk: nothing moves when a chapter of the entry is
     * queued, when a folder is also another entry's ([sharesDownloadFolder]), when a folder of a new
     * name exists, or when two folders would get the same name (their chapters would have to be
     * merged). A move that fails puts the folders moved before it back. When several folders, or a
     * shared one, keep the entry's title, the user is told once per entry and title in a run.
     *
     * @return the moves made, empty when there was nothing to move; null when nothing moved
     */
    suspend fun renameMangaFolders(manga: Manga, newTitle: String): List<FolderMove>? = withContext(Dispatchers.IO) {
        val moves = paths.existingMangaDirRenames(manga.source, manga.ogTitle, newTitle)
            .map { (folder, name) -> FolderMove(folder, File(folder.parentFile, name)) }
            // Already the new name; or a case-only change on a disk that ignores case, which names the same folder
            .filterNot { it.from.name == it.to.name || !it.from.exists() || isSameFolder(it.from, it.to) }
        if (moves.isEmpty()) return@withContext emptyList()
        if (isEntryQueued(manga.id)) {
            logcat(LogPriority.WARN) { "Not renaming the download folders of the entry: chapters of the entry are queued" }
            return@withContext null
        }
        val others = try {
            coOwnerLookup()?.invoke(manga).orEmpty().map { it.first }.filter { it.id != manga.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without knowing who else uses the folder, it does not move
            logcat(LogPriority.WARN, e) { "Not renaming the download folders of the entry: the entries sharing them could not be read" }
            return@withContext null
        }
        if (others.isNotEmpty()) {
            logcat(LogPriority.WARN) { "Not renaming the download folders of the entry: another entry's downloads are in them too" }
            warnRenameOnce(manga.id, newTitle) {
                localized(DMR.strings.download_folder_shared_not_renamed_text, manga.ogTitle, newTitle, moves.first().from.name)
            }
            return@withContext null
        }
        val targets = moves.map { folderKey(it.to) }
        val taken = moves.filter { Files.exists(it.to.toPath(), LinkOption.NOFOLLOW_LINKS) }
        if (taken.isNotEmpty() || targets.distinct().size != targets.size) {
            logcat(LogPriority.WARN) {
                "Not renaming the download folders of the entry: " +
                    if (taken.isNotEmpty()) "a folder named ${taken.first().to.name} exists" else "two folders would get one name"
            }
            // One folder whose new name is taken keeps its title quietly, as before; several are told
            if (moves.size > 1) {
                warnRenameOnce(manga.id, newTitle) {
                    localized(DMR.strings.download_folders_not_renamed_text, manga.ogTitle, newTitle, moves.size)
                }
            }
            return@withContext null
        }
        val done = ArrayList<FolderMove>()
        for (move in moves) {
            try {
                // No REPLACE_EXISTING: nothing already on disk is ever overwritten
                moveFolder(move.from.toPath(), move.to.toPath())
                done += move
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not rename the download folder ${move.from.name} to ${move.to.name}" }
                moveBack(done)
                return@withContext null
            } finally {
                invalidate(move.from)
                invalidate(move.to)
            }
        }
        done
    }

    /**
     * Moves back what [renameMangaFolders] moved, the last move first, when the new title could not
     * be saved: each folder to the name it had, never over anything on disk.
     *
     * @return whether every folder is back
     */
    suspend fun moveFoldersBack(moves: List<FolderMove>): Boolean = withContext(Dispatchers.IO) { moveBack(moves) }

    private fun moveBack(moves: List<FolderMove>): Boolean {
        var all = true
        for (move in moves.asReversed()) {
            try {
                moveFolder(move.to.toPath(), move.from.toPath())
            } catch (e: IOException) {
                // Its chapters are out of view until it has its name again: the user is told which
                logcat(LogPriority.ERROR, e) { "Could not move the download folder ${move.to.name} back to ${move.from.name}" }
                Notifications.post(
                    localized(DMR.strings.download_folders_not_renamed),
                    localized(DMR.strings.download_folder_not_moved_back_text, move.to.name, move.from.name),
                    Notifications.Kind.ERROR,
                )
                all = false
            } finally {
                invalidate(move.from)
                invalidate(move.to)
            }
        }
        return all
    }

    private fun warnRenameOnce(mangaId: Long, newTitle: String, text: () -> String) {
        if (!renameWarned.add(mangaId to newTitle)) return
        Notifications.post(localized(DMR.strings.download_folders_not_renamed), text(), Notifications.Kind.WARNING)
    }

    /** [folder]'s source folder and name as the file system compares names (see [DownloadIndex.key]). */
    private fun folderKey(folder: File): String = index.key(root, folder.parentFile.name) + "/" + index.key(root, folder.name)

    private fun isSameFolder(a: File, b: File): Boolean =
        Files.exists(b.toPath(), LinkOption.NOFOLLOW_LINKS) && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false)

    /** Free bytes on the volume holding [dir], checked via its nearest existing ancestor. */
    fun usableSpace(dir: File): Long? =
        dir.parentFile?.let { generateSequence(it) { f -> f.parentFile }.firstOrNull(File::exists) }?.usableSpace

    companion object {
        internal fun resolveMangaDirs(paths: DownloadPaths, index: DownloadIndex, manga: Manga): List<File> {
            val root = paths.downloadsRoot
            return paths.mangaDirs(manga).map { index.resolve(root, it) }.distinct()
        }

        internal fun findChapterIn(paths: DownloadPaths, index: DownloadIndex, dirs: List<File>, chapter: Chapter): File? {
            val root = paths.downloadsRoot
            val names = paths.chapterNameCandidates(chapter)
            for (dir in dirs) {
                index.findValid(root, dir, names)?.let { return File(dir, it) }
            }
            return null
        }

        /** Canonical chapter lookup shared by [DownloadPaths.findChapter] and [DownloadFolderOps.findChapter]. */
        internal fun findChapter(paths: DownloadPaths, index: DownloadIndex, manga: Manga, chapter: Chapter): File? =
            findChapterIn(paths, index, resolveMangaDirs(paths, index, manga), chapter)
    }
}
