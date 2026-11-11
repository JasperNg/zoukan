package zoukan.desktop.domain

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.MergedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
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
        val title = remoteTitle.takeIf {
            it.isNotEmpty() && (!local.favorite || libraryPreferences.updateMangaTitles().get())
        }
        val thumbnailUrl = remote.thumbnail_url?.takeIf { it.isNotEmpty() }
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
    }
}
