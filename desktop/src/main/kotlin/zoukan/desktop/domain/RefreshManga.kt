package zoukan.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.MergedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalSource
import java.time.Instant

/**
 * Fetches an entry's details and chapters from its source and saves them, like the Android app's
 * UpdateMangaFromRemote.
 */
class RefreshManga(
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val syncChapters: SyncChapters,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
) {
    data class Result(val manga: Manga, val newChapters: List<Chapter>)

    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = true,
        fetchChapters: Boolean = true,
        // ZOUKAN -->
        source: Source? = null,
        /** For merged entries: download the new chapters of references set to download */
        downloadNew: Boolean = false,
        throttle: suspend () -> Unit = {},
        // ZOUKAN <--
    ): Result = withContext(Dispatchers.IO) {
        @Suppress("NAME_SHADOWING")
        val source = source ?: sourceManager.get(manga.source)
            ?: error("The source of ${manga.title} is not installed")
        val chapters = chapterRepository.getChapterByMangaId(manga.id).sortedBy { it.sourceOrder }
        // ZOUKAN -->
        // As UpdateMangaFromRemote: E-Hentai throttles its chapter requests, and a merged entry
        // updates each of its references
        val update = if (source is EHentai) {
            source.getMangaUpdate(manga.toSManga(), chapters.map { it.toSChapter() }, fetchDetails, fetchChapters, throttle)
        } else {
            source.getMangaUpdate(
                manga = manga.toSManga(),
                chapters = chapters.map { it.toSChapter() },
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
            )
        }
        if (fetchDetails) updateDetails(manga, update.manga)
        val newChapters = when {
            !fetchChapters -> emptyList()
            source is MergedSource -> source.fetchChaptersAndSync(manga, downloadChapters = downloadNew)
            else -> syncChapters.await(update.chapters, manga, source)
        }
        // ZOUKAN <--
        Result(mangaRepository.getMangaById(manga.id), newChapters)
    }

    private suspend fun updateDetails(local: Manga, remote: SManga) {
        val remoteTitle = try {
            remote.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }
        var title = remoteTitle.takeIf {
            it.isNotEmpty() && (!local.favorite || libraryPreferences.updateMangaTitles().get())
        }
        // As Android's renameManga: the download folder follows the title, and a title whose folder
        // cannot move stays as it is, so the entry keeps its downloads
        var renamedTo: String? = null
        if (title != null && title != local.ogTitle && local.source != LocalSource.ID) {
            if (downloadManager.renameManga(local, title)) {
                renamedTo = title
            } else {
                logcat(LogPriority.WARN) { "Keeping the title ${local.ogTitle}: its download folder could not move to $title" }
                title = null
            }
        }
        val thumbnailUrl = remote.thumbnail_url?.takeIf { it.isNotEmpty() }
        val saved = try {
            mangaRepository.update(
                MangaUpdate(
                    id = local.id,
                    title = title,
                    coverLastModified = if (thumbnailUrl != null && thumbnailUrl != local.ogThumbnailUrl) {
                        Instant.now().toEpochMilli()
                    } else {
                        null
                    },
                    author = remote.author,
                    artist = remote.artist,
                    description = remote.description,
                    genre = remote.getGenres(),
                    thumbnailUrl = thumbnailUrl,
                    status = remote.status.toLong(),
                    updateStrategy = remote.update_strategy,
                    initialized = true,
                    memo = remote.memo,
                ),
            )
        } catch (e: Throwable) {
            // Also on cancellation: the folder goes back to the name the stored title gives it
            moveBack(local, renamedTo)
            throw e
        }
        if (!saved) moveBack(local, renamedTo)
    }

    private suspend fun moveBack(local: Manga, renamedTo: String?) {
        if (renamedTo == null) return
        withContext(NonCancellable) {
            downloadManager.renameManga(local.id, local.source, renamedTo, local.ogTitle)
        }
    }
}
