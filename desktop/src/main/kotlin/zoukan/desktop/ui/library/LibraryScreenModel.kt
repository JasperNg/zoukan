package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.GetSortTag
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedManga
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.manga_removed_library
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.entry_merged
import tachiyomi.i18n.sy.merged_references_invalid
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.ChaptersByEntry
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.launchReporting
import zoukan.desktop.ui.recs.RecommendationSearchHelper
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.a_library_update_is_already_running
import zoukan.i18n.desktop.could_not_update_the_mangadex_follows
import zoukan.i18n.desktop.merging_failed
import zoukan.i18n.desktop.removed_entries_from_library
import zoukan.i18n.desktop.screen_action_failed_clean_titles
import zoukan.i18n.desktop.screen_action_failed_delete_downloads
import zoukan.i18n.desktop.screen_action_failed_mark_read
import zoukan.i18n.desktop.screen_action_failed_remove_from_library
import zoukan.i18n.desktop.screen_action_failed_set_categories
import kotlin.random.Random

/** As Android's AppBar: the library search waits this long after the last key */
private const val SEARCH_DEBOUNCE_MILLIS = 250L

/** Active modal dialog shown over the library screen; at most one may be active at a time. */
sealed interface LibraryDialog {
    data object Settings : LibraryDialog
    data class Categories(val ids: Set<Long>) : LibraryDialog
    data class Remove(val ids: Set<Long>) : LibraryDialog
    data class DeleteDownloads(val ids: Set<Long>) : LibraryDialog
    data object FavoritesSyncNotes : LibraryDialog
    data object FavoritesSyncConfirm : LibraryDialog
    data class Recommendations(val manga: List<Manga>) : LibraryDialog
    data class RemoveMerged(val mergingMangas: List<Manga>, val mergedMangaId: Long) : LibraryDialog
}

/**
 * The library, as the Android LibraryScreenModel shows it: the same filters, sorts, display modes
 * and badges (from the same preferences), and the same actions on a selection.
 */
