package zoukan.desktop.ui.manga

import exh.source.isEhBasedManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.AppScope
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalSource

/**
 * Chapter state mutations and download queuing for the entry screen: read/bookmark updates,
 * downloaded-ID resolution, chapter download queueing/cancellation, and auto-downloading newly
 * fetched chapters.
 */
class MangaChapterActions(
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val chaptersByEntry: ChaptersByEntry = Injekt.get<ChaptersByEntry>().cached(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    private val appScope: CoroutineScope = AppScope,
) {
    val downloadChanges: Flow<Int> get() = downloadManager.changes
    val downloadQueue: Flow<List<Download>> get() = downloadManager.queue

    suspend fun downloadedIds(manga: Manga, chapters: List<Chapter>): Set<Long> =
        withContext(Dispatchers.IO) {
            chaptersByEntry.group(manga, chapters).flatMapTo(HashSet()) { (entry, list) ->
                downloadManager.downloadedIds(entry, list)
            }
        }

    suspend fun enqueue(manga: Manga, chapters: List<Chapter>) {
        downloadManager.enqueueAll(chaptersByEntry.group(manga, chapters))
    }

    fun cancelDownload(chapter: Chapter) = downloadManager.cancel(setOf(chapter.id))

    /** Queues the new chapters the download settings pick, as Android's downloadNewChapters. */
    fun downloadNewChapters(scope: CoroutineScope, manga: Manga, chapters: List<Chapter>) {
        scope.launch(NonCancellable) {
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)
            if (chaptersToDownload.isNotEmpty() && !manga.isEhBasedManga()) {
                downloadManager.enqueueAll(chaptersByEntry.group(manga, chaptersToDownload))
            }
        }
    }

    suspend fun markRead(chapters: List<Chapter>, read: Boolean) {
        updateChapter.awaitAll(
            chapters.map { ChapterUpdate(id = it.id, read = read, lastPageRead = if (read) null else 0L) },
        )
        if (read) {
            appScope.launch { downloadManager.afterMarkedRead(chapters.filter { !it.read }) }
        }
    }

    suspend fun setBookmark(chapters: List<Chapter>, bookmark: Boolean) {
        updateChapter.awaitAll(chapters.map { ChapterUpdate(id = it.id, bookmark = bookmark) })
    }

    fun selectUnreadForDownload(chapters: List<Chapter>, downloaded: Set<Long>, count: Int?): List<Chapter> {
        val unread = chapters.sortedByDescending { it.sourceOrder }
            .filter { !it.read && it.id !in downloaded }
        return if (count == null) unread else unread.take(count)
    }

    fun selectBookmarkedForDownload(chapters: List<Chapter>, downloaded: Set<Long>): List<Chapter> =
        chapters.sortedByDescending { it.sourceOrder }
            .filter { it.bookmark && it.id !in downloaded }

    fun isLocal(manga: Manga): Boolean = manga.source == LocalSource.ID
}
