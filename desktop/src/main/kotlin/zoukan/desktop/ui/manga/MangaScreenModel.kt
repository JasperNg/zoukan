package zoukan.desktop.ui.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.util.isVmFailure
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.Toasts
import zoukan.desktop.app.launchAction
import zoukan.desktop.app.runCatchingCancellable
import zoukan.desktop.backup.SafetySnapshot
import zoukan.desktop.backup.SnapshotFailedPrompt
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.library.AddToLibrary
import zoukan.desktop.library.LibraryUpdateErrors
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.track.TrackChapters
import zoukan.desktop.ui.common.LibraryAddController
import zoukan.desktop.ui.common.ListSelection
import zoukan.desktop.ui.common.launchReporting
import zoukan.desktop.ui.common.sourceErrorText
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.default_chapter_settings_updated
import zoukan.i18n.desktop.screen_action_failed_add_to_library
import zoukan.i18n.desktop.screen_action_failed_bookmark
import zoukan.i18n.desktop.screen_action_failed_delete_downloads
import zoukan.i18n.desktop.screen_action_failed_mark_read
import zoukan.i18n.desktop.screen_action_failed_set_categories
import zoukan.i18n.desktop.screen_action_failed_toggle_favorite
import zoukan.i18n.desktop.the_source_lists_no_chapters
import zoukan.i18n.desktop.this_entry_no_longer_exists
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// ZOUKAN -->
/**
 * Whether the Resume button docks to the start of the chapter list. Android persists
 * FabPosition.Start/End as "Start"/"End" while desktop's Compose material3 spells them
 * "FabPosition.Start"/"FabPosition.End": both are read, Android's is written, as the
 * preference travels in backups between the apps.
 */
internal fun readButtonAtStart(position: String): Boolean =
    position == "Start" || position == androidx.compose.material3.FabPosition.Start.toString()
// ZOUKAN <--

