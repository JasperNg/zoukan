package zoukan.desktop.ui.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedManga
import exh.source.getMainSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.domain.toSManga
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.LocalSource
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR
import java.util.concurrent.atomic.AtomicInteger

class MangaScreenModel(
    private val mangaId: Long,
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val refreshManga: RefreshManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapters: TrackChapters = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    // ZOUKAN -->
    private val getFlatMetadataById: tachiyomi.domain.manga.interactor.GetFlatMetadataById = Injekt.get(),
    private val addToLibraryFlow: zoukan.desktop.library.AddToLibrary = Injekt.get(),
    private val chapterSettings: ChapterSettings = ChapterSettings(),
    private val filterChaptersForDownload: mihon.domain.chapter.interactor.FilterChaptersForDownload = Injekt.get(),
    private val updateErrors: zoukan.desktop.library.LibraryUpdateErrors = Injekt.get(),
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
        /** Library entries with the same title, while the duplicate check asks */
        val duplicates: List<tachiyomi.domain.manga.model.MangaWithChapterCount>? = null,
        /** Scanlators whose chapters the entry hides, as on Android */
        val excludedScanlators: Set<String> = emptySet(),
        /** More > Downloaded only, which shows only downloaded chapters, as on Android */
        val downloadedOnly: Boolean = false,
        // ZOUKAN <--
    ) {
        val isLocal: Boolean get() = manga?.source == LocalSource.ID

        // ZOUKAN -->
        /** The scanlators of the entry's chapters, which the scanlator filter offers */
        val availableScanlators: Set<String>
            get() = chapters.mapNotNullTo(sortedSetOf()) { it.scanlator?.takeIf(String::isNotBlank) }

        val scanlatorFilterActive: Boolean
            get() = excludedScanlators.any { it in availableScanlators }

        /** Whether a filter of the chapter settings hides chapters, which tints its button */
        val filterActive: Boolean
            get() = scanlatorFilterActive || manga?.let { ChapterSettings.filtered(it, downloadedOnly) } == true

        /** The chapters the list shows: filtered and sorted by the entry's chapter settings */
        val sortedChapters: List<Chapter> by lazy {
            manga?.let { ChapterSettings.apply(it, chapters, excludedScanlators, downloaded, isLocal, downloadedOnly) } ?: chapters
        }

        /**
         * The chapter "Continue" or "Start" opens: the first unread one in reading order among
         * the chapters the filters show, as on Android
         */
        val nextChapter: Chapter?
            get() {
                val manga = manga ?: return null
                val shown = sortedChapters
                return if (manga.sortDescending()) shown.findLast { !it.read } else shown.find { !it.read }
            }
        // ZOUKAN <--

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
                    // ZOUKAN -->
                    // An entry outside the library takes the default chapter settings, as on Android
                    if (!manga.favorite) chapterSettings.reset(manga)
                    // ZOUKAN <--
                    if (!manga.initialized || chapters.isEmpty()) {
                        refresh(fetchDetails = !manga.initialized || manga.thumbnailUrl.isNullOrBlank(), manualFetch = false)
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
        screenModelScope.launch {
            chapterSettings.excludedScanlators(mangaId).collect { excluded ->
                mutableState.update { it.copy(excludedScanlators = excluded) }
            }
        }
        screenModelScope.launch {
            Injekt.get<zoukan.desktop.ui.common.DesktopPreferences>().downloadedOnly().changes().collect { on ->
                mutableState.update { it.copy(downloadedOnly = on) }
            }
        }
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
        done(chapters)
    }

    /** Downloads the next [count] unread chapters, or all unread ones when null. */
    fun downloadUnread(count: Int?) {
        val unread = state.value.chapters.sortedByDescending { it.sourceOrder }
            .filter { !it.read && it.id !in state.value.downloaded }
        download(if (count == null) unread else unread.take(count))
    }

    // ZOUKAN -->
    /** Downloads the bookmarked chapters not downloaded yet, as Android's download menu. */
    fun downloadBookmarked() {
        download(state.value.chapters.sortedByDescending { it.sourceOrder }.filter { it.bookmark && it.id !in state.value.downloaded })
    }
    // ZOUKAN <--

    fun deleteDownloads(chapters: List<Chapter>) {
        val manga = state.value.manga ?: return
        screenModelScope.launch {
            byEntry(manga, chapters).forEach { (entry, list) -> downloadManager.delete(entry, list) }
            done(chapters)
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

    /**
     * Fetches the entry's details and chapters, as Android's fetchAllFromSource.
     *
     * @param manualFetch the refresh button, not the first load: a merged entry downloads the new
     * chapters of references set to download, and the new chapters are downloaded by the "Download
     * new chapters" settings
     */
    fun refresh(fetchDetails: Boolean = true, manualFetch: Boolean = true) {
        val manga = state.value.manga ?: return
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, error = null) }
        screenModelScope.launch {
            val error = try {
                val update = refreshManga(manga, fetchDetails = fetchDetails, fetchChapters = true, downloadNew = manualFetch)
                // KMK: a successful refresh clears the entry's update error
                runCatching { updateErrors.clear(manga.id) }
                if (manualFetch) downloadNewChapters(update.manga, update.newChapters)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an extension built against another API fails with a
                // LinkageError, which would leave the screen refreshing for good
                // KMK: the failure is the entry's update error, as a library update's
                runCatching { updateErrors.record(manga.id, zoukan.desktop.library.LibraryUpdater.errorMessage(e)) }
                if (e is NoChaptersException) localized(DMR.strings.the_source_lists_no_chapters) else e.message ?: e::class.simpleName
            }
            mutableState.update { it.copy(refreshing = false, error = error) }
        }
    }

    // ZOUKAN -->
    /** Queues the new chapters the download settings pick, as Android's downloadNewChapters. */
    private fun downloadNewChapters(manga: Manga, chapters: List<Chapter>) {
        screenModelScope.launch(kotlinx.coroutines.NonCancellable) {
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)
            if (chaptersToDownload.isNotEmpty() && !manga.isEhBasedManga()) {
                byEntry(manga, chaptersToDownload).forEach { (entry, list) -> downloadManager.enqueue(entry, list) }
            }
        }
    }
    // ZOUKAN <--

    fun toggleFavorite() {
        val manga = state.value.manga ?: return
        screenModelScope.launch {
            if (manga.favorite) {
                // ZOUKAN -->
                addToLibraryFlow.remove(manga)
                // ZOUKAN <--
                Toasts.show(localized(MR.strings.manga_removed_library))
            } else {
                // ZOUKAN -->
                // Android's duplicate check, then the default category or a choice
                val duplicates = addToLibraryFlow.duplicates(manga)
                if (duplicates.isNotEmpty()) {
                    mutableState.update { it.copy(duplicates = duplicates) }
                } else {
                    addAnyway()
                }
                // ZOUKAN <--
            }
        }
    }

    // ZOUKAN -->
    /** Adds the entry after the duplicate check: to the default category, or asks for categories. */
    fun addAnyway() {
        mutableState.update { it.copy(duplicates = null) }
        screenModelScope.launch {
            val plan = addToLibraryFlow.plan()
            if (plan.ask != null) {
                mutableState.update { it.copy(categoryChoice = plan.ask) }
            } else {
                addToLibrary(plan.categoryIds)
            }
        }
    }

    fun dismissDuplicates() = mutableState.update { it.copy(duplicates = null) }
    // ZOUKAN <--

    fun addToLibrary(categoryIds: List<Long>) {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(categoryChoice = null) }
        screenModelScope.launch {
            // ZOUKAN -->
            // As on Android: bind the enhanced trackers, then offer tracking
            addToLibraryFlow.add(manga, categoryIds)
            // ZOUKAN <--
            Toasts.show(localized(MR.strings.manga_added_library))
            if (trackPreferences.trackOnAddingToLibrary().get() && trackerManager.loggedInTrackers().isNotEmpty()) {
                setTrackingVisible(true)
            }
        }
    }

    fun setTrackingVisible(visible: Boolean) = mutableState.update { it.copy(showTracking = visible) }

    fun dismissCategoryChoice() = mutableState.update { it.copy(categoryChoice = null) }

    // ZOUKAN -->
    /** The chapter settings sheet's filters and sort, saved on the entry as on Android */
    fun setUnreadFilter(filter: TriState) = updateFlags { chapterSettings.setUnreadFilter(it, filter) }

    fun setDownloadedFilter(filter: TriState) = updateFlags { chapterSettings.setDownloadedFilter(it, filter) }

    fun setBookmarkedFilter(filter: TriState) = updateFlags { chapterSettings.setBookmarkedFilter(it, filter) }

    /** Sorts by [sorting], or flips the order when the chapters are already sorted by it */
    fun setSorting(sorting: Long) = updateFlags { chapterSettings.setSortingOrFlip(it, sorting) }

    fun resetChapterSettings() = updateFlags { chapterSettings.reset(it) }

    fun setChapterSettingsAsDefault(toLibrary: Boolean) = updateFlags {
        chapterSettings.setAsDefault(it, toLibrary)
        Toasts.show(localized(DMR.strings.default_chapter_settings_updated))
    }

    fun setExcludedScanlators(excluded: Set<String>) {
        screenModelScope.launch { chapterSettings.setExcludedScanlators(mangaId, excluded) }
    }

    private fun updateFlags(update: suspend (Manga) -> Unit) {
        val manga = state.value.manga ?: return
        screenModelScope.launch { update(manga) }
    }
    // ZOUKAN <--

    fun toggleSelection(chapter: Chapter) = mutableState.update {
        it.copy(selected = if (chapter.id in it.selected) it.selected - chapter.id else it.selected + chapter.id)
    }

    // ZOUKAN -->
    // The chapters the filters show, as on Android
    fun selectAll() = mutableState.update { it.copy(selected = it.sortedChapters.mapTo(HashSet()) { c -> c.id }) }
    // ZOUKAN <--

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    /**
     * Ends an action on [chapters]: an action on the selection clears it, and one on a chapter
     * outside the selection (its row's buttons or context menu) leaves the selection as it was.
     */
    private fun done(chapters: List<Chapter>) {
        if (chapters.mapTo(HashSet()) { it.id } == state.value.selected) clearSelection()
    }

    fun markRead(chapters: List<Chapter>, read: Boolean) {
        screenModelScope.launch {
            updateChapter.awaitAll(
                chapters.map { ChapterUpdate(id = it.id, read = read, lastPageRead = if (read) null else 0L) },
            )
            done(chapters)
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
            done(chapters)
        }
    }

    private fun mangaUrl(): String? {
        val manga = state.value.manga ?: return null
        val source = state.value.source as? HttpSource ?: return null
        return runCatching { source.getMangaUrl(manga.toSManga()) }.getOrNull()
    }

    /** Opens the entry's page in the built-in browser, which shares cookies with the app. */
    fun openInWebView() {
        val url = mangaUrl() ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        AppScope.launch {
            val userAgent = Injekt.get<NetworkHelper>().defaultUserAgentProvider()
            if (!BrowserWindow(url, userAgent).open()) Toasts.show(localized(DMR.strings.the_built_in_browser_could_not))
        }
    }

    fun openInBrowser() {
        val url = mangaUrl() ?: return Toasts.show(localized(DMR.strings.this_source_has_no_web_page))
        AppScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }
                    .onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_browser), it) }
            }
        }
    }
}
