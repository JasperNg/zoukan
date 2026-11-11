package zoukan.desktop.ui.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.domain.toSManga
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalSource
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MangaScreenModel(
    private val mangaId: Long,
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val refreshManga: RefreshManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val trackChapters: TrackChapters = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    // ZOUKAN -->
    private val getFlatMetadataById: tachiyomi.domain.manga.interactor.GetFlatMetadataById = Injekt.get(),
    // ZOUKAN <--
) : StateScreenModel<MangaScreenModel.State>(State()) {

    data class State(
        val manga: Manga? = null,
        val chapters: List<Chapter> = emptyList(),
        val source: Source? = null,
        val refreshing: Boolean = false,
        val error: String? = null,
        /** Set while the user picks categories for a new library entry */
        val categoryChoice: List<Category>? = null,
        val selected: Set<Long> = emptySet(),
        val descending: Boolean = true,
        /** Chapters downloaded to disk */
        val downloaded: Set<Long> = emptySet(),
        /** Chapters in the download queue */
        val queued: Map<Long, Download> = emptyMap(),
        // ZOUKAN -->
        /** How many trackers track this entry */
        val trackCount: Int = 0,
        val showTracking: Boolean = false,
        /** The chapter number to send to the trackers, while the "Ask" setting asks */
        val askTrackChapter: Double? = null,
        /** The source's metadata of the entry (E-Hentai, MangaDex and the other metadata sources) */
        val meta: exh.metadata.metadata.RaisedSearchMetadata? = null,
        // ZOUKAN <--
    ) {
        val isLocal: Boolean get() = manga?.source == LocalSource.ID

        /** Newest first by default, the order sources list chapters in */
        val sortedChapters: List<Chapter>
            get() = if (descending) chapters.sortedBy { it.sourceOrder } else chapters.sortedByDescending { it.sourceOrder }

        /** The chapter "Continue" or "Start" opens: the oldest unread one */
        val nextChapter: Chapter?
            get() = chapters.sortedByDescending { it.sourceOrder }.firstOrNull { !it.read }

        val hasStarted: Boolean
            get() = chapters.any { it.read || it.lastPageRead > 0 }
    }

    private var firstLoad = true

    init {
        screenModelScope.launch {
            // ZOUKAN -->
            // A merged entry's chapters belong to the entries it merges
            val isMerged = getMangaWithChapters.awaitManga(mangaId).source == MERGED_SOURCE_ID
            val flow = if (isMerged) {
                getMangaWithChapters.subscribe(mangaId)
                    .combine(getMergedChaptersByMangaId.subscribe(mangaId)) { (manga, _), merged -> manga to merged }
            } else {
                getMangaWithChapters.subscribe(mangaId)
            }
            // ZOUKAN <--
            flow.collect { (manga, chapters) ->
                mutableState.update {
                    it.copy(manga = manga, chapters = chapters, source = sourceManager.get(manga.source))
                }
                updateDownloaded()
                if (firstLoad) {
                    firstLoad = false
                    if (!manga.initialized || chapters.isEmpty()) {
                        refresh(fetchDetails = !manga.initialized || manga.thumbnailUrl.isNullOrBlank())
                    }
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            downloadManager.changes.collect { updateDownloaded() }
        }
        // ZOUKAN -->
        // The metadata block and namespace tags, as Android's MangaScreenModel raises them
        screenModelScope.launch {
            getFlatMetadataById.subscribe(mangaId).collect { flat ->
                // The entry can be deleted while the screen is open
                val meta = try {
                    val source = sourceManager.get(getMangaWithChapters.awaitManga(mangaId).source)
                    val metaClass = source?.getMainSource<eu.kanade.tachiyomi.source.online.MetadataSource<*, *>>()?.metaClass
                    if (flat != null && metaClass != null) flat.raise(metaClass) else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                mutableState.update { it.copy(meta = meta) }
            }
        }
        // ZOUKAN <--
        screenModelScope.launch {
            getTracks.subscribe(mangaId).collect { tracks -> mutableState.update { it.copy(trackCount = tracks.size) } }
        }
        screenModelScope.launch {
            downloadManager.queue
                .map { q -> q.filter { it.manga.id == mangaId }.associateBy { it.chapter.id } }
                .distinctUntilChanged()
                .collect { queued -> mutableState.update { it.copy(queued = queued) } }
        }
    }

    /** Bumped by each check of the downloads, so an older check that ends last is dropped. */
    private val downloadedCheck = AtomicInteger()

    private fun updateDownloaded() {
        val manga = state.value.manga ?: return
        if (manga.source == LocalSource.ID) return
        val chapters = state.value.chapters
        val check = downloadedCheck.incrementAndGet()
        screenModelScope.launch(Dispatchers.IO) {
            // ZOUKAN -->
            val ids = byEntry(manga, chapters).flatMapTo(HashSet()) { (entry, list) -> downloadManager.downloadedIds(entry, list) }
            // ZOUKAN <--
            mutableState.update { if (check == downloadedCheck.get()) it.copy(downloaded = ids) else it }
        }
    }

    fun download(chapters: List<Chapter>) {
        val manga = state.value.manga ?: return
        if (state.value.isLocal) return
        // ZOUKAN -->
        screenModelScope.launch {
            byEntry(manga, chapters).forEach { (entry, list) -> downloadManager.enqueue(entry, list) }
        }
        // ZOUKAN <--
        clearSelection()
    }

    /** Downloads the next [count] unread chapters, or all unread ones when null. */
    fun downloadUnread(count: Int?) {
        val unread = state.value.chapters.sortedByDescending { it.sourceOrder }
            .filter { !it.read && it.id !in state.value.downloaded }
        download(if (count == null) unread else unread.take(count))
    }

    fun deleteDownloads(chapters: List<Chapter>) {
        val manga = state.value.manga ?: return
        screenModelScope.launch {
            byEntry(manga, chapters).forEach { (entry, list) -> downloadManager.delete(entry, list) }
            clearSelection()
        }
    }

    // ZOUKAN -->
    private val mergedEntries = java.util.concurrent.ConcurrentHashMap<Long, Manga>()

    /**
     * Chapters grouped by the entry they belong to: the entry itself, or for a merged entry the
     * entries it merges, which own the chapters and their downloads, as on Android.
     */
    private suspend fun byEntry(manga: Manga, chapters: List<Chapter>): List<Pair<Manga, List<Chapter>>> {
        if (manga.source != MERGED_SOURCE_ID) return listOf(manga to chapters)
        return chapters.groupBy { it.mangaId }.mapNotNull { (id, list) ->
            val entry = mergedEntries[id] ?: getMangaWithChapters.awaitManga(id).also { mergedEntries[id] = it }
            entry to list
        }
    }
    // ZOUKAN <--

    fun cancelDownload(chapter: Chapter) = downloadManager.cancel(setOf(chapter.id))

    fun refresh(fetchDetails: Boolean = true) {
        val manga = state.value.manga ?: return
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, error = null) }
        screenModelScope.launch {
            val error = try {
                refreshManga(manga, fetchDetails = fetchDetails, fetchChapters = true)
                null
            } catch (e: NoChaptersException) {
                "The source lists no chapters"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an extension built against another API fails with a
                // LinkageError, which would leave the screen refreshing for good
                e.message ?: e::class.simpleName
            }
            mutableState.update { it.copy(refreshing = false, error = error) }
        }
    }

    fun toggleFavorite() {
        val manga = state.value.manga ?: return
        screenModelScope.launch {
            if (manga.favorite) {
                mangaRepository.update(MangaUpdate(id = manga.id, favorite = false, dateAdded = 0))
                Toasts.show("Removed from library")
            } else {
                val categories = getCategories.await().filterNot { it.isSystemCategory }
                // ZOUKAN -->
                // Settings > Library > Default category, as on Android: a category, the default
                // one (0), or ask (-1)
                val defaultId = Injekt.get<tachiyomi.domain.library.service.LibraryPreferences>().defaultCategory().get().toLong()
                val defaultCategory = categories.find { it.id == defaultId }
                when {
                    defaultCategory != null -> addToLibrary(listOf(defaultCategory.id))
                    defaultId == 0L || categories.isEmpty() -> addToLibrary(emptyList())
                    else -> mutableState.update { it.copy(categoryChoice = categories) }
                }
                // ZOUKAN <--
            }
        }
    }

    fun addToLibrary(categoryIds: List<Long>) {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(categoryChoice = null) }
        screenModelScope.launch {
            mangaRepository.update(MangaUpdate(id = manga.id, favorite = true, dateAdded = Instant.now().toEpochMilli()))
            setMangaCategories.await(manga.id, categoryIds)
            Toasts.show("Added to library")
            // As on Android: bind the enhanced trackers, then offer tracking
            state.value.source?.let { source ->
                launch(Dispatchers.IO) {
                    runCatching { addTracks.bindEnhancedTrackers(manga, source) }
                        .onFailure { Toasts.error("Tracking failed", it) }
                }
            }
            if (trackPreferences.trackOnAddingToLibrary().get() && trackerManager.loggedInTrackers().isNotEmpty()) {
                setTrackingVisible(true)
            }
        }
    }

    fun setTrackingVisible(visible: Boolean) = mutableState.update { it.copy(showTracking = visible) }

    fun dismissCategoryChoice() = mutableState.update { it.copy(categoryChoice = null) }

    fun toggleSort() = mutableState.update { it.copy(descending = !it.descending) }

    fun toggleSelection(chapter: Chapter) = mutableState.update {
        it.copy(selected = if (chapter.id in it.selected) it.selected - chapter.id else it.selected + chapter.id)
    }

    fun selectAll() = mutableState.update { it.copy(selected = it.chapters.mapTo(HashSet()) { c -> c.id }) }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    fun markRead(chapters: List<Chapter>, read: Boolean) {
        screenModelScope.launch {
            updateChapter.awaitAll(
                chapters.map { ChapterUpdate(id = it.id, read = read, lastPageRead = if (read) null else 0L) },
            )
            clearSelection()
            // ZOUKAN -->
            if (read) AppScope.launch { downloadManager.afterMarkedRead(chapters.filter { !it.read }) }
            // ZOUKAN <--
            val highest = chapters.filter { it.isRecognizedNumber }.maxOfOrNull { it.chapterNumber }
            if (read && highest != null && state.value.trackCount > 0) {
                when (trackPreferences.autoUpdateTrackOnMarkRead().get()) {
                    AutoTrackState.ALWAYS -> AppScope.launch { trackChapters.await(mangaId, highest) }
                    AutoTrackState.ASK -> mutableState.update { it.copy(askTrackChapter = highest) }
                    AutoTrackState.NEVER -> Unit
                }
            }
        }
    }

    /** Answers the "Ask" setting's question: [update] sends the chapter to the trackers. */
    fun answerTrackQuestion(update: Boolean) {
        val chapter = state.value.askTrackChapter ?: return
        mutableState.update { it.copy(askTrackChapter = null) }
        if (update) AppScope.launch { trackChapters.await(mangaId, chapter) }
    }

    /** Marks every chapter before [chapter] (in reading order) as read. */
    fun markPreviousRead(chapter: Chapter) {
        markRead(state.value.chapters.filter { it.sourceOrder > chapter.sourceOrder && !it.read }, true)
    }

    fun setBookmark(chapters: List<Chapter>, bookmark: Boolean) {
        screenModelScope.launch {
            updateChapter.awaitAll(chapters.map { ChapterUpdate(id = it.id, bookmark = bookmark) })
            clearSelection()
        }
    }

    private fun mangaUrl(): String? {
        val manga = state.value.manga ?: return null
        val source = state.value.source as? HttpSource ?: return null
        return runCatching { source.getMangaUrl(manga.toSManga()) }.getOrNull()
    }

    /** Opens the entry's page in the built-in browser, which shares cookies with the app. */
    fun openInWebView() {
        val url = mangaUrl() ?: return Toasts.show("This source has no web page")
        AppScope.launch {
            val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
            if (!BrowserWindow(url, userAgent).open()) Toasts.show("The built-in browser could not start")
        }
    }

    fun openInBrowser() {
        val url = mangaUrl() ?: return Toasts.show("This source has no web page")
        AppScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }
                    .onFailure { Toasts.error("Could not open the browser", it) }
            }
        }
    }
}
