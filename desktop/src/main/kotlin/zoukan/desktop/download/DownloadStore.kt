package zoukan.desktop.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektionException
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Queue serialization, atomic persistence to `downloads-queue.json`, unreadable-queue quarantine,
 * and startup recovery with held-item retry when the database cannot be read at launch.
 */
internal class DownloadStore(
    private val dirs: ZoukanDirs,
    private val scope: CoroutineScope,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val canDownload: (Manga) -> Boolean,
    private val queueLock: Any,
    private val queue: MutableStateFlow<List<Download>>,
    private val onRestored: () -> Unit = {},
    private val restoreRepositoriesProvider: () -> Pair<MangaRepository, ChapterRepository>? = ::defaultRestoreRepositories,
) {
    @Serializable
    internal data class SavedDownload(val mangaId: Long, val chapterId: Long)

    private val json = Json { ignoreUnknownKeys = true }
    internal val queueFile: File get() = File(dirs.root, "downloads-queue.json")
    private val mutex = Mutex()

    /**
     * The saved downloads [restore] has not put in the queue yet, or could not read from the
     * database: every save writes them after the queue, so they are restored when the queue next
     * starts ([restoreHeld]) or, failing that, at the next start of the app.
     */
    @Volatile
    private var held: List<SavedDownload> = emptyList()

    /** Whether [held] has downloads a failed database read left unread, which [retryHeldIfNeeded] reads again. */
    @Volatile
    private var heldUnread = false

    /** Whether a [restoreHeld] is running, so a second start does not read the same downloads again. */
    private val heldRetry = AtomicBoolean()

    /** Whether [restore] has read the saved file; until then, every save keeps what it holds. */
    @Volatile
    private var restoreBegun = false

    /** The entries and chapters as the database reads them; null when not registered (tests without one). */
    private val restoreRepositories by lazy(restoreRepositoriesProvider)

    fun heldChapterIds(): List<Long> = held.map { it.chapterId }

    /**
     * Removes [chapterIds] from [held] so cancelled items are not restored later.
     * Callers must hold [queueLock].
     *
     * @return whether any held item was removed
     */
    fun cancelHeld(chapterIds: Set<Long>): Boolean {
        val heldBefore = held
        if (heldBefore.isEmpty()) return false
        held = heldBefore.filterNot { it.chapterId in chapterIds }
        if (held.isEmpty()) heldUnread = false
        return held.size != heldBefore.size
    }

    fun save() {
        scope.launch { saveNow() }
    }

    internal suspend fun saveNow() {
        mutex.withLock {
            runCatching {
                // The saved downloads a restore has not read yet, or could not read, stay saved.
                // Read before the queue: restore() adds to the queue first and then lets go of them,
                // so each is in one or the other. Before restore() the saved file is all of them;
                // one that cannot be read is left for restore() to keep aside
                val kept = if (restoreBegun) held else peekSavedQueue() ?: return@runCatching
                // The queue as it is now, not as it was at the call: saves start on IO threads
                // and can take the lock out of order, so an older list must never be written last
                val queued = queue.value.map { SavedDownload(it.manga.id, it.chapter.id) }
                val ids = queued.mapTo(HashSet()) { it.chapterId }
                val saved = queued + kept.filterNot { it.chapterId in ids }
                val tmp = File(queueFile.path + ".tmp")
                tmp.parentFile?.mkdirs()
                tmp.writeText(json.encodeToString(saved))
                try {
                    Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), queueFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure { logcat(LogPriority.WARN, it) { "Saving the download queue failed" } }
        }
    }

    /** The saved queue as it is, empty when there is none; null when it cannot be read. Changes nothing. */
    internal fun peekSavedQueue(): List<SavedDownload>? {
        val file = queueFile
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<SavedDownload>>(file.readText())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is SerializationException && e !is IllegalArgumentException && e !is IOException) throw e
            null
        }
    }

    /**
     * The saved queue, or null when there is none (first start) or it cannot be read. A file that
     * cannot be read is moved to `downloads-queue.unreadable-<time>.json`, never replaced or
     * deleted, so the next save does not overwrite it, and the user is told.
     */
    internal fun readSavedQueue(): List<SavedDownload>? {
        val file = queueFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<List<SavedDownload>>(file.readText())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is SerializationException && e !is IllegalArgumentException && e !is IOException) throw e
            val keptName = keepUnreadableQueue(file)
            logcat(LogPriority.WARN, e) { "The saved download queue could not be read; kept as $keptName" }
            Notifications.post(
                localized(DMR.strings.download_queue_unreadable),
                localized(DMR.strings.download_queue_unreadable_kept, keptName.orEmpty()),
                Notifications.Kind.WARNING,
            )
            null
        }
    }

    private fun keepUnreadableQueue(file: File): String? {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        for (n in 0..99) {
            val name = "downloads-queue.unreadable-$stamp${if (n == 0) "" else "-$n"}.json"
            try {
                // No REPLACE_EXISTING: a kept file is never overwritten
                Files.move(file.toPath(), File(file.parentFile, name).toPath())
                return name
            } catch (_: FileAlreadyExistsException) {
                continue
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Could not keep the unreadable download queue aside" }
                return null
            }
        }
        return null
    }

    /**
     * Loads the saved queue and starts it. Called once at start-up. A download whose entry or chapter
     * was deleted is dropped; when the database cannot be read, the downloads from there on stay in
     * the saved file (see [held]) and the user is told. They are read again when the queue next
     * starts, in this run.
     */
    suspend fun restore(start: Boolean = true) {
        val saved = readSavedQueue().orEmpty()
        // Until they are in the queue, saves keep them (also when the restore is cancelled)
        held = saved
        restoreBegun = true
        if (saved.isEmpty()) return
        val (downloads, unread) = readSaved(saved)
        synchronized(queueLock) {
            // Still held: not cancelled (cancel() takes it out of [held]) while the database was read
            val stillHeld = held.mapTo(HashSet()) { it.chapterId }
            queue.update { q ->
                val queued = q.mapTo(HashSet()) { it.chapter.id }
                q + downloads.distinctBy { it.chapter.id }.filter { d -> d.chapter.id in stillHeld && d.chapter.id !in queued }
            }
            // After the queue has them, so a save in between writes each download at least once
            held = unread.filter { it.chapterId in stillHeld }
            heldUnread = held.isNotEmpty()
        }
        if (unread.isNotEmpty()) {
            Notifications.post(
                localized(DMR.strings.download_queue_restore_failed),
                localized(DMR.strings.download_queue_restore_failed_kept),
                Notifications.Kind.WARNING,
            )
        }
        // Not start(): it would read the held downloads again at once, with the database that has
        // just failed. They are read again when the queue next starts
        if (start) onRestored()
    }

    /** Retries restoring downloads that were held back by a failed database read at launch. */
    fun retryHeldIfNeeded() {
        if (heldUnread && heldRetry.compareAndSet(false, true)) {
            scope.launch {
                try {
                    restoreHeld()
                } finally {
                    heldRetry.set(false)
                }
            }
        }
    }

    /**
     * Reads the downloads [restore] held back again, as the queue starts: the database may read
     * again by now. Those it reads join the queue, after what is queued and never twice; a held
     * download the user cancelled meanwhile stays out. What still cannot be read stays held, and the
     * user, told once at start-up, is not told again.
     */
    private suspend fun restoreHeld() {
        val snapshot = held
        if (snapshot.isEmpty()) return
        val (downloads, unread) = readSaved(snapshot)
        val unreadIds = unread.mapTo(HashSet()) { it.chapterId }
        val added = synchronized(queueLock) {
            // Still held: not cancelled (cancel() takes it out of [held]) while the database was read
            val stillHeld = held.mapTo(HashSet()) { it.chapterId }
            val adding = downloads.distinctBy { it.chapter.id }.filter { it.chapter.id in stillHeld }
            queue.update { q ->
                val queued = q.mapTo(HashSet()) { it.chapter.id }
                q + adding.filterNot { d -> d.chapter.id in queued }
            }
            // After the queue has them, so a save in between writes each download at least once
            held = held.filter { it.chapterId in unreadIds }
            heldUnread = held.isNotEmpty()
            adding
        }
        if (unread.isEmpty()) logcat(LogPriority.INFO) { "The held download queue was restored" }
        save()
        if (added.isNotEmpty()) onRestored()
    }

    /**
     * The downloads of [saved] as the database reads them, in order, and the saved downloads from
     * the first read that failed on (empty when every read worked). A download whose entry or chapter
     * was deleted, or whose source can no longer download, is dropped.
     */
    private suspend fun readSaved(saved: List<SavedDownload>): Pair<List<Download>, List<SavedDownload>> {
        val mangas = HashMap<Long, Manga?>()
        val downloads = ArrayList<Download>()
        for ((i, s) in saved.withIndex()) {
            val download = try {
                val chapter = readSavedChapter(s) ?: continue
                val manga = mangas.getOrPut(s.mangaId) { readSavedManga(s, chapter) } ?: continue
                Download(manga, chapter)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "The saved download queue could not be read from the database" }
                return downloads to saved.subList(i, saved.size).toList()
            }
            // As Android's DownloadStore.restore: a chapter whose source can no longer download is dropped
            if (canDownload(download.manga)) downloads += download
        }
        return downloads to emptyList()
    }

    /**
     * [saved]'s chapter, or null when it was deleted. Unlike [GetChapter], which returns null on a
     * failed read too, a read that fails throws, so the saved download is not taken for deleted.
     */
    private suspend fun readSavedChapter(saved: SavedDownload): Chapter? {
        val chapters = restoreRepositories?.second ?: return getChapter.await(saved.chapterId)
        return chapters.getChapterById(saved.chapterId)
    }

    /**
     * [chapter]'s entry. A chapter is deleted with its entry, so the entry of a chapter that exists
     * exists too, and a read that fails throws. A saved entry that is not the chapter's is looked up
     * as before.
     */
    private suspend fun readSavedManga(saved: SavedDownload, chapter: Chapter): Manga? {
        val mangas = restoreRepositories?.first
        if (mangas == null || chapter.mangaId != saved.mangaId) return getManga.await(saved.mangaId)
        return mangas.getMangaById(saved.mangaId)
    }

    companion object {
        private fun defaultRestoreRepositories(): Pair<MangaRepository, ChapterRepository>? = try {
            Injekt.get<MangaRepository>() to Injekt.get<ChapterRepository>()
        } catch (_: InjektionException) {
            null
        }
    }
}