class LibraryScreenModel(
    getLibraryManga: GetLibraryManga = Injekt.get(),
    getCategories: GetCategories = Injekt.get(),
    getTracksPerManga: GetTracksPerManga = Injekt.get(),
    val preferences: LibraryPreferences = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val setSortModeForCategory: SetSortModeForCategory = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    // SY -->
    private val setCustomMangaInfo: SetCustomMangaInfo = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
    private val librarySearch: LibrarySearch = LibrarySearch(),
    private val extensionManager: DesktopExtensionManager = Injekt.get(),
    private val chaptersByEntry: ChaptersByEntry = Injekt.get(),
    // Resolved on first use, as before, so a missing registration only fails where it is used
    private val desktopPreferences: Lazy<zoukan.desktop.app.DesktopPreferences> = injectLazy(),
    private val libraryUpdater: Lazy<LibraryUpdater> = injectLazy(),
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    /** Display layout and tab visibility preferences. */
    data class DisplayPrefs(
        val mode: LibraryDisplayMode = LibraryDisplayMode.default,
        val columns: Int = 0,
        val continueReading: Boolean = false,
        val categoryTabs: Boolean = true,
        val itemCounts: Boolean = false,
        val showHiddenCategories: Boolean = false,
        val showEmptyCategoriesSearch: Boolean = false,
    )

    /** Entry cover badge preferences. */
    data class BadgePrefs(
        val unread: Boolean = true,
        val download: Boolean = false,
        val local: Boolean = true,
        val language: Boolean = true,
        val source: Boolean = true,
        val useLangIcon: Boolean = true,
    )

    /** Library sorting and grouping preferences. */
    data class SortPrefs(
        val sort: LibrarySort = LibrarySort.default,
        val randomSeed: Int = 0,
        val categorized: Boolean = false,
        /** The "Tag sorting" tags (SY), in their order */
        val sortTags: List<String> = emptyList(),
        // ZOUKAN -->
        /** How the tabs split the library: by category, source, status or tracking status (SY) */
        val group: Int = LibraryGroup.BY_DEFAULT,
        // ZOUKAN <--
    )

    /** Category inclusion and exclusion filter preferences (KMK). */
    data class CategoryFilterPrefs(
        val enabled: Boolean = false,
        val included: Set<Long> = emptySet(),
        val excluded: Set<Long> = emptySet(),
    )

    /** Library filter preferences across status, custom interval, lewd, categories, and trackers. */
    data class FilterPrefs(
        val downloaded: TriState = TriState.DISABLED,
        val unread: TriState = TriState.DISABLED,
        val started: TriState = TriState.DISABLED,
        val bookmarked: TriState = TriState.DISABLED,
        val completed: TriState = TriState.DISABLED,
        // KMK -->
        /** "Customized update frequency": entries with their own update interval */
        val intervalCustom: TriState = TriState.DISABLED,
        /** The interval filter applies only with the "outside the release period" update restriction */
        val skipOutsideReleasePeriod: Boolean = false,
        val lewd: TriState = TriState.DISABLED,
        val categories: CategoryFilterPrefs = CategoryFilterPrefs(),
        // KMK <--
        val tracking: Map<Long, TriState> = emptyMap(),
    ) {
        val hasFilter: Boolean
            get() = listOf(downloaded, unread, started, bookmarked, completed, intervalCustom, lewd).any { it != TriState.DISABLED } ||
                tracking.values.any { it != TriState.DISABLED } ||
                categories.enabled
    }

    /** The library preferences that change what is shown. */
    data class Settings(
        val displayPrefs: DisplayPrefs = DisplayPrefs(),
        val badgePrefs: BadgePrefs = BadgePrefs(),
        val sortPrefs: SortPrefs = SortPrefs(),
        val filterPrefs: FilterPrefs = FilterPrefs(),
    ) {
        val display: LibraryDisplayMode get() = displayPrefs.mode
        val columns: Int get() = displayPrefs.columns
        val sort: LibrarySort get() = sortPrefs.sort
        val randomSeed: Int get() = sortPrefs.randomSeed
        val categorized: Boolean get() = sortPrefs.categorized
        val downloaded: TriState get() = filterPrefs.downloaded
        val unread: TriState get() = filterPrefs.unread
        val started: TriState get() = filterPrefs.started
        val bookmarked: TriState get() = filterPrefs.bookmarked
        val completed: TriState get() = filterPrefs.completed
        val tracking: Map<Long, TriState> get() = filterPrefs.tracking
        val unreadBadge: Boolean get() = badgePrefs.unread
        val downloadBadge: Boolean get() = badgePrefs.download
        val localBadge: Boolean get() = badgePrefs.local
        val languageBadge: Boolean get() = badgePrefs.language
        val sourceBadge: Boolean get() = badgePrefs.source
        val continueReading: Boolean get() = displayPrefs.continueReading
        val categoryTabs: Boolean get() = displayPrefs.categoryTabs
        val itemCounts: Boolean get() = displayPrefs.itemCounts
        val sortTags: List<String> get() = sortPrefs.sortTags
        val showEmptyCategoriesSearch: Boolean get() = displayPrefs.showEmptyCategoriesSearch
        val group: Int get() = sortPrefs.group
        val intervalCustom: TriState get() = filterPrefs.intervalCustom
        val skipOutsideReleasePeriod: Boolean get() = filterPrefs.skipOutsideReleasePeriod
        val lewd: TriState get() = filterPrefs.lewd
        val filterCategories: Boolean get() = filterPrefs.categories.enabled
        val includedCategories: Set<Long> get() = filterPrefs.categories.included
        val excludedCategories: Set<Long> get() = filterPrefs.categories.excluded
        val showHiddenCategories: Boolean get() = displayPrefs.showHiddenCategories
        val useLangIcon: Boolean get() = badgePrefs.useLangIcon

        val hasFilter: Boolean get() = filterPrefs.hasFilter

        /** KMK: filtering by included categories shows the library ungrouped */
        val effectiveGroup: Int
            get() = if (filterCategories && includedCategories.isNotEmpty()) LibraryGroup.UNGROUPED else group
    }

    /** One library entry with what its badges show. */
    data class Item(
        val library: LibraryManga,
        val downloadCount: Int,
        val sourceName: String,
        val language: String,
        val isLocal: Boolean,
        // KMK -->
        /** The source's extension icon, for the source badge */
        val sourceIconUrl: String? = null,
        /** The source is not installed */
        val isStubSource: Boolean = false,
        // KMK <--
    ) {
        val id: Long get() = library.id
        val manga: Manga get() = library.manga
    }

    data class State(
        val loading: Boolean = true,
        val items: List<LibraryManga> = emptyList(),
        val categories: List<Category> = emptyList(),
        val selectedCategory: Long? = null,
        val query: String = "",
        val settings: Settings = Settings(),
        val tracks: Map<Long, List<Track>> = emptyMap(),
        val downloadCounts: Map<Long, Int> = emptyMap(),
        val selection: Set<Long> = emptySet(),
        val sources: Map<Long, Pair<String, String>> = emptyMap(),
        // ZOUKAN -->
        /** Each entry's tracking statuses, for grouping by tracking status */
        val trackStatuses: Map<Long, List<TrackStatus>> = emptyMap(),
        // ZOUKAN <--
        // SY -->
        /** The entries that match [query] (SY's library search), null before the first search */
        val searchResults: Set<Long>? = null,
        // SY <--
        // KMK -->
        /** Source id to its extension's icon */
        val sourceIcons: Map<Long, String> = emptyMap(),
        /** Sources that are not installed */
        val stubSources: Set<Long> = emptySet(),
        // KMK <--
        /** Active modal dialog, if any; at most one dialog can be active at a time. */
        val dialog: LibraryDialog? = null,
        /** Immutable projected view data computed by [LibraryViewProjector]. */
        val view: LibraryViewProjection = LibraryViewProjection(),
    ) {
        fun viewInput(): LibraryViewInput = LibraryViewInput(
            items = items,
            categories = categories,
            selectedCategory = selectedCategory,
            query = query,
            settings = settings,
            tracks = tracks,
            downloadCounts = downloadCounts,
            sources = sources,
            trackStatuses = trackStatuses,
            searchResults = searchResults,
            sourceIcons = sourceIcons,
            stubSources = stubSources,
        )

        /** True when [other] has identical view inputs: only selection, loading, or dialog may differ. */
        fun sameView(other: State): Boolean = viewInput() == other.viewInput()

        val grouped: Boolean get() = LibraryViewProjector.isGrouped(settings)
        val groups: List<Pair<Category, Set<Long>>> get() = view.groups
        val tabs: List<Category> get() = view.tabs
        val entryCount: Int get() = view.entryCount
        val visible: List<Item> get() = view.visible

        /**
         * Whether the selection bar shows Download: as Android's LibraryTab, only when no selected
         * entry is from the local source, whose chapters are files already and cannot be downloaded.
         */
        val selectionCanDownload: Boolean
            get() = LibraryViewProjector.selectionCanDownload(items, selection)

        /** Whether a selected entry is not from the local source, so Delete downloads can delete something */
        val selectionHasRemote: Boolean
            get() = LibraryViewProjector.selectionHasRemote(items, selection)

        val selectedCategoryModel: Category? get() = categories.firstOrNull { it.id == selectedCategory }.takeIf { !grouped }

        /** The sort in effect: the category's own when categories keep their own settings. */
        val sort: LibrarySort
            get() = selectedCategoryModel?.takeIf { settings.categorized }?.sort ?: settings.sort

        fun countIn(category: Long?): Int = view.countIn(category)

        // ZOUKAN -->
        /**
         * What updating [category] updates while the library is grouped, as LibraryUpdateJob's
         * addMangaToQueue under SY's groupLibraryUpdateType: null for the global update (by the
         * library update categories), or the ids of the tab's entries; the whole library when
         * ungrouped.
         */
        fun groupUpdateIds(category: Long?, mode: GroupLibraryMode): Set<Long>? =
            LibraryViewProjector.groupUpdateIds(viewInput(), category, mode, view.groups)
        // ZOUKAN <--
    }

    private val loadingFlow = MutableStateFlow(true)
    private val viewInputs = MutableStateFlow(LibraryViewInput())

    init {
        // Project the library view on a background thread whenever inputs change, keeping selection/dialog updates fast
        screenModelScope.launch(Dispatchers.Default) {
            combine(loadingFlow, viewInputs) { isLoading, input -> isLoading to input }
                .distinctUntilChanged()
                .collectLatest { (isLoading, input) ->
                    val projected = LibraryViewProjector.project(input, withCounts = input.settings.itemCounts)
                    mutableState.update { current ->
                        val nextSelection = if (input.items === current.items) {
                            current.selection
                        } else {
                            val ids = input.items.mapTo(HashSet()) { it.id }
                            current.selection.filterTo(HashSet()) { id -> id in ids }
                        }
                        current.copy(
                            loading = isLoading,
                            items = input.items,
                            categories = input.categories,
                            selectedCategory = input.selectedCategory,
                            settings = input.settings,
                            tracks = input.tracks,
                            downloadCounts = input.downloadCounts,
                            sources = input.sources,
                            trackStatuses = input.trackStatuses,
                            searchResults = input.searchResults,
                            sourceIcons = input.sourceIcons,
                            stubSources = input.stubSources,
                            selection = nextSelection,
                            view = projected,
                        )
                    }
                }
        }
        screenModelScope.launch {
            // Download counts come from the same items they describe, so the two always match
            combine(getLibraryManga.subscribe(), getCategories.subscribe(), downloadManager.changes) { items, categories, _ ->
                items to categories
            }
                .collectLatest { (items, categories) ->
                    val counts = withContext(Dispatchers.IO) { downloadCounts(items) }
                    val sourceModels = items.map { it.manga.source }.distinct().associateWith { sourceManager.getOrStub(it) }
                    val sources = sourceModels.mapValues { (_, source) -> source.name to source.lang }
                    loadingFlow.value = false
                    viewInputs.update {
                        it.copy(
                            items = items,
                            categories = categories,
                            downloadCounts = counts,
                            sources = sources,
                            stubSources = sourceModels.filterValues { source -> source is StubSource }.keys,
                        )
                    }
                }
        }
        screenModelScope.launch {
            getTracksPerManga.subscribe().collect { tracks ->
                // ZOUKAN -->
                val statuses = tracks.mapValues { (_, list) ->
                    list.mapNotNull { TrackStatus.parseTrackerStatus(trackerManager, it.trackerId, it.status) }.distinct()
                }
                viewInputs.update { it.copy(tracks = tracks, trackStatuses = statuses) }
                // ZOUKAN <--
            }
        }
        screenModelScope.launch {
            settingsFlow().collect { settings ->
                viewInputs.update {
                    // A new grouping has other tabs, so it starts from All
                    val tab = if (settings.effectiveGroup != it.settings.effectiveGroup) null else it.selectedCategory
                    it.copy(settings = settings, selectedCategory = tab)
                }
            }
        }
        // SY -->
        screenModelScope.launch {
            combine(
                state.map { it.query }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                viewInputs.map { it.items }.distinctUntilChanged(),
                viewInputs.map { it.tracks }.distinctUntilChanged(),
                ::Triple,
            ).collectLatest { (query, items, tracks) ->
                val results = if (query.isBlank()) null else withContext(Dispatchers.IO) { librarySearch.search(items, query, tracks) }
                viewInputs.update { it.copy(searchResults = results) }
            }
        }
        // SY <--
        // KMK -->
        screenModelScope.launch {
            // The source badge's icon: the icon of the installed extension the source comes from
            combine(extensionManager.installedExtensionsFlow, extensionManager.availableExtensionsFlow) { installed, available ->
                installed.values.flatMap { extension ->
                    val icon = available[extension.pkgName]?.iconUrl ?: return@flatMap emptyList()
                    extension.sources.map { it.id to icon }
                }.toMap()
            }.collect { icons -> viewInputs.update { it.copy(sourceIcons = icons) } }
        }
        // KMK <--
    }

    private fun downloadCounts(items: List<LibraryManga>): Map<Long, Int> =
        runCatching { downloadManager.downloadCounts(items.map { it.manga }) }.getOrDefault(emptyMap())

    /** The trackers the library can filter by: the ones signed in. */
    fun trackers() = trackerManager.loggedInTrackers()

    private fun settingsFlow(): Flow<Settings> =
        combine(
            displayPrefsFlow(),
            badgePrefsFlow(),
            sortPrefsFlow(),
            filterPrefsFlow(),
            ::Settings,
        )

    private fun displayPrefsFlow(): Flow<DisplayPrefs> {
        val p = preferences
        val layoutFlow = combine(
            p.displayMode().changes(),
            p.landscapeColumns().changes(),
            p.showContinueReadingButton().changes(),
            p.categoryTabs().changes(),
            p.categoryNumberOfItems().changes(),
        ) { mode, columns, continueReading, categoryTabs, itemCounts ->
            DisplayPrefs(
                mode = mode,
                columns = columns,
                continueReading = continueReading,
                categoryTabs = categoryTabs,
                itemCounts = itemCounts,
            )
        }
        return combine(
            layoutFlow,
            p.showHiddenCategories().changes(),
            p.showEmptyCategoriesSearch().changes(),
        ) { base, showHidden, showEmptySearch ->
            base.copy(
                showHiddenCategories = showHidden,
                showEmptyCategoriesSearch = showEmptySearch,
            )
        }
    }

    private fun badgePrefsFlow(): Flow<BadgePrefs> {
        val p = preferences
        val coreBadgesFlow = combine(
            p.unreadBadge().changes(),
            p.downloadBadge().changes(),
            p.localBadge().changes(),
            p.languageBadge().changes(),
            p.sourceBadge().changes(),
        ) { unread, download, local, language, source ->
            BadgePrefs(
                unread = unread,
                download = download,
                local = local,
                language = language,
                source = source,
            )
        }
        return combine(coreBadgesFlow, p.useLangIcon().changes()) { base, useLangIcon ->
            base.copy(useLangIcon = useLangIcon)
        }
    }

    private fun sortPrefsFlow(): Flow<SortPrefs> {
        val p = preferences
        return combine(
            p.sortingMode().changes(),
            p.randomSortSeed().changes(),
            p.categorizedDisplaySettings().changes(),
            p.sortTagsForLibrary().changes().map { GetSortTag.mapSortTags(it) },
            p.groupLibraryBy().changes(),
            ::SortPrefs,
        )
    }

    private fun categoryFilterPrefsFlow(): Flow<CategoryFilterPrefs> {
        val p = preferences
        return combine(
            p.filterCategories().changes(),
            p.filterCategoriesInclude().changes().map { ids -> ids.mapNotNull { it.toLongOrNull() }.toSet() },
            p.filterCategoriesExclude().changes().map { ids -> ids.mapNotNull { it.toLongOrNull() }.toSet() },
            ::CategoryFilterPrefs,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun trackingFiltersFlow(): Flow<Map<Long, TriState>> {
        val p = preferences
        return trackerManager.loggedInTrackersFlow()
            .map { trackers -> trackers.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { trackerIds ->
                if (trackerIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val trackerFlows = trackerIds.map { id ->
                        p.filterTracking(id.toInt()).changes().map { id to it }
                    }
                    combine(trackerFlows) { entries -> entries.toMap() }
                }
            }
    }

    private fun filterPrefsFlow(): Flow<FilterPrefs> {
        val p = preferences
        // ZOUKAN -->
        // More > Downloaded only shows only downloaded entries, as Android's library
        val downloadedFlow = combine(
            p.filterDownloaded().changes(),
            desktopPreferences.value.downloadedOnly().changes(),
        ) { filter, downloadedOnly ->
            if (downloadedOnly) TriState.ENABLED_IS else filter
        }
        // ZOUKAN <--
        val statusFiltersFlow = combine(
            downloadedFlow,
            p.filterUnread().changes(),
            p.filterStarted().changes(),
            p.filterBookmarked().changes(),
            p.filterCompleted().changes(),
        ) { downloaded, unread, started, bookmarked, completed ->
            FilterPrefs(
                downloaded = downloaded,
                unread = unread,
                started = started,
                bookmarked = bookmarked,
                completed = completed,
            )
        }
        val extraFiltersFlow = combine(
            p.filterIntervalCustom().changes(),
            p.autoUpdateMangaRestrictions().changes().map { LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in it },
            p.filterLewd().changes(),
            ::Triple,
        )
        return combine(
            statusFiltersFlow,
            extraFiltersFlow,
            categoryFilterPrefsFlow(),
            trackingFiltersFlow(),
        ) { base, (intervalCustom, skipOutsideReleasePeriod, lewd), categories, tracking ->
            base.copy(
                intervalCustom = intervalCustom,
                skipOutsideReleasePeriod = skipOutsideReleasePeriod,
                lewd = lewd,
                categories = categories,
                tracking = tracking,
            )
        }
    }

    fun openDialog(dialog: LibraryDialog) = mutableState.update { it.copy(dialog = dialog) }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    fun search(query: String) {
        mutableState.update { it.copy(query = query) }
        viewInputs.update { it.copy(query = query) }
    }

    fun selectCategory(id: Long?) {
        viewInputs.update { it.copy(selectedCategory = id) }
    }

    fun setSort(type: LibrarySort.Type) {
        val current = state.value.sort
        // Picking the current sort again flips its direction, as on Android
        val direction = if (current.type == type) {
            if (current.isAscending) LibrarySort.Direction.Descending else LibrarySort.Direction.Ascending
        } else {
            LibrarySort.Direction.Ascending
        }
        screenModelScope.launch { setSortModeForCategory.await(state.value.selectedCategory, type, direction) }
    }

    fun reshuffle() = preferences.randomSortSeed().set(Random.nextInt())

    /** Updates the open category, or the library (by the library update settings) from "All". */
    fun refresh() {
        val category = state.value.selectedCategory
        // ZOUKAN -->
        // A source, status or tracking tab updates the entries in it, or the whole library, as SY's
        // "Library dynamic category updates" (groupLibraryUpdateType) says
        val started = if (state.value.grouped) {
            val groupIds = state.value.groupUpdateIds(category, preferences.groupLibraryUpdateType().get())
            libraryUpdater.value.start(groupIds = groupIds)
        } else {
            libraryUpdater.value.start(categoryId = category)
        }
        // ZOUKAN <--
        if (!started) Toasts.show(localized(DMR.strings.a_library_update_is_already_running))
    }

    // Selection ---------------------------------------------------------------------------------

    private var anchor: Long? = null

    fun toggleSelection(id: Long) {
        anchor = id
        mutableState.update { it.copy(selection = toggledSelection(it.selection, id)) }
    }

    /** Selects from the last selected entry to [id], as Shift + click does in a file manager. */
    fun selectRange(id: Long) {
        val range = rangeSelection(state.value.visible.map { it.id }, anchor, id) ?: return toggleSelection(id)
        mutableState.update { it.copy(selection = it.selection + range) }
    }

    fun selectAll() = mutableState.update { s -> s.copy(selection = s.selection + s.visible.map { it.id }) }

    fun invertSelection() = mutableState.update { s -> s.copy(selection = s.visible.map { it.id }.toSet() - s.selection) }

    fun clearSelection() = mutableState.update { it.copy(selection = emptySet()) }

    /**
     * Ends an action on [ids]: an action on the selection clears it, and one from the context menu
     * of an entry outside the selection leaves the selection as it was.
     */
    private fun done(ids: Set<Long>) {
        if (ids == state.value.selection) clearSelection()
    }

    private fun selected(ids: Set<Long>): List<Manga> {
        return state.value.items.filter { it.id in ids }.distinctBy { it.id }.map { it.manga }
    }

    /** The categories every selected entry has, and the ones only some have. */
    fun selectionCategories(ids: Set<Long> = state.value.selection): Pair<Set<Long>, Set<Long>> {
        val per = state.value.items.filter { it.id in ids }.groupBy { it.id }.map { (_, rows) -> rows.flatMap { it.categories }.toSet() }
        if (per.isEmpty()) return emptySet<Long>() to emptySet()
        val all = per.reduce { a, b -> a intersect b }
        val some = per.reduce { a, b -> a union b } - all
        return all to some
    }

    fun setCategories(add: Set<Long>, remove: Set<Long>, ids: Set<Long> = state.value.selection) {
        val current = state.value.items.filter { it.id in ids }.groupBy { it.id }
            .mapValues { (_, rows) -> rows.flatMap { it.categories }.toSet() }
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_set_categories) }) {
            current.forEach { (id, categories) ->
                val updated = (categories - remove + add).filter { it != Category.UNCATEGORIZED_ID }
                setMangaCategories.await(id, updated)
            }
            done(ids)
        }
    }

    private suspend fun chaptersOf(manga: Manga): List<Chapter> =
        if (manga.source == MERGED_SOURCE_ID) getMergedChaptersByMangaId.await(manga.id) else getChaptersByMangaId.await(manga.id)

    fun markRead(read: Boolean, ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids)
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_mark_read) }) {
            mangas.forEach { manga ->
                val changed = chaptersOf(manga).filter { it.read != read }
                val updates = changed.map { ChapterUpdate(id = it.id, read = read, lastPageRead = if (read) null else 0L) }
                updateChapter.awaitAll(updates)
                // ZOUKAN -->
                if (read) downloadManager.afterMarkedRead(changed)
                // ZOUKAN <--
            }
            done(ids)
        }
    }

    /** Queues the next [count] unread chapters of each selected entry, or all unread when null. */
    fun download(count: Int?, ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids).filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            // The manager skips chapters that are downloaded or queued, and reads folders off the UI thread
            downloadManager.enqueueAll(
                mangas.flatMap { manga ->
                    val unread = chaptersOf(manga).filter { !it.read }.sortedByDescending { it.sourceOrder }
                        .let { list -> if (count == null) list else list.take(count) }
                    byEntry(manga, unread)
                },
            )
            done(ids)
        }
    }

    fun deleteDownloads(ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids).filter { it.source != LocalSource.ID }
        // A failure (the storage drive unplugged) is shown and keeps the selection, as the other actions do
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_delete_downloads) }) {
            mangas.forEach { manga ->
                byEntry(manga, chaptersOf(manga)).forEach { (entry, chapters) -> downloadManager.delete(entry, chapters) }
            }
            done(ids)
        }
    }

    fun removeFromLibrary(deleteDownloads: Boolean, ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids)
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_remove_from_library) }) {
            mangas.forEach { manga ->
                if (deleteDownloads && manga.source != LocalSource.ID) {
                    byEntry(manga, chaptersOf(manga)).forEach { (entry, chapters) -> downloadManager.delete(entry, chapters) }
                }
                mangaRepository.update(MangaUpdate(id = manga.id, favorite = false, dateAdded = 0))
            }
            Toasts.show(if (mangas.size == 1) localized(MR.strings.manga_removed_library) else localized(DMR.strings.removed_entries_from_library, mangas.size))
            done(ids)
        }
    }

    private suspend fun byEntry(manga: Manga, chapters: List<Chapter>): List<Pair<Manga, List<Chapter>>> =
        chaptersByEntry.group(manga, chapters)

    /** Queues each selected entry's bookmarked chapters (DownloadAction.BOOKMARKED_CHAPTERS). */
    fun downloadBookmarked() {
        val mangas = selected(state.value.selection).filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            downloadManager.enqueueAll(
                mangas.flatMap { manga -> byEntry(manga, chaptersOf(manga).filter { it.bookmark }) },
            )
            clearSelection()
        }
    }

    // SY -->
    /** Whether a selected entry is from E-Hentai, ExHentai or nhentai, for Clean titles. */
    fun showCleanTitles(): Boolean = selectionFlags().cleanTitles

    /** Whether a selected entry is from MangaDex, for Add to MangaDex follows. */
    fun showAddToMangadex(): Boolean = selectionFlags().addToMangadex

    /** Whether a selected entry has edited details, for Reset info. */
    fun showResetInfo(): Boolean = selectionFlags().resetInfo

    /** Which of the selection's overflow-menu actions apply, found in one pass over the library. */
    data class SelectionFlags(val cleanTitles: Boolean, val addToMangadex: Boolean, val resetInfo: Boolean)

    fun selectionFlags(state: State = this.state.value): SelectionFlags =
        LibraryViewProjector.selectionFlags(state.items, state.selection)

    /** Removes bracketed tags (groups, events, languages) from gallery titles, as custom titles. */
    fun cleanTitles() {
        val regex1 = "\\[.*?]".toRegex()
        val regex2 = "\\(.*?\\)".toRegex()
        val regex3 = "\\{.*?\\}".toRegex()
        val regex4 = ".*\\|".toRegex()
        val mangas = selected(state.value.selection).filter { it.isEhBasedManga() || it.source in nHentaiSourceIds }
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_clean_titles) }) {
            mangas.forEach { manga ->
                val editedTitle = manga.title
                    .replace(regex1, "").trim()
                    .replace(regex2, "").trim()
                    .replace(regex3, "").trim()
                    .let {
                        if (it.contains("|")) {
                            it.replace(regex4, "").trim()
                        } else {
                            it
                        }
                    }
                if (manga.title == editedTitle) return@forEach
                val mangaInfo = CustomMangaInfo(
                    id = manga.id,
                    title = editedTitle.ifBlank { null },
                    author = manga.author.takeUnless { it == manga.ogAuthor },
                    artist = manga.artist.takeUnless { it == manga.ogArtist },
                    thumbnailUrl = manga.thumbnailUrl.takeUnless { it == manga.ogThumbnailUrl },
                    description = manga.description.takeUnless { it == manga.ogDescription },
                    genre = manga.genre.takeUnless { it == manga.ogGenre },
                    status = manga.status.takeUnless { it == manga.ogStatus },
                )
                setCustomMangaInfo.set(mangaInfo)
                // ZOUKAN -->
                // The library reads custom info again when the entry's row changes (as EditManga)
                mangaRepository.update(MangaUpdate(id = manga.id, lastUpdate = manga.lastUpdate + 1))
                // ZOUKAN <--
            }
            // The selection stays when the action failed
            clearSelection()
        }
    }

    /** Sets the selected MangaDex entries to Reading in the MangaDex follows. */
    fun syncMangaToDex() {
        val mangas = selected(state.value.selection).filter { it.source in mangaDexSourceIds }
        screenModelScope.launch(Dispatchers.IO) {
            zoukan.desktop.app.runCatchingCancellable {
                MdUtil.getEnabledMangaDex(sourcePreferences, sourceManager)?.let { mdex ->
                    mangas.forEach { manga ->
                        mdex.updateFollowStatus(MdUtil.getMangaId(manga.url), FollowStatus.READING)
                    }
                }
            }.onFailure { Toasts.error(localized(DMR.strings.could_not_update_the_mangadex_follows), it) }
            clearSelection()
        }
    }

    /** Puts back the source's details of the selected entries. */
    fun resetInfo() {
        val mangas = selected(state.value.selection)
        screenModelScope.launch {
            mangas.forEach { manga ->
                val mangaInfo = CustomMangaInfo(
                    id = manga.id,
                    title = null,
                    author = null,
                    artist = null,
                    thumbnailUrl = null,
                    description = null,
                    genre = null,
                    status = null,
                )
                setCustomMangaInfo.set(mangaInfo)
                // ZOUKAN -->
                mangaRepository.update(MangaUpdate(id = manga.id, lastUpdate = manga.lastUpdate + 1))
                // ZOUKAN <--
            }
        }
        clearSelection()
    }

    /** The selected entries in the library's order, as Android's selectedManga. */
    fun selectedManga(): List<Manga> = selected(state.value.selection)
    // SY <--

    // KMK -->
    /** Updates the chapters of the selected entries. */
    fun updateSelectedManga(): Boolean {
        val mangaIds = state.value.selection
        return libraryUpdater.value.start(mangaIds = mangaIds)
    }

    /**
     * Will get first merged manga in the list as target merging.
     * If there is no merged manga, then it will use the first one in list to create a new target.
     */
    suspend fun smartSearchMerge(selectedMangas: List<Manga>): Long? {
        val mergedManga = selectedMangas.firstOrNull { it.source == MERGED_SOURCE_ID }?.let { listOf(it) }
            ?: emptyList()
        val mergingMangas = selectedMangas.filterNot { it.source == MERGED_SOURCE_ID }
        val toMergeMangas = mergedManga + mergingMangas
        if (toMergeMangas.size <= 1) return null

        var mergingMangaId = toMergeMangas.first().id
        for (manga in toMergeMangas.drop(1)) {
            mergingMangaId = withContext(Dispatchers.IO) { SmartSearchMerge().smartSearchMerge(manga, mergingMangaId) }.id
        }
        return mergingMangaId
    }

    /**
     * Merges [selectedMangas] in the screen model's scope, so leaving the screen doesn't stop the
     * merge partway, then asks whether to remove the merged entries.
     */
    fun mergeMultiple(selectedMangas: List<Manga>) {
        screenModelScope.launch {
            val mergingMangas = selectedMangas.filterNot { it.source == MERGED_SOURCE_ID }
            val mergedMangaId = try {
                smartSearchMerge(selectedMangas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasts.error(localized(DMR.strings.merging_failed), e)
                return@launch
            }
            if (mergedMangaId != null) {
                Toasts.show(localized(SYMR.strings.entry_merged))
                openDialog(LibraryDialog.RemoveMerged(mergingMangas, mergedMangaId))
            } else {
                Toasts.show(localized(SYMR.strings.merged_references_invalid))
            }
        }
    }

    /** Takes [mangas] out of the library, keeping their downloads (the merge's "Remove" action). */
    fun removeMangas(mangas: List<Manga>) {
        screenModelScope.launchReporting({ localized(DMR.strings.screen_action_failed_remove_from_library) }) {
            mangas.distinctBy { it.id }.forEach { manga ->
                mangaRepository.update(MangaUpdate(id = manga.id, favorite = false, dateAdded = 0))
            }
        }
    }
    // KMK <--

    /** The chapter "continue reading" opens: the next unread one. */
    suspend fun nextChapter(mangaId: Long): Chapter? = getNextChapters.await(mangaId, onlyUnread = true).firstOrNull()

    /** A random entry of what is shown, for "Open a random entry". */
    fun randomEntry(): Long? = state.value.visible.randomOrNull()?.id
}

/** Ctrl/Cmd + click: [selection] with [id] added, or removed when it was selected. */
internal fun toggledSelection(selection: Set<Long>, id: Long): Set<Long> = if (id in selection) selection - id else selection + id

/**
 * Shift + click: the entries of [ids] from [anchor] (the last toggled entry; [id] itself when
 * there is none or it is no longer shown) to [id], or null when [id] is not shown.
 */
internal fun rangeSelection(ids: List<Long>, anchor: Long?, id: Long): List<Long>? {
    val from = ids.indexOf(anchor ?: id).takeIf { it >= 0 } ?: ids.indexOf(id)
    val to = ids.indexOf(id)
    if (from < 0 || to < 0) return null
    return ids.subList(minOf(from, to), maxOf(from, to) + 1)
}
