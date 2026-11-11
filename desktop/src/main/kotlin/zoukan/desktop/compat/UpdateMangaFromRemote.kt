// ZOUKAN -->
package mihon.domain.source.interactor

import eu.kanade.tachiyomi.source.Source
import logcat.LogPriority
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import zoukan.desktop.domain.RefreshManga

/**
 * The Android app's UpdateMangaFromRemote, which the shared E-Hentai and merged-source code call,
 * on top of the desktop's [RefreshManga]. Covers, download folders and fetch intervals are the
 * desktop's own business.
 */
class UpdateMangaFromRemote(
    private val sourceManager: SourceManager,
    private val refreshManga: RefreshManga,
) {
    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        throttleFunc: suspend () -> Unit = {},
    ): Result<RemoteMangaUpdate> = invoke(
        source = sourceManager.getOrStub(manga.source),
        manga = manga,
        fetchDetails = fetchDetails,
        fetchChapters = fetchChapters,
        manualFetch = manualFetch,
        fetchWindow = fetchWindow,
        throttleFunc = throttleFunc,
    )

    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        throttleFunc: suspend () -> Unit = {},
    ): Result<RemoteMangaUpdate> = try {
        val result = refreshManga(
            manga,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            source = source,
            downloadNew = manualFetch,
            throttle = throttleFunc,
        )
        Result.success(RemoteMangaUpdate(result.manga, result.newChapters))
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e)
        Result.failure(e)
    }
}
// ZOUKAN <--
