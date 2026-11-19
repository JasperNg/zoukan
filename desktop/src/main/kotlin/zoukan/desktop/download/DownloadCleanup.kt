package zoukan.desktop.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.download.DownloadManager.CleanupItem
import zoukan.desktop.download.DownloadManager.CleanupPlan
import zoukan.desktop.download.DownloadManager.CleanupReason
import zoukan.desktop.download.DownloadManager.CleanupRecheck
import zoukan.desktop.download.DownloadManager.CleanupResult
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Chapter/manga deletion, orphan/temp folder clean-up, entry lock ordering, and
 * stopping-job/waiter coordination so deletions and restarts never race a cancelled download.
 */
internal class DownloadCleanup(
    private val paths: DownloadPaths,
    private val sourceManager: SourceManager,
    private val preferences: DownloadPreferences,
    private val getManga: GetManga,
    private val getCategories: GetCategories,
    private val folderOps: DownloadFolderOps,
    private val scope: CoroutineScope,
    private val queueLock: Any,
    private val queuedChapterIds: () -> Set<Long> = { emptySet() },
    private val queuedMangaIds: () -> Set<Long> = { emptySet() },
    private val cancelChapters: (Set<Long>) -> Unit = {},
    private val cancelSeries: (Long) -> Unit = {},
    private val coOwnerLookup: () -> (suspend (Manga) -> List<Pair<Manga, List<Chapter>>>)? = { null },
) {
    private val root: File get() = paths.downloadsRoot
    private val index: DownloadIndex get() = folderOps.index

    private val stopping = LinkedHashSet<Job>()
    private val stoppingWaiters = ConcurrentHashMap<Job, Job>()

    /**
     * One lock per entry, shared by the clean-up and the download writer, so clean-up never deletes the
     * files of a download that is starting or running. Only the clean-up holds several at once (the
     * owners of a shared folder), always taken in id order; nothing else holds more than one.
     */
    // Internal so a test can put in a lock that records who asks for it: a Mutex does not show its waiters
    internal val entryLocks = ConcurrentHashMap<Long, Mutex>()

    internal fun entryLock(id: Long): Mutex = entryLocks.getOrPut(id) { Mutex() }

    /**
     * Records [job] as stopping before cancelling it, and removes it when it completes.
     * Callers must hold [queueLock].
     */
    internal fun cancelAndTrack(job: Job) {
        stopping.add(job)
        job.cancel()
        job.invokeOnCompletion { synchronized(queueLock) { stopping.remove(job) } }
    }

    /** Snapshot of jobs currently stopping; callers must hold [queueLock]. */
    internal fun stoppingSnapshot(): List<Job> = stopping.toList()

    internal suspend fun awaitStopping(jobs: List<Job> = synchronized(queueLock) { stopping.toList() }) {
        val waiter = currentCoroutineContext().job
        for (stoppingJob in jobs) {
            stoppingWaiters[waiter] = stoppingJob
            try {
                stoppingJob.join()
            } finally {
                stoppingWaiters.remove(waiter, stoppingJob)
            }
        }
    }

    internal fun isWaitingFor(waiter: Job, stoppingJob: Job): Boolean {
        fun contains(root: Job, target: Job): Boolean = root === target || root.children.any { contains(it, target) }
        return stoppingWaiters.any { (waiting, target) -> target === stoppingJob && contains(waiter, waiting) }
    }

    internal fun stoppingCount(): Int = synchronized(queueLock) { stopping.size }

    fun deleteCancelledTempFiles(stopped: List<Job>, removed: List<Download>): Job = scope.launch {
        // After the cancelled download let go of its files
        awaitStopping(stopped)
        if (!paths.isStorageAvailable) return@launch
        removed.groupBy { it.manga.id }.forEach { (id, downloads) ->
            // Under the entry's lock, re-reading the queue: a chapter queued again since keeps its
            // unfinished download, which the new download resumes
            entryLock(id).withLock {
                val queued = queuedChapterIds()
                downloads.filterNot { it.chapter.id in queued }.forEach { d ->
                    val dir = folderOps.resolveMangaDir(d.manga)
                    val name = paths.chapterDirName(d.chapter)
                    listOf(name + DownloadPaths.TMP_SUFFIX, "$name.cbz" + DownloadPaths.TMP_SUFFIX).forEach {
                        SafeDelete.delete(root, File(dir, it), minDepth = 3)
                    }
                    folderOps.invalidate(dir)
                }
            }
        }
    }

    /**
     * Deletes the downloads of [chapters] (each name they may have, and unfinished downloads), then
     * the entry's folder when nothing is left in it. Never touches other files.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun delete(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        paths.requireStorage()
        cancelChapters(chapters.map { it.id }.toSet())
        // A cancelled download may still be writing its last file; delete after it stopped, or the
        // chapter would come back
        awaitStopping()
        val others = coOwnerChapters(manga, chapters)
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters, others) }
    }

    /**
     * The chapters of the other entries that share [manga]'s folder, read before any file is deleted;
     * none when [chapters] have nothing on disk (no lookup then).
     */
    private suspend fun coOwnerChapters(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val lookup = coOwnerLookup() ?: return emptyList()
        val anything = folderOps.dirsOf(manga).any { dir ->
            dir.isDirectory && chapters.any { folderOps.namesOnDisk(dir, it).isNotEmpty() }
        }
        if (!anything) return emptyList()
        return lookup(manga).filter { (other, _) -> other.id != manga.id }.flatMap { it.second }
    }

    /**
     * Under the entry's lock: [chapters]' files in each of the entry's folders, then the folder if
     * empty. A name a chapter of another entry in the folder ([others]) also has is that entry's
     * download too (titles that give the same folder name, chapter names without the URL hash): kept.
     */
    private fun deleteChapterFiles(manga: Manga, chapters: List<Chapter>, others: List<Chapter> = emptyList()) {
        folderOps.dirsOf(manga).forEach { dir ->
            index.invalidate(dir)
            if (!dir.isDirectory) return@forEach
            val keep = folderOps.namesOnDisk(dir, others)
            chapters.flatMap { folderOps.namesOnDisk(dir, it) }.distinct().filter { it !in keep }.forEach { name ->
                if (!SafeDelete.delete(root, File(dir, name), minDepth = 3)) {
                    logcat(LogPriority.WARN) { "Could not delete the download $name of ${manga.title}" }
                }
            }
            SafeDelete.deleteIfEmpty(root, dir, minDepth = 2)
            folderOps.invalidate(dir)
        }
    }

    /**
     * Deletes the downloads of [chapters], all of an entry's chapters, and takes them out of the
     * queue, as Android's deleteManga(removeQueued = true). Unlike Android it deletes only those
     * chapters' files (and unfinished downloads of them): an entry whose title gives the same folder
     * name as another entry's, or files copied in by hand, are kept. The folder, and then the
     * source's folder, go once empty.
     *
     * @throws zoukan.desktop.storage.StorageUnavailableException when the storage folder is missing
     */
    suspend fun deleteManga(manga: Manga, chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        paths.requireStorage()
        cancelSeries(manga.id)
        awaitStopping()
        val others = coOwnerChapters(manga, chapters)
        entryLock(manga.id).withLock { deleteChapterFiles(manga, chapters, others) }
        SafeDelete.deleteIfEmpty(root, paths.sourceDir(manga.source), minDepth = 1)
    }

    /**
     * Which of [chapters] the automatic deletions may delete, as Android's getChaptersToDelete:
     * read chapters of an entry in an excluded category stay ("Exclude categories"), and so do
     * bookmarked chapters unless "Allow deleting bookmarked chapters" is on.
     */
    suspend fun chaptersToDelete(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        val excluded = preferences.removeExcludeCategories().get().mapNotNull(String::toLongOrNull).toSet()
        val categories = try {
            getCategories.await(manga.id).map { it.id }.ifEmpty { listOf(0L) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without the categories the "Exclude categories" protection can't be checked; delete nothing
            logcat(LogPriority.WARN, e) { "Reading the categories of ${manga.title} failed; not deleting its chapters" }
            return emptyList()
        }
        val allowed = if (categories.any { it in excluded }) chapters.filterNot { it.read } else chapters
        return if (preferences.removeBookmarkedChapters().get()) allowed else allowed.filterNot { it.bookmark }
    }

    /**
     * Deletes the downloads of read [chapters] the way an automatic deletion does (see
     * [chaptersToDelete]), each from the entry it belongs to, which for a merged entry is a child.
     */
    suspend fun deleteRead(chapters: List<Chapter>) {
        // An automatic deletion: with the storage drive unplugged there is nothing to delete, and
        // marking the chapters read must not fail because of it
        if (!paths.isStorageAvailable) {
            logcat(LogPriority.WARN) { "Not deleting read chapters: the storage folder is not available" }
            return
        }
        chapters.filter { it.read }.groupBy { it.mangaId }.forEach { (mangaId, list) ->
            val manga = getManga.await(mangaId) ?: return@forEach
            val queued = queuedChapterIds()
            val toDelete = chaptersToDelete(manga, list).filter { folderOps.isDownloaded(manga, it) || it.id in queued }
            if (toDelete.isNotEmpty()) delete(manga, toDelete)
        }
    }

    /**
     * After chapters are marked as read by hand (not by reading): deletes their downloads when
     * "After manually marked as read" is on, as Android's SetReadStatus.
     */
    suspend fun afterMarkedRead(chapters: List<Chapter>) {
        if (!preferences.removeAfterMarkedAsRead().get()) return
        deleteRead(chapters.map { it.copy(read = true) })
    }

    /**
     * SY's "Clean up downloaded chapters" (Settings > Advanced), first step: finds what it would
     * delete and deletes nothing.
     */
    suspend fun planCleanup(
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        allManga: suspend () -> List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
        protectedIds: Set<Long> = emptySet(),
    ): CleanupPlan = withContext(Dispatchers.IO) {
        paths.requireStorage()
        val mangas = allManga()
        val busy = queuedMangaIds()
        val chapterCache = HashMap<Long, List<Chapter>>()
        val knownChapters: suspend (Long) -> List<Chapter> = { id -> chapterCache.getOrPut(id) { chaptersOf(id) } }
        // Every entry by the source folder its downloads go to: other sources (even ones not
        // installed) whose folder has the same name count as owners too
        val bySourceDir = mangas.groupBy { paths.sourceDir(it.source) }
        val items = ArrayList<CleanupItem>()
        sourceManager.getOnlineSources().map { paths.sourceDir(it.id) }.distinct().forEach { sourceDir ->
            val sourceMangas = bySourceDir[sourceDir].orEmpty()
            // Nothing in the database for this folder: it is not the desktop's to judge
            if (sourceMangas.isEmpty()) return@forEach
            // A linked folder (or a Windows junction) is not walked into: what it points to is not the desktop's
            val folders = sourceDir.listFiles()
                ?.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !SafeDelete.isLink(it.toPath()) }
                .orEmpty()
            if (folders.isEmpty()) return@forEach
            val byFolder = HashMap<String, MutableSet<Manga>>()
            sourceMangas.forEach { m ->
                paths.mangaDirNames(m.ogTitle).forEach { name -> byFolder.getOrPut(index.key(root, name)) { LinkedHashSet() } += m }
            }
            folders.forEach folder@{ folder ->
                val owners = byFolder[index.key(root, folder.name)]?.toList()
                if (owners == null) {
                    // Not orphaned when it holds chapters of an entry whose folder has another name (the
                    // source changed the title, and a download may have used the old one since)
                    val owner = renamedOwner(folder, sourceMangas, knownChapters)
                    if (owner != null) {
                        logcat(LogPriority.WARN) { "Kept ${folder.name}: its chapters belong to ${owner.ogTitle}, whose folder has another name" }
                    } else {
                        items += CleanupItem(folder, CleanupReason.ORPHAN_FOLDER, emptySet(), sizeOf(folder))
                    }
                    return@folder
                }
                if (owners.any { it.id in busy }) return@folder
                items += planFolder(folder, owners, knownChapters, removeRead, removeNonFavorite, protectedIds)
            }
        }
        CleanupPlan(
            items,
            toTrash = SafeDelete.canTrash(root),
            recheck = CleanupRecheck(removeRead, removeNonFavorite, protectedIds, allManga, chaptersOf),
        )
    }

    /** The entries of [mangas] whose download folder is [folder] (a folder in a source's folder). */
    private fun ownersOf(folder: File, mangas: List<Manga>): List<Manga> {
        val key = index.key(root, folder.name)
        return mangas.filter { m ->
            paths.sourceDir(m.source) == folder.parentFile && paths.mangaDirNames(m.ogTitle).any { index.key(root, it) == key }
        }
    }

    private suspend fun planFolder(
        folder: File,
        owners: List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        protectedIds: Set<Long>,
    ): List<CleanupItem> {
        index.invalidate(folder)
        val ownerIds = owners.mapTo(LinkedHashSet()) { it.id }
        val chapters = owners.flatMap { chaptersOf(it.id) }
        val items = ArrayList<CleanupItem>()
        // Unfinished downloads of any chapter, known or not: the queue of these entries is empty
        index.names(root, folder).filter { it.endsWith(DownloadPaths.TMP_SUFFIX) }.forEach { name ->
            items += CleanupItem(File(folder, name), CleanupReason.UNFINISHED, ownerIds, sizeOf(File(folder, name)))
        }
        val notInLibrary = removeNonFavorite && owners.all { !it.favorite && it.id !in protectedIds }
        val toDelete: List<Chapter> = when {
            notInLibrary -> chapters
            removeRead -> chapters.filter { it.read }
            else -> emptyList()
        }
        if (toDelete.isEmpty()) return items
        // A name an unread chapter of another entry in this folder also has is that entry's too: kept
        val keep = if (notInLibrary) {
            emptySet()
        } else {
            folderOps.namesOnDisk(folder, chapters.filterNot { it.read })
        }
        val reason = if (notInLibrary) CleanupReason.NOT_IN_LIBRARY else CleanupReason.READ
        toDelete.flatMap { folderOps.namesOnDisk(folder, it) }.distinct()
            .filter { it !in keep && !it.endsWith(DownloadPaths.TMP_SUFFIX) }
            .forEach { name -> items += CleanupItem(File(folder, name), reason, ownerIds, sizeOf(File(folder, name))) }
        return items
    }

    /**
     * Second step: deletes what [plan] lists, except orphaned folders not in [orphansToDelete], each
     * through [SafeDelete], into the system trash when [toTrash].
     */
    suspend fun applyCleanup(plan: CleanupPlan, orphansToDelete: Set<File> = emptySet(), toTrash: Boolean = false): Int =
        applyCleanupResult(plan, orphansToDelete, toTrash).deleted

    /** [applyCleanup], with what it could not delete and what it left because it changed. */
    suspend fun applyCleanupResult(
        plan: CleanupPlan,
        orphansToDelete: Set<File> = emptySet(),
        toTrash: Boolean = false,
    ): CleanupResult = withContext(Dispatchers.IO) {
        paths.requireStorage()
        val recheck = plan.recheck
        // The database as it is now, read once
        val mangas = recheck?.allManga?.invoke()
        var deleted = 0
        var notDeleted = 0
        var skipped = 0
        plan.orphans.filter { it.file in orphansToDelete }.forEach { item ->
            if (recheck != null && mangas != null && hasOwnerNow(item.file, mangas, recheck.chaptersOf)) {
                logcat(LogPriority.WARN) { "Kept ${item.file.name}: an entry uses that folder now" }
                skipped++
                return@forEach
            }
            if (SafeDelete.delete(root, item.file, minDepth = 2, toTrash = toTrash)) deleted++ else notDeleted++
            folderOps.invalidate(item.file)
        }
        plan.others.groupBy { it.file.parentFile }.forEach { (folder, items) ->
            val owners = mangas?.let { ownersOf(folder, it) }
            val ids = (items.flatMap { it.owners } + owners.orEmpty().map { it.id }).distinct().sorted()
            // The queue is read under the entries' locks, right before deleting: an entry queued since
            // the plan is left alone, and a download of it waits for this to finish
            withEntryLocks(ids) {
                if (queuedMangaIds().any { it in ids }) {
                    skipped += items.size
                    return@withEntryLocks
                }
                // What the plan would list now, with each entry's library and read state read again
                val still = if (recheck != null && owners != null) {
                    if (owners.isEmpty()) {
                        emptySet()
                    } else {
                        val chapters = HashMap<Long, List<Chapter>>()
                        planFolder(
                            folder,
                            owners,
                            { id -> chapters.getOrPut(id) { recheck.chaptersOf(id) } },
                            recheck.removeRead,
                            recheck.removeNonFavorite,
                            recheck.protectedIds,
                        ).mapTo(HashSet()) { it.file }
                    }
                } else {
                    null
                }
                items.forEach { item ->
                    if (still != null && item.file !in still) {
                        logcat(LogPriority.INFO) { "Kept ${item.file.name}: it changed since the clean-up was planned" }
                        skipped++
                        return@forEach
                    }
                    if (SafeDelete.delete(root, item.file, minDepth = 3, toTrash = toTrash)) deleted++ else notDeleted++
                }
                if (SafeDelete.deleteIfEmpty(root, folder, minDepth = 2)) deleted++
            }
            folderOps.invalidate(folder)
        }
        CleanupResult(deleted, notDeleted, skipped)
    }

    /** Runs [block] holding the entry locks of [ids], taken in the order given (ascending ids). */
    private suspend fun withEntryLocks(ids: List<Long>, block: suspend () -> Unit) {
        if (ids.isEmpty()) return block()
        entryLock(ids.first()).withLock { withEntryLocks(ids.drop(1), block) }
    }

    /** Whether an orphaned [folder] planned for deletion is an entry's folder now (see [planCleanup]). */
    private suspend fun hasOwnerNow(folder: File, mangas: List<Manga>, chaptersOf: suspend (Long) -> List<Chapter>): Boolean {
        if (ownersOf(folder, mangas).isNotEmpty()) return true
        val sourceMangas = mangas.filter { paths.sourceDir(it.source) == folder.parentFile }
        return renamedOwner(folder, sourceMangas, chaptersOf) != null
    }

    /**
     * Plans and runs a clean-up in one go, leaving orphaned folders alone (the settings screen shows
     * the plan first, see [planCleanup] and [applyCleanup]).
     */
    suspend fun cleanup(
        removeRead: Boolean,
        removeNonFavorite: Boolean,
        allManga: suspend () -> List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
    ): Int = applyCleanup(planCleanup(removeRead, removeNonFavorite, allManga, chaptersOf))

    private fun sizeOf(file: File): Long = try {
        Files.walk(file.toPath()).use { s ->
            s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { it.toFile().length() }.sum()
        }
    } catch (_: IOException) {
        0L
    } catch (_: UncheckedIOException) {
        0L
    }

    /**
     * An entry of the source that has a chapter's download in [folder], which is not the folder its
     * title names, and not in its own folder (which may or may not exist).
     */
    private suspend fun renamedOwner(
        folder: File,
        sourceMangas: List<Manga>,
        chaptersOf: suspend (Long) -> List<Chapter>,
    ): Manga? {
        index.invalidate(folder)
        if (index.names(root, folder).isEmpty()) return null
        return sourceMangas.firstOrNull { manga ->
            // A copy of a chapter the entry's own folder also has is a leftover, not the only download
            chaptersOf(manga.id).any { chapter ->
                index.matching(root, folder, paths.validChapterNames(chapter)).isNotEmpty() && !folderOps.isDownloaded(manga, chapter)
            }
        }
    }
}