class MangaScreenModel(
    private val mangaId: Long,
    private val getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val refreshManga: RefreshManga = Injekt.get(),
    private val updateErrors: LibraryUpdateErrors = Injekt.get(),
    private val chapterSettings: ChapterSettings = ChapterSettings(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val desktopPreferences: Lazy<DesktopPreferences> = injectLazy(),
    private val chapterActions: MangaChapterActions = MangaChapterActions(),
    private val cleanupActions: MangaCleanupActions = MangaCleanupActions(),
    private val externalActions: MangaExternalActions = MangaExternalActions(),
    private val libraryAdd: LibraryAddController = LibraryAddController(),
) : StateScreenModel<MangaScreenModel.State>(
    State(
        swipeToStart = libraryPreferences.swipeToStartAction().get(),
        swipeToEnd = libraryPreferences.swipeToEndAction().get(),
        showMissingChapters = !libraryPreferences.hideMissingChapters().get(),
        readButtonAtStart = readButtonAtStart(uiPreferences.readButtonPosition().get()),
    ),
) {
    constructor(
        mangaId: Long,
        getMangaWithChapters: GetMangaWithChapters = Injekt.get(),
        sourceManager: SourceManager = Injekt.get(),
        updateChapter: UpdateChapter,
        refreshManga: RefreshManga = Injekt.get(),
        downloadManager: DownloadManager = Injekt.get(),
        getTracks: GetTracks = Injekt.get(),
        trackPreferences: TrackPreferences = Injekt.get(),
        trackerManager: TrackerManager = Injekt.get(),
        trackChapters: TrackChapters = Injekt.get(),
        getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
        getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
        addToLibraryFlow: AddToLibrary = Injekt.get(),
        getCategories: GetCategories = Injekt.get(),
        setMangaCategories: SetMangaCategories = Injekt.get(),
        chapterSettings: ChapterSettings = ChapterSettings(),
        filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
        updateErrors: LibraryUpdateErrors = Injekt.get(),
        refreshTracks: RefreshTracks = Injekt.get(),
        libraryPreferences: LibraryPreferences = Injekt.get(),
        uiPreferences: UiPreferences = Injekt.get(),
        chaptersByEntry: ChaptersByEntry = Injekt.get<ChaptersByEntry>().cached(),
        desktopPreferences: Lazy<DesktopPreferences> = injectLazy(),
        downloadPaths: Lazy<DownloadPaths> = injectLazy(),
        getChaptersByMangaId: Lazy<GetChaptersByMangaId> = injectLazy(),
        getMergedMangaById: Lazy<GetMergedMangaById> = lazy { GetMergedMangaById(Injekt.get()) },
        deleteChapters: Lazy<DeleteChapters> = lazy { DeleteChapters(Injekt.get()) },
        appScope: CoroutineScope = AppScope,
        safetySnapshot: SafetySnapshot = SafetySnapshot.forApp(),
        snapshotPrompt: SnapshotFailedPrompt = SnapshotFailedPrompt {
            zoukan.desktop.ui.common.snapshotFailedPrompt.continueWithout(it)
        },
    ) : this(
        mangaId = mangaId,
        getMangaWithChapters = getMangaWithChapters,
        getMergedChaptersByMangaId = getMergedChaptersByMangaId,
        sourceManager = sourceManager,
        refreshManga = refreshManga,
        updateErrors = updateErrors,
        chapterSettings = chapterSettings,
        libraryPreferences = libraryPreferences,
        uiPreferences = uiPreferences,
        desktopPreferences = desktopPreferences,
        chapterActions = MangaChapterActions(
            updateChapter = updateChapter,
            downloadManager = downloadManager,
            chaptersByEntry = chaptersByEntry,
            filterChaptersForDownload = filterChaptersForDownload,
            appScope = appScope,
        ),
        cleanupActions = MangaCleanupActions(
            downloadManager = downloadManager,
            chaptersByEntry = chaptersByEntry,
            sourceManager = sourceManager,
            downloadPaths = downloadPaths,
            getChaptersByMangaId = getChaptersByMangaId,
            getMergedMangaById = getMergedMangaById,
            deleteChapters = deleteChapters,
            safetySnapshot = safetySnapshot,
            snapshotPrompt = snapshotPrompt,
            appScope = appScope,
        ),
        externalActions = MangaExternalActions(
            sourceManager = sourceManager,
            getTracks = getTracks,
            trackPreferences = trackPreferences,
            trackerManager = trackerManager,
            trackChapters = trackChapters,
            refreshTracks = refreshTracks,
            getFlatMetadataById = getFlatMetadataById,
            getMergedMangaById = getMergedMangaById,
            appScope = appScope,
        ),
        libraryAdd = LibraryAddController(
            addToLibrary = { addToLibraryFlow },
            getCategories = { getCategories },
            setMangaCategories = { setMangaCategories },
        ),
    )

    data class State(
        val manga: Manga? = null,
        val chapters: List<Chapter> = emptyList(),
        val source: Source? = null,
        val refreshing: Boolean = false,
        val error: String? = null,
        /** Set while the user picks categories for a new library entry */
        val categoryChoice: List<Category>? = null,
        // ZOUKAN -->
        /** All categories and the entry's own, while the entry's In-library right click asks to change them */
        val categoryEdit: Pair<List<Category>, Set<Long>>? = null,
        // ZOUKAN <--
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
        val meta: RaisedSearchMetadata? = null,
        /** Library entries with the same title, while the duplicate check asks */
        val duplicates: List<MangaWithChapterCount>? = null,
        /** Scanlators whose chapters the entry hides, as on Android */
        val excludedScanlators: Set<String> = emptySet(),
        /** More > Downloaded only, which shows only downloaded chapters, as on Android */
        val downloadedOnly: Boolean = false,
        /** Settings > Library > Chapter swipe, towards the start and the end of a row */
        val swipeToStart: LibraryPreferences.ChapterSwipeAction =
            LibraryPreferences.ChapterSwipeAction.Disabled,
        val swipeToEnd: LibraryPreferences.ChapterSwipeAction =
            LibraryPreferences.ChapterSwipeAction.Disabled,
        /** False while Settings > Library > "Hide missing chapter indicators" is on */
        val showMissingChapters: Boolean = true,
        /** The side of the chapter list the Resume button docks to */
        val readButtonAtStart: Boolean = false,
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

    /**
     * Bumped by each check of the downloads, so an older check that ends last is dropped. Before
     * the init blocks: the entry can load (and check its downloads) before the constructor ends.
     */
    private val downloadedCheck = AtomicInteger()

    init {
        screenModelScope.launch {
            // ZOUKAN -->
            // The entry can be gone when the screen opens, or be deleted while it is open: show
            // an error, as the reader does, instead of failing on the UI thread
            try {
                loadEntry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Set before a fatal error goes on, so the screen does not stay loading
                mutableState.update { it.copy(refreshing = false, error = localized(DMR.strings.this_entry_no_longer_exists)) }
                e.rethrowIfVmFailure()
                logcat(LogPriority.WARN, e) { "The entry $mangaId is gone" }
            }
            // ZOUKAN <--
        }
    }

    // ZOUKAN -->
    /** Shows the entry and follows it until it is deleted (which throws) or the screen closes. */
    private suspend fun loadEntry() {
        // A merged entry's chapters belong to the entries it merges
        val isMerged = getMangaWithChapters.awaitManga(mangaId).source == MERGED_SOURCE_ID
        val flow = if (isMerged) {
            getMangaWithChapters.subscribe(mangaId)
                .combine(getMergedChaptersByMangaId.subscribe(mangaId)) { (manga, _), merged -> manga to merged }
        } else {
            getMangaWithChapters.subscribe(mangaId)
        }
        flow.collect { (manga, chapters) ->
            mutableState.update {
                it.copy(manga = manga, chapters = chapters, source = sourceManager.get(manga.source))
            }
            updateDownloaded()
            if (firstLoad) {
                firstLoad = false
                // An entry outside the library takes the default chapter settings, as on Android
                if (!manga.favorite) chapterSettings.reset(manga)
                if (!manga.initialized || chapters.isEmpty()) {
                    refresh(fetchDetails = !manga.initialized || manga.thumbnailUrl.isNullOrBlank(), manualFetch = false)
                }
            }
        }
    }
    // ZOUKAN <--

    init {
        screenModelScope.launch {
            chapterActions.downloadChanges.collect { updateDownloaded() }
        }
        // ZOUKAN -->
        screenModelScope.launch {
            chapterSettings.excludedScanlators(mangaId).collect { excluded ->
                mutableState.update { it.copy(excludedScanlators = excluded) }
            }
        }
        screenModelScope.launch {
            libraryPreferences.swipeToStartAction().changes().collect { action ->
                mutableState.update { it.copy(swipeToStart = action) }
            }
        }
        screenModelScope.launch {
            libraryPreferences.swipeToEndAction().changes().collect { action ->
                mutableState.update { it.copy(swipeToEnd = action) }
            }
        }
        screenModelScope.launch {
            libraryPreferences.hideMissingChapters().changes().collect { hide ->
                mutableState.update { it.copy(showMissingChapters = !hide) }
            }
        }
        screenModelScope.launch {
            uiPreferences.readButtonPosition().changes().collect { position ->
                mutableState.update { it.copy(readButtonAtStart = readButtonAtStart(position)) }
            }
        }
        screenModelScope.launch {
            desktopPreferences.value.downloadedOnly().changes().collect { on ->
                mutableState.update { it.copy(downloadedOnly = on) }
            }
        }
        screenModelScope.launch {
            externalActions.observeMetadata(mangaId) { getMangaWithChapters.awaitManga(mangaId).source }
                .collect { meta -> mutableState.update { it.copy(meta = meta) } }
        }
        // ZOUKAN <--
        screenModelScope.launch {
            externalActions.observeTrackCount(mangaId).collect { count ->
                mutableState.update { it.copy(trackCount = count) }
            }
        }
        screenModelScope.launch {
            chapterActions.downloadQueue
                .map { q -> q.filter { it.manga.id == mangaId }.associateBy { it.chapter.id } }
                .distinctUntilChanged()
                .collect { queued -> mutableState.update { it.copy(queued = queued) } }
        }
    }

    private fun updateDownloaded() {
        val manga = state.value.manga ?: return
        if (chapterActions.isLocal(manga)) return
        val chapters = state.value.chapters
        val check = downloadedCheck.incrementAndGet()
        screenModelScope.launch(Dispatchers.IO) {
            val ids = chapterActions.downloadedIds(manga, chapters)
            mutableState.update { if (check == downloadedCheck.get()) it.copy(downloaded = ids) else it }
        }
    }

    fun download(chapters: List<Chapter>) {
        val manga = state.value.manga ?: return
        if (state.value.isLocal) return
        screenModelScope.launch {
            chapterActions.enqueue(manga, chapters)
        }
        done(chapters)
    }

    /** Downloads the next [count] unread chapters, or all unread ones when null. */
    fun downloadUnread(count: Int?) {
        download(chapterActions.selectUnreadForDownload(state.value.chapters, state.value.downloaded, count))
    }

    // ZOUKAN -->
    /** Downloads the bookmarked chapters not downloaded yet, as Android's download menu. */
    fun downloadBookmarked() {
        download(chapterActions.selectBookmarkedForDownload(state.value.chapters, state.value.downloaded))
    }
    // ZOUKAN <--

    fun deleteDownloads(chapters: List<Chapter>) {
        val manga = state.value.manga ?: return
        // A failure (the storage drive unplugged) is shown and keeps the selection, as markRead does
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_delete_downloads) }) {
            cleanupActions.deleteChapterDownloads(manga, chapters)
            done(chapters)
        }
    }

    fun cancelDownload(chapter: Chapter) = chapterActions.cancelDownload(chapter)

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
        onRefreshStart?.invoke(Thread.currentThread())
        mutableState.update { it.copy(refreshing = true, error = null) }
        screenModelScope.launch {
            var error: String? = null
            // The refresh always ends, last: a screen left refreshing can never refresh again, after a
            // fatal error that goes on or a failure in the error handling itself. Not before: ending
            // it earlier would let a second refresh start and be overwritten by this one
            try {
                try {
                    val update = refreshManga(manga, fetchDetails = fetchDetails, fetchChapters = true, downloadNew = manualFetch)
                    // KMK: a successful refresh clears the entry's update error
                    runCatchingCancellable { updateErrors.clear(manga.id) }
                    if (manualFetch) chapterActions.downloadNewChapters(screenModelScope, update.manga, update.newChapters)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A fatal error goes on to the crash report, after the refresh ends with an error
                    if (e.isVmFailure()) error = sourceErrorText(e)
                    e.rethrowIfVmFailure()
                    // Throwable, not Exception: an extension built against another API fails with a
                    // LinkageError, which would leave the screen refreshing for good
                    // KMK: the failure is the entry's update error, as a library update's
                    runCatchingCancellable { updateErrors.record(manga.id, LibraryUpdater.errorMessage(e)) }
                    error = if (e is NoChaptersException) {
                        localized(DMR.strings.the_source_lists_no_chapters)
                    } else {
                        logcat(LogPriority.WARN, e) { "Refreshing the entry failed" }
                        sourceErrorText(e)
                    }
                }
                // KMK: a manual refresh also syncs progress from the trackers, as Android's fetchAllFromSource
                if (manualFetch) launch { externalActions.syncTrackers(mangaId) }
            } finally {
                mutableState.update { it.copy(refreshing = false, error = error) }
            }
        }
    }

    fun toggleFavorite() {
        val manga = state.value.manga ?: return
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_toggle_favorite) }) {
            applyLibraryResult(libraryAdd.toggleSingle(manga, confirmRemove = false, fetchOnAdd = false))
        }
    }

    // ZOUKAN -->
    /** Adds the entry after the duplicate check: to the default category, or asks for categories. */
    fun addAnyway() {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(duplicates = null) }
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_add_to_library) }) {
            applyLibraryResult(libraryAdd.planOrAddSingle(manga, fetchOnAdd = false))
        }
    }

    fun dismissDuplicates() = mutableState.update { it.copy(duplicates = null) }
    // ZOUKAN <--

    fun addToLibrary(categoryIds: List<Long>) {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(categoryChoice = null) }
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_add_to_library) }) {
            applyLibraryResult(libraryAdd.addSingle(manga, categoryIds, fetchOnAdd = false))
        }
    }

    private fun applyLibraryResult(result: LibraryAddController.SingleResult) {
        when (result) {
            is LibraryAddController.SingleResult.Prompt -> when (val prompt = result.prompt) {
                is LibraryAddController.SinglePrompt.Duplicates -> mutableState.update { it.copy(duplicates = prompt.matches) }
                is LibraryAddController.SinglePrompt.Categories -> mutableState.update { it.copy(categoryChoice = prompt.choices) }
                is LibraryAddController.SinglePrompt.ConfirmRemove,
                is LibraryAddController.SinglePrompt.Migrate,
                -> Unit
            }
            is LibraryAddController.SingleResult.Added -> {
                if (externalActions.shouldShowTrackingOnAdd()) setTrackingVisible(true)
            }
            is LibraryAddController.SingleResult.Removed -> Unit
        }
    }

    fun setTrackingVisible(visible: Boolean) = mutableState.update { it.copy(showTracking = visible) }

    fun dismissCategoryChoice() = mutableState.update { it.copy(categoryChoice = null) }

    // ZOUKAN -->
    // Komikku's long press on the entry's In-library button: loads the categories and the entry's
    // own, so the dialog can change them
    fun showCategoryEdit() {
        val manga = state.value.manga ?: return
        screenModelScope.launch {
            val edit = libraryAdd.loadCategoryEdit(manga.id)
            mutableState.update { it.copy(categoryEdit = edit) }
        }
    }

    fun setCategories(chosen: Set<Long>) {
        val manga = state.value.manga ?: return
        mutableState.update { it.copy(categoryEdit = null) }
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_set_categories) }) {
            libraryAdd.setCategories(manga.id, chosen)
        }
    }

    fun dismissCategoryEdit() = mutableState.update { it.copy(categoryEdit = null) }
    // ZOUKAN <--

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
        it.copy(selected = ListSelection(it.selected).toggle(chapter.id).ids)
    }

    fun selectRange(chapter: Chapter) = mutableState.update {
        it.copy(selected = ListSelection(it.selected).selectRange(it.sortedChapters.map { c -> c.id }, chapter.id).ids)
    }

    // ZOUKAN -->
    // The chapters the filters show, as on Android
    fun selectAll() = mutableState.update {
        it.copy(selected = ListSelection(it.selected).selectAll(it.sortedChapters.map { c -> c.id }).ids)
    }
    // ZOUKAN <--

    fun invertSelection() = mutableState.update {
        it.copy(selected = ListSelection(it.selected).invert(it.sortedChapters.map { c -> c.id }).ids)
    }

    fun clearSelection() = mutableState.update {
        it.copy(selected = ListSelection(it.selected).clear().ids)
    }

    // ZOUKAN -->
    /** The Resume button was dragged to a side: saved under Android's "Start"/"End" spelling, as backups carry it between the apps. */
    fun moveReadButton(atStart: Boolean) {
        uiPreferences.readButtonPosition().set(if (atStart) "Start" else "End")
    }
    // ZOUKAN <--

    /**
     * Ends an action on [chapters]: an action on the selection clears it, and one on a chapter
     * outside the selection (its row's buttons or context menu) leaves the selection as it was.
     */
    private fun done(chapters: List<Chapter>) {
        if (chapters.mapTo(HashSet()) { it.id } == state.value.selected) clearSelection()
    }

    fun markRead(chapters: List<Chapter>, read: Boolean) {
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_mark_read) }) {
            chapterActions.markRead(chapters, read)
            done(chapters)
            if (read) {
                val askChapter = externalActions.handleAutoTrackOnMarkRead(mangaId, chapters, state.value.trackCount)
                if (askChapter != null) mutableState.update { it.copy(askTrackChapter = askChapter) }
            }
        }
    }

    /** Answers the "Ask" setting's question: [update] sends the chapter to the trackers. */
    fun answerTrackQuestion(update: Boolean) {
        val chapter = state.value.askTrackChapter ?: return
        mutableState.update { it.copy(askTrackChapter = null) }
        if (update) externalActions.trackChapter(mangaId, chapter)
    }

    /** Marks every chapter before [chapter] (in reading order) as read. */
    fun markPreviousRead(chapter: Chapter) {
        markRead(state.value.chapters.filter { it.sourceOrder > chapter.sourceOrder && !it.read }, true)
    }

    fun setBookmark(chapters: List<Chapter>, bookmark: Boolean) {
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_bookmark) }) {
            chapterActions.setBookmark(chapters, bookmark)
            done(chapters)
        }
    }

    /** Opens the entry's page in the built-in browser, which shares cookies with the app. */
    fun openInWebView() = externalActions.openInWebView(state.value.manga, state.value.source)

    fun openInBrowser() = externalActions.openInBrowser(state.value.manga, state.value.source)

    // ZOUKAN -->
    /** The chapter settings' Display tab: source title or chapter number, as Android's setDisplayMode */
    fun setDisplayMode(mode: Long) = updateFlags { chapterSettings.setDisplayMode(it, mode) }

    /**
     * The entries a merged entry merges, each with its source, as Komikku's mergedMangaAction
     * offers them; empty for an entry that is not merged.
     */
    suspend fun mergedEntries(): List<Pair<Manga, Source>> = externalActions.mergedEntries(state.value.manga)

    /**
     * KMK's Open folder: the entry's download folder (a local entry's own folder) in Explorer or
     * Finder. Nothing opens when the folder does not exist, as on Android.
     */
    fun openMangaFolder(source: Source?, manga: Manga?) = cleanupActions.openMangaFolder(source, manga)

    /**
     * KMK's Clear manga: deletes the entry's downloaded data (for a merged entry, each merged
     * entry's), and its chapters with their history from the database.
     */
    fun clearManga(deleteDownload: Boolean, removeChapters: Boolean) =
        cleanupActions.clearManga(state.value.manga, state.value.chapters, deleteDownload, removeChapters)

    /** The folders in the local source folder holding the user's own files for this entry (or its local parts). */
    suspend fun localFolders(): List<File> = cleanupActions.localFolders(state.value.manga)

    /**
     * Moves [confirmed], the folders the user agreed to in the local-files question, to the system
     * trash. Only those that are still this entry's local folders move: a folder that left the
     * entry (or a merge that gained one) while the question was open is never touched. Where the
     * system has no trash, or the move fails, nothing is deleted: a toast names the folder so the
     * user can remove it.
     */
    fun deleteLocalFiles(confirmed: List<File>) {
        if (confirmed.isEmpty()) return
        // On the screen's scope, so the refresh below checks and sets its busy flag on the UI
        // thread, as the Refresh button does, and not after the screen is closed. Only the move
        // to the trash is made non-cancellable, so closing the screen cannot cut it short.
        screenModelScope.launchAction {
            try {
                val moved = cleanupActions.trashLocalFolders(state.value.manga, confirmed)
                if (moved) refresh(fetchDetails = false, manualFetch = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // ZOUKAN <--
}

// ZOUKAN -->
/**
 * Journeys set it to see on which thread a refresh passes its busy check: it must be the one of
 * Dispatchers.Main, the UI thread. It has no effect in the app.
 */
internal var onRefreshStart: ((Thread) -> Unit)? = null
// ZOUKAN <--
