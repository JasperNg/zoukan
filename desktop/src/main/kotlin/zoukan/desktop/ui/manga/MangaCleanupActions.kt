package zoukan.desktop.ui.manga

import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.backup.SnapshotReason
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.local.LocalSource
import zoukan.i18n.desktop.DMR
import java.io.File

/**
 * Destructive and filesystem cleanup operations for an entry on the manga screen: deleting chapter
 * downloads, clearing downloaded data / database chapters (guarded by [SafetySnapshot]), trashing
 * confirmed local source folders, and opening the entry's download/local folder.
 */
class MangaCleanupActions(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val chaptersByEntry: ChaptersByEntry = Injekt.get<ChaptersByEntry>().cached(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadPaths: Lazy<DownloadPaths> = injectLazy(),
    private val getChaptersByMangaId: Lazy<GetChaptersByMangaId> = injectLazy(),
    private val getMergedMangaById: Lazy<GetMergedMangaById> = lazy { GetMergedMangaById(Injekt.get()) },
    private val deleteChapters: Lazy<DeleteChapters> = lazy { DeleteChapters(Injekt.get()) },
    private val safetySnapshot: SafetySnapshot = SafetySnapshot.forApp(),
    private val snapshotPrompt: SnapshotFailedPrompt = SnapshotFailedPrompt {
        zoukan.desktop.ui.common.snapshotFailedPrompt.continueWithout(it)
    },
    private val appScope: CoroutineScope = AppScope,
) {
    /**
     * KMK's Open folder: the entry's download folder (a local entry's own folder) in Explorer or
     * Finder. Nothing opens when the folder does not exist, as on Android.
     */
    fun openMangaFolder(source: eu.kanade.tachiyomi.source.Source?, manga: Manga?) {
        if (manga == null || source == null || source is StubSource) return
        appScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dir = mangaFolder(manga) ?: return@withContext
                    if (!dir.isDirectory) return@withContext
                    SystemEffects.current.openFolder(dir)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    Toasts.show(localized(KMR.strings.error_opening_folder))
                }
            }
        }
    }

    /** Where the entry's chapters are on disk: KMK's DownloadProvider finds a local entry in the local folder. */
    fun mangaFolder(manga: Manga): File? = if (manga.source == LocalSource.ID) {
        (sourceManager.get(LocalSource.ID) as? LocalSource)?.mangaDir(manga.url)
    } else {
        downloadPaths.value.mangaDir(manga)
    }

    /** Deletes downloaded files for specific [chapters] of [manga], grouped across merged entries. */
    suspend fun deleteChapterDownloads(manga: Manga, chapters: List<Chapter>) {
        chaptersByEntry.group(manga, chapters).forEach { (entry, list) ->
            downloadManager.delete(entry, list)
        }
    }

    /**
     * KMK's Clear manga: deletes the entry's downloaded data (for a merged entry, each merged
     * entry's), and its chapters with their history from the database.
     */
    fun clearManga(manga: Manga?, chapters: List<Chapter>, deleteDownload: Boolean, removeChapters: Boolean) {
        if (deleteDownload) deleteDownloadedData(manga, chapters)
        if (removeChapters) removeChaptersDatabase(chapters)
    }

    /**
     * Deletes the downloads of the entry, or of each online entry a merged entry merges. A local
     * entry has no downloads: its folder holds the user's own files, which only [trashLocalFolders]
     * touches, after the user's second confirmation.
     */
    private fun deleteDownloadedData(manga: Manga?, shown: List<Chapter>) {
        val target = manga ?: return
        appScope.launch {
            try {
                if (target.source == MERGED_SOURCE_ID) {
                    mergedEntries(target).forEach { (entry, _) ->
                        // A cancel stops before the next entry's folder, never keeps deleting
                        ensureActive()
                        deleteMangaDownloads(entry, shown)
                    }
                } else {
                    deleteMangaDownloads(target, shown)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                // The storage drive unplugged, a file that cannot be deleted: said, not only logged
                logcat(LogPriority.ERROR, e) { "Deleting the downloads of ${target.title} failed" }
                Toasts.error(localized(DMR.strings.screen_action_failed_delete_downloads), e)
            }
        }
    }

    /** Android's DownloadManager.deleteManga: the queued chapters and the entry's folder. */
    private suspend fun deleteMangaDownloads(manga: Manga, shown: List<Chapter>) {
        // A local entry's folder holds the user's own files: only trashLocalFolders touches it
        if (manga.source == LocalSource.ID) return
        // Only this entry's chapters are deleted: another entry may share the folder
        val chapters = (shown.filter { it.mangaId == manga.id } + getChaptersByMangaId.value.await(manga.id))
            .distinctBy { it.id }
        // One entry's downloads are deleted whole: a cancel waits for this entry and stops before the next
        withContext(NonCancellable) { downloadManager.deleteManga(manga, chapters) }
    }

    /** The folders in the local source folder holding the user's own files for [manga] (or its local parts). */
    suspend fun localFolders(manga: Manga?): List<File> = withContext(Dispatchers.IO) {
        val target = manga ?: return@withContext emptyList()
        val entries = if (target.source == MERGED_SOURCE_ID) {
            mergedEntries(target).map { it.first }
        } else {
            listOf(target)
        }
        entries.filter { it.source == LocalSource.ID }
            .mapNotNull { mangaFolder(it) }
            .filter { it.isDirectory }
    }

    /**
     * Moves [confirmed], the folders the user agreed to in the local-files question, to the system
     * trash, returning true if at least one folder was trashed.
     */
    suspend fun trashLocalFolders(manga: Manga?, confirmed: List<File>): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            var moved = false
            localFolders(manga).filter { it in confirmed }.forEach { dir ->
                if (systemTrash(dir)) {
                    moved = true
                } else {
                    Toasts.show(localized(DMR.strings.local_files_not_trashed, dir.absolutePath))
                }
            }
            moved
        }

    private fun removeChaptersDatabase(chapters: List<Chapter>) {
        val ids = chapters.map { it.id }
        if (ids.isEmpty()) return
        appScope.launch {
            try {
                // The chapters' read state and history go with them: a safety backup first, or the user's go-ahead
                if (!safetySnapshot.guard(SnapshotReason.CLEAR_CHAPTERS, snapshotPrompt)) return@launch
                deleteChapters.value.await(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private suspend fun mergedEntries(manga: Manga): List<Pair<Manga, eu.kanade.tachiyomi.source.Source>> {
        if (manga.source != MERGED_SOURCE_ID) return emptyList()
        return withContext(Dispatchers.IO) {
            getMergedMangaById.value.await(manga.id)
                .filterNot { it.source == MERGED_SOURCE_ID }
                .map { it to sourceManager.getOrStub(it.source) }
        }
    }
}

/** Moves a folder to the system trash; false when it could not (journeys replace it, so no test machine's trash is used). */
internal var systemTrash: (File) -> Boolean = SystemTrash::move

private object SystemTrash {
    fun move(file: File): Boolean = try {
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        desktop != null && desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && desktop.moveToTrash(file)
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
        false
    }
}
