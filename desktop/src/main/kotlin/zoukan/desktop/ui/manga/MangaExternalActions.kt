package zoukan.desktop.ui.manga

import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.app.Toasts
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.toSManga
import zoukan.desktop.track.TrackChapters
import zoukan.i18n.desktop.DMR

/**
 * External integrations and metadata helpers for the entry screen: trackers (sync, auto-track on
 * read, prompt on library add), source metadata observation, merged entries lookup, and opening
 * the entry in WebView or the external browser.
 */
class MangaExternalActions(
    private val sourceManager: SourceManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapters: TrackChapters = Injekt.get(),
    private val refreshTracks: RefreshTracks = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val getMergedMangaById: Lazy<GetMergedMangaById> = lazy { GetMergedMangaById(Injekt.get()) },
    private val appScope: CoroutineScope = AppScope,
) {
    fun observeTrackCount(mangaId: Long): Flow<Int> =
        getTracks.subscribe(mangaId).map { it.size }

    fun observeMetadata(
        mangaId: Long,
        sourceIdFor: suspend () -> Long,
    ): Flow<RaisedSearchMetadata?> =
        getFlatMetadataById.subscribe(mangaId).map { flat ->
            // The entry can be deleted while the screen is open
            try {
                val source = sourceManager.get(sourceIdFor())
                val metaClass = source?.getMainSource<MetadataSource<*, *>>()?.metaClass
                if (flat != null && metaClass != null) flat.raise(metaClass) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** Android's syncTrackers: refreshes every tracker and syncs chapter progress from it when the setting is on. */
    suspend fun syncTrackers(mangaId: Long) {
        if (!trackPreferences.autoSyncProgressFromTrackers().get()) return
        try {
            refreshTracks.await(mangaId, enhancedTrackersOnly = false)
                .forEach { (tracker, e) ->
                    if (tracker == null) return@forEach
                    logcat(LogPriority.ERROR, e) { "Failed to refresh track data mangaId=$mangaId for service ${tracker.id}" }
                    Toasts.show(localized(MR.strings.track_error, tracker.name, e.message ?: ""))
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfVmFailure()
            logcat(LogPriority.ERROR, e) { "Failed to sync trackers mangaId=$mangaId" }
        }
    }

    fun shouldShowTrackingOnAdd(): Boolean =
        trackPreferences.trackOnAddingToLibrary().get() && trackerManager.loggedInTrackers().isNotEmpty()

    /**
     * Handles auto-tracking after chapters are marked read. Returns the chapter number to ask the
     * user about when the preference is [AutoTrackState.ASK], or null otherwise.
     */
    fun handleAutoTrackOnMarkRead(mangaId: Long, chapters: List<Chapter>, trackCount: Int): Double? {
        val highest = chapters.filter { it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }
        if (highest == null || trackCount <= 0) return null
        return when (trackPreferences.autoUpdateTrackOnMarkRead().get()) {
            AutoTrackState.ALWAYS -> {
                appScope.launch { trackChapters.await(mangaId, highest) }
                null
            }
            AutoTrackState.ASK -> highest
            AutoTrackState.NEVER -> null
        }
    }

    fun trackChapter(mangaId: Long, chapter: Double) {
        appScope.launch { trackChapters.await(mangaId, chapter) }
    }

    fun mangaUrl(manga: Manga?, source: Source?): String? {
        val target = manga ?: return null
        val http = source as? HttpSource ?: return null
        return runCatching { http.getMangaUrl(target.toSManga()) }.getOrNull()
    }

    /** Opens the entry's page in the built-in browser, which shares cookies with the app. */
    fun openInWebView(manga: Manga?, source: Source?) {
        val url = mangaUrl(manga, source) ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        appScope.launch {
            if (!BrowserWindow(url).open()) Toasts.show(localized(DMR.strings.the_built_in_browser_could_not))
        }
    }

    fun openInBrowser(manga: Manga?, source: Source?) {
        val url = mangaUrl(manga, source) ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ExternalLinks.browse(url) }
                    .onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_browser), it) }
            }
        }
    }

    /**
     * The entries a merged entry merges, each with its source, as Komikku's mergedMangaAction
     * offers them; empty for an entry that is not merged.
     */
    suspend fun mergedEntries(manga: Manga?): List<Pair<Manga, Source>> {
        val target = manga ?: return emptyList()
        if (target.source != MERGED_SOURCE_ID) return emptyList()
        return withContext(Dispatchers.IO) {
            getMergedMangaById.value.await(target.id)
                .filterNot { it.source == MERGED_SOURCE_ID }
                .map { it to sourceManager.getOrStub(it.source) }
        }
    }
}
