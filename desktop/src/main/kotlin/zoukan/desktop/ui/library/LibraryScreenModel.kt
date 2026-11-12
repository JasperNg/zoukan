package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedManga
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import exh.util.isLewd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.recs.RecommendationSearchHelper
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

/** As Android's AppBar: the library search waits this long after the last key */
private const val SEARCH_DEBOUNCE_MILLIS = 250L

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
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    /** The library preferences that change what is shown. */
    data class Settings(
        val display: LibraryDisplayMode = LibraryDisplayMode.default,
        val columns: Int = 0,
        val sort: LibrarySort = LibrarySort.default,
        val randomSeed: Int = 0,
        val categorized: Boolean = false,
        val downloaded: TriState = TriState.DISABLED,
        val unread: TriState = TriState.DISABLED,
        val started: TriState = TriState.DISABLED,
        val bookmarked: TriState = TriState.DISABLED,
        val completed: TriState = TriState.DISABLED,
        val tracking: Map<Long, TriState> = emptyMap(),
        val unreadBadge: Boolean = true,
        val downloadBadge: Boolean = false,
        val localBadge: Boolean = true,
        val languageBadge: Boolean = true,
        val sourceBadge: Boolean = true,
        val continueReading: Boolean = false,
        val categoryTabs: Boolean = true,
        val itemCounts: Boolean = false,
        /** The "Tag sorting" tags (SY), in their order */
        val sortTags: List<String> = emptyList(),
        val showEmptyCategoriesSearch: Boolean = false,
        // ZOUKAN -->
        /** How the tabs split the library: by category, source, status or tracking status (SY) */
        val group: Int = LibraryGroup.BY_DEFAULT,
        // ZOUKAN <--
        // KMK -->
        /** "Customized update frequency": entries with their own update interval */
        val intervalCustom: TriState = TriState.DISABLED,
        /** The interval filter applies only with the "outside the release period" update restriction */
        val skipOutsideReleasePeriod: Boolean = false,
        val lewd: TriState = TriState.DISABLED,
        val filterCategories: Boolean = false,
        val includedCategories: Set<Long> = emptySet(),
        val excludedCategories: Set<Long> = emptySet(),
        val showHiddenCategories: Boolean = false,
        val useLangIcon: Boolean = true,
        // KMK <--
    ) {
        val hasFilter: Boolean
            get() = listOf(downloaded, unread, started, bookmarked, completed, intervalCustom, lewd).any { it != TriState.DISABLED } ||
                tracking.values.any { it != TriState.DISABLED } ||
                filterCategories

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
    ) {
        val grouped: Boolean get() = settings.effectiveGroup != LibraryGroup.BY_DEFAULT

        // ZOUKAN -->
        /**
         * The tabs when the library is grouped by source, status or tracking status, with the entries
         * in each, as Android's getGroupedMangaItems. Ungrouped has none, so only All shows.
         */
        val groups: List<Pair<Category, Set<Long>>>
            get() {
                fun tab(id: Long, name: String, order: Long) = Category(id = id, name = name, order = order, flags = 0, hidden = false)
                return when (settings.effectiveGroup) {
                    LibraryGroup.BY_SOURCE -> items.groupBy { it.manga.source }
                        .map { (source, list) ->
                            val name = if (source == LocalSource.ID) localized(MR.strings.action_display_local_badge) else sources[source]?.first?.ifBlank { null } ?: source.toString()
                            name to (source to list)
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
                        .mapIndexed { i, (name, pair) -> tab(pair.first, name, i.toLong()) to pair.second.mapTo(HashSet()) { it.id } }
                    LibraryGroup.BY_STATUS -> items.groupBy { it.manga.status }
                        .map { (status, list) ->
                            val (name, order) = STATUS_GROUPS[status] ?: (localized(MR.strings.unknown) to 7L)
                            tab(status + 1, name, order) to list.mapTo(HashSet()) { it.id }
                        }
                        .sortedBy { it.first.order }
                    LibraryGroup.BY_TRACK_STATUS -> {
                        val members = mutableMapOf<TrackStatus, MutableSet<Long>>()
                        items.forEach { item ->
                            trackStatuses[item.id].orEmpty().ifEmpty { listOf(TrackStatus.OTHER) }
                                .forEach { members.getOrPut(it) { HashSet() }.add(item.id) }
                        }
                        members.entries.sortedBy { it.key.ordinal }
                            .map { (status, ids) -> tab(status.int.toLong(), localized(status.res), status.ordinal.toLong()) to ids }
                    }
                    else -> emptyList()
                }
            }
        // ZOUKAN <--

        /**
         * Category tabs: the user's categories, plus Default when entries have no category. While
         * searching or filtering, categories with no match are left out unless "Show empty
         * categories during search/filtering" is on, as Android's (KMK).
         */
        val tabs: List<Category>
            get() {
                // ZOUKAN -->
                if (grouped) return groups.map { it.first }
                // ZOUKAN <--
                val hasUncategorized = items.any { it.categories.isEmpty() || Category.UNCATEGORIZED_ID in it.categories }
                // KMK: hidden categories show with "Show hidden categories"
                val shown = categories.filter { (settings.showHiddenCategories || !it.hidden) && (!it.isSystemCategory || hasUncategorized) }
                val searching = query.isNotBlank() || settings.hasFilter
                if (!searching || settings.showEmptyCategoriesSearch) return shown
                return shown.filter { countIn(it.id) > 0 }
            }

        val selectedCategoryModel: Category? get() = categories.firstOrNull { it.id == selectedCategory }.takeIf { !grouped }

        /** The sort in effect: the category's own when categories keep their own settings. */
        val sort: LibrarySort
            get() = selectedCategoryModel?.takeIf { settings.categorized }?.sort ?: settings.sort

        fun inCategory(category: Long?): List<LibraryManga> = when {
            category == null -> items
            // ZOUKAN -->
            grouped -> groups.firstOrNull { it.first.id == category }?.second
                .orEmpty().let { ids -> items.filter { it.id in ids } }
            // ZOUKAN <--
            category == Category.UNCATEGORIZED_ID -> items.filter { it.categories.isEmpty() || category in it.categories }
            else -> items.filter { category in it.categories }
        }.distinctBy { it.id }

        fun countIn(category: Long?): Int = inCategory(category).count { passes(it) }

        // ZOUKAN -->
        /**
         * What updating [category] updates while the library is grouped, as LibraryUpdateJob's
         * addMangaToQueue under SY's groupLibraryUpdateType: null for the global update (by the
         * library update categories), or the ids of the tab's entries; the whole library when
         * ungrouped.
         */
        fun groupUpdateIds(category: Long?, mode: GroupLibraryMode): Set<Long>? {
            val group = settings.effectiveGroup
            val global = mode == GroupLibraryMode.GLOBAL ||
                (mode == GroupLibraryMode.ALL_BUT_UNGROUPED && group == LibraryGroup.UNGROUPED)
            return when {
                global -> null
                group == LibraryGroup.UNGROUPED -> inCategory(null).mapTo(HashSet()) { it.id }
                category == null -> null
                else -> inCategory(category).mapTo(HashSet()) { it.id }
            }
        }
        // ZOUKAN <--

        private fun TriState.test(value: Boolean): Boolean = when (this) {
            TriState.DISABLED -> true
            TriState.ENABLED_IS -> value
            TriState.ENABLED_NOT -> !value
        }

        fun passes(item: LibraryManga): Boolean {
            val s = settings
            // SY: the search runs in the background; until its first result, nothing is hidden
            if (query.isNotBlank() && searchResults != null && item.id !in searchResults) return false
            val isLocal = item.manga.source == LocalSource.ID
            if (!s.downloaded.test(isLocal || (downloadCounts[item.id] ?: 0) > 0)) return false
            if (!s.unread.test(item.unreadCount > 0)) return false
            if (!s.started.test(item.hasStarted)) return false
            if (!s.bookmarked.test(item.hasBookmarks)) return false
            if (!s.completed.test(item.manga.status.toInt() == eu.kanade.tachiyomi.source.model.SManga.COMPLETED)) return false
            // KMK -->
            if (s.skipOutsideReleasePeriod && !s.intervalCustom.test(item.manga.fetchInterval < 0)) return false
            if (s.lewd != TriState.DISABLED && !s.lewd.test(item.manga.isLewd())) return false
            return passesTracking(item) && passesCategories(item)
        }

        /** KMK's tracking filter: no excluded tracker, and one of the included ones if any */
        private fun passesTracking(item: LibraryManga): Boolean {
            val excludedTracks = settings.tracking.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
            val includedTracks = settings.tracking.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
            if (includedTracks.isEmpty() && excludedTracks.isEmpty()) return true
            val mangaTracks = tracks[item.id].orEmpty()
            val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.any { it.trackerId in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || mangaTracks.any { it.trackerId in includedTracks }
            return !isExcluded && isIncluded
        }

        /** KMK's category filter: in every included category and in no excluded one */
        private fun passesCategories(item: LibraryManga): Boolean {
            if (!settings.filterCategories) return true
            val mangaCategories = item.categories.filterNot { it == 0L }.toSet()
            if (mangaCategories.isEmpty()) return settings.includedCategories.isEmpty()
            val isExcluded = settings.excludedCategories.any { it in mangaCategories }
            val isIncluded = settings.includedCategories.isEmpty() || settings.includedCategories.all { it in mangaCategories }
            return !isExcluded && isIncluded
        }
        // KMK <--

        val visible: List<Item>
            get() {
                val filtered = inCategory(selectedCategory).filter(::passes)
                return sorted(filtered).map { lm ->
                    val (name, lang) = sources[lm.manga.source] ?: ("" to "")
                    Item(
                        lm,
                        downloadCounts[lm.id] ?: 0,
                        name,
                        lang,
                        lm.manga.source == LocalSource.ID,
                        sourceIconUrl = sourceIcons[lm.manga.source],
                        isStubSource = lm.manga.source in stubSources,
                    )
                }
            }

        private fun sorted(list: List<LibraryManga>): List<LibraryManga> {
            val sort = sort
            if (sort.type == LibrarySort.Type.Random) return list.shuffled(Random(settings.randomSeed))
            val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
            val byTitle = Comparator<LibraryManga> { a, b -> collator.compare(a.manga.title, b.manga.title) }
            val comparator: Comparator<LibraryManga> = when (sort.type) {
                LibrarySort.Type.LastRead -> compareBy { it.lastRead }
                LibrarySort.Type.LastUpdate -> compareBy { it.manga.lastUpdate }
                LibrarySort.Type.UnreadCount -> Comparator { a, b ->
                    when {
                        a.unreadCount == b.unreadCount -> 0
                        // Unread entries first, either way
                        a.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                        b.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                        else -> a.unreadCount.compareTo(b.unreadCount)
                    }
                }
                LibrarySort.Type.TotalChapters -> compareBy { it.totalChapters }
                LibrarySort.Type.LatestChapter -> compareBy { it.latestUpload }
                LibrarySort.Type.ChapterFetchDate -> compareBy { it.chapterFetchedAt }
                LibrarySort.Type.DateAdded -> compareBy { it.manga.dateAdded }
                LibrarySort.Type.TrackerMean -> compareBy { meanScore(it.id) }
                // SY's tag sorting: by the first sort tag the entry has; entries without one first
                LibrarySort.Type.TagList -> compareBy { item ->
                    val genres = item.manga.genre.orEmpty()
                    settings.sortTags.indexOfFirst { it in genres }
                }
                else -> byTitle
            }
            return list.sortedWith((if (sort.isAscending) comparator else comparator.reversed()).thenComparing(byTitle))
        }

        private fun meanScore(id: Long): Double =
            tracks[id].orEmpty().filter { it.score > 0 }.map { it.score }.average().takeUnless { it.isNaN() } ?: -1.0
    }

    init {
        screenModelScope.launch {
            // Download counts come from the same items they describe, so the two always match
            combine(getLibraryManga.subscribe(), getCategories.subscribe(), downloadManager.changes) { items, categories, _ ->
                items to categories
            }
                .collectLatest { (items, categories) ->
                    val counts = withContext(Dispatchers.IO) { downloadCounts(items) }
                    val sourceModels = items.map { it.manga.source }.distinct().associateWith { sourceManager.getOrStub(it) }
                    val sources = sourceModels.mapValues { (_, source) -> source.name to source.lang }
                    val ids = items.mapTo(HashSet()) { it.id }
                    mutableState.update {
                        it.copy(
                            loading = false,
                            items = items,
                            categories = categories,
                            downloadCounts = counts,
                            sources = sources,
                            stubSources = sourceModels.filterValues { source -> source is StubSource }.keys,
                            // Entries that left the library leave the selection too
                            selection = it.selection.filterTo(HashSet()) { id -> id in ids },
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
                mutableState.update { it.copy(tracks = tracks, trackStatuses = statuses) }
                // ZOUKAN <--
            }
        }
        screenModelScope.launch {
            settingsFlow().collect { settings ->
                mutableState.update {
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
                state.map { it.items }.distinctUntilChanged(),
                state.map { it.tracks }.distinctUntilChanged(),
                ::Triple,
            ).collectLatest { (query, items, tracks) ->
                val results = if (query.isBlank()) null else withContext(Dispatchers.IO) { librarySearch.search(items, query, tracks) }
                mutableState.update { it.copy(searchResults = results) }
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
            }.collect { icons -> mutableState.update { it.copy(sourceIcons = icons) } }
        }
        // KMK <--
    }

    private fun downloadCounts(items: List<LibraryManga>): Map<Long, Int> =
        items.distinctBy { it.id }.associate { it.id to runCatching { downloadManager.downloadCount(it.manga) }.getOrDefault(0) }

    /** The trackers the library can filter by: the ones signed in. */
    fun trackers() = trackerManager.loggedInTrackers()

    private fun settingsFlow(): Flow<Settings> {
        val p = preferences
        // KMK -->
        val extra = combine(
            listOf<Flow<Any>>(
                p.filterIntervalCustom().changes(),
                p.autoUpdateMangaRestrictions().changes(),
                p.filterLewd().changes(),
                p.filterCategories().changes(),
                p.filterCategoriesInclude().changes(),
                p.filterCategoriesExclude().changes(),
                p.showHiddenCategories().changes(),
                p.useLangIcon().changes(),
            ),
        ) { v ->
            { s: Settings ->
                s.copy(
                    intervalCustom = v[0] as TriState,
                    skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (v[1] as Set<*>),
                    lewd = v[2] as TriState,
                    filterCategories = v[3] as Boolean,
                    includedCategories = (v[4] as Set<*>).mapNotNull { (it as String).toLongOrNull() }.toSet(),
                    excludedCategories = (v[5] as Set<*>).mapNotNull { (it as String).toLongOrNull() }.toSet(),
                    showHiddenCategories = v[6] as Boolean,
                    useLangIcon = v[7] as Boolean,
                )
            }
        }
        return combine(baseSettingsFlow(), extra) { settings, apply -> apply(settings) }
        // KMK <--
    }

    private fun baseSettingsFlow(): Flow<Settings> {
        val p = preferences
        val trackerIds = trackerManager.loggedInTrackers().map { it.id }
        val flows: List<Flow<Any>> = listOf(
            p.displayMode().changes(), p.landscapeColumns().changes(), p.sortingMode().changes(),
            p.randomSortSeed().changes(), p.categorizedDisplaySettings().changes(),
            p.filterDownloaded().changes(), p.filterUnread().changes(), p.filterStarted().changes(),
            p.filterBookmarked().changes(), p.filterCompleted().changes(),
            p.unreadBadge().changes(), p.downloadBadge().changes(), p.localBadge().changes(),
            p.languageBadge().changes(), p.sourceBadge().changes(), p.showContinueReadingButton().changes(),
            p.categoryTabs().changes(), p.categoryNumberOfItems().changes(),
            // ZOUKAN -->
            // More > Downloaded only shows only downloaded entries, as Android's library
            Injekt.get<zoukan.desktop.ui.common.DesktopPreferences>().downloadedOnly().changes(),
            p.groupLibraryBy().changes(),
            // ZOUKAN <--
            p.sortTagsForLibrary().changes(), p.showEmptyCategoriesSearch().changes(),
        ) + trackerIds.map { p.filterTracking(it.toInt()).changes() }
        return combine(flows) { v ->
            Settings(
                display = v[0] as LibraryDisplayMode,
                columns = v[1] as Int,
                sort = v[2] as LibrarySort,
                randomSeed = v[3] as Int,
                categorized = v[4] as Boolean,
                downloaded = if (v[18] as Boolean) TriState.ENABLED_IS else v[5] as TriState,
                unread = v[6] as TriState,
                started = v[7] as TriState,
                bookmarked = v[8] as TriState,
                completed = v[9] as TriState,
                unreadBadge = v[10] as Boolean,
                downloadBadge = v[11] as Boolean,
                localBadge = v[12] as Boolean,
                languageBadge = v[13] as Boolean,
                sourceBadge = v[14] as Boolean,
                continueReading = v[15] as Boolean,
                categoryTabs = v[16] as Boolean,
                itemCounts = v[17] as Boolean,
                group = v[19] as Int,
                sortTags = eu.kanade.domain.manga.interactor.GetSortTag.mapSortTags((v[20] as Set<*>).filterIsInstance<String>().toSet()),
                showEmptyCategoriesSearch = v[21] as Boolean,
                tracking = trackerIds.mapIndexed { i, id -> id to v[22 + i] as TriState }.toMap(),
            )
        }
    }

    fun search(query: String) = mutableState.update { it.copy(query = query) }

    fun selectCategory(id: Long?) = mutableState.update { it.copy(selectedCategory = id) }

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
            Injekt.get<LibraryUpdater>().start(groupIds = groupIds)
        } else {
            Injekt.get<LibraryUpdater>().start(categoryId = category)
        }
        // ZOUKAN <--
        if (!started) Toasts.show(localized(DMR.strings.a_library_update_is_already_running))
    }

    // Selection ---------------------------------------------------------------------------------

    private var anchor: Long? = null

    fun toggleSelection(id: Long) {
        anchor = id
        mutableState.update { it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id) }
    }

    /** Selects from the last selected entry to [id], as Shift + click does in a file manager. */
    fun selectRange(id: Long) {
        val ids = state.value.visible.map { it.id }
        val from = ids.indexOf(anchor ?: id).takeIf { it >= 0 } ?: ids.indexOf(id)
        val to = ids.indexOf(id)
        if (from < 0 || to < 0) return toggleSelection(id)
        val range = ids.subList(minOf(from, to), maxOf(from, to) + 1)
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
        screenModelScope.launch {
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
        screenModelScope.launch {
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
            mangas.forEach { manga ->
                val unread = chaptersOf(manga).filter { !it.read }.sortedByDescending { it.sourceOrder }
                    .let { list -> if (count == null) list else list.take(count) }
                byEntry(manga, unread).forEach { (entry, chapters) ->
                    val missing = chapters.filter { !downloadManager.isDownloaded(entry, it) }
                    downloadManager.enqueue(entry, missing)
                }
            }
            done(ids)
        }
    }

    fun deleteDownloads(ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids).filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            mangas.forEach { manga ->
                byEntry(manga, chaptersOf(manga)).forEach { (entry, chapters) -> downloadManager.delete(entry, chapters) }
            }
            done(ids)
        }
    }

    fun removeFromLibrary(deleteDownloads: Boolean, ids: Set<Long> = state.value.selection) {
        val mangas = selected(ids)
        screenModelScope.launch {
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

    private suspend fun byEntry(manga: Manga, chapters: List<Chapter>): List<Pair<Manga, List<Chapter>>> {
        if (manga.source != MERGED_SOURCE_ID) return listOf(manga to chapters)
        return chapters.groupBy { it.mangaId }.mapNotNull { (id, list) -> getManga.await(id)?.let { it to list } }
    }

    /** Queues each selected entry's bookmarked chapters (DownloadAction.BOOKMARKED_CHAPTERS). */
    fun downloadBookmarked() {
        val mangas = selected(state.value.selection).filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            mangas.forEach { manga ->
                byEntry(manga, chaptersOf(manga).filter { it.bookmark }).forEach { (entry, chapters) ->
                    val missing = chapters.filter { !downloadManager.isDownloaded(entry, it) }
                    downloadManager.enqueue(entry, missing)
                }
            }
            clearSelection()
        }
    }

    // SY -->
    /** Whether a selected entry is from E-Hentai, ExHentai or nhentai, for Clean titles. */
    fun showCleanTitles(): Boolean = selected(state.value.selection).any { it.isEhBasedManga() || it.source in nHentaiSourceIds }

    /** Whether a selected entry is from MangaDex, for Add to MangaDex follows. */
    fun showAddToMangadex(): Boolean = selected(state.value.selection).any { it.source in mangaDexSourceIds }

    /** Whether a selected entry has edited details, for Reset info. */
    fun showResetInfo(): Boolean = selected(state.value.selection).any { manga ->
        manga.title != manga.ogTitle ||
            manga.author != manga.ogAuthor ||
            manga.artist != manga.ogArtist ||
            manga.thumbnailUrl != manga.ogThumbnailUrl ||
            manga.description != manga.ogDescription ||
            manga.genre != manga.ogGenre ||
            manga.status != manga.ogStatus
    }

    /** Removes bracketed tags (groups, events, languages) from gallery titles, as custom titles. */
    fun cleanTitles() {
        val regex1 = "\\[.*?]".toRegex()
        val regex2 = "\\(.*?\\)".toRegex()
        val regex3 = "\\{.*?\\}".toRegex()
        val regex4 = ".*\\|".toRegex()
        val mangas = selected(state.value.selection).filter { it.isEhBasedManga() || it.source in nHentaiSourceIds }
        screenModelScope.launch {
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
        }
        clearSelection()
    }

    /** Sets the selected MangaDex entries to Reading in the MangaDex follows. */
    fun syncMangaToDex() {
        val mangas = selected(state.value.selection).filter { it.source in mangaDexSourceIds }
        screenModelScope.launch(Dispatchers.IO) {
            runCatching {
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

    val recommendationSearch = RecommendationSearchHelper()
    private var recommendationSearchJob: Job? = null

    /** The selected entries in the library's order, as Android's selectedManga. */
    fun selectedManga(): List<Manga> = selected(state.value.selection)

    fun runRecommendationSearch(selection: List<Manga>) {
        recommendationSearch.runSearch(screenModelScope, selection)?.let {
            recommendationSearchJob = it
        }
    }

    fun cancelRecommendationSearch() {
        recommendationSearchJob?.cancel()
    }
    // SY <--

    // KMK -->
    /** Updates the chapters of the selected entries. */
    fun updateSelectedManga(): Boolean {
        val mangaIds = state.value.selection
        return Injekt.get<LibraryUpdater>().start(mangaIds = mangaIds)
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

    /** Takes [mangas] out of the library, keeping their downloads (the merge's "Remove" action). */
    fun removeMangas(mangas: List<Manga>) {
        screenModelScope.launch {
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

// ZOUKAN -->
/** Status tabs' names and order, as Komikku's statusMap */
private val STATUS_GROUPS: Map<Long, Pair<String, Long>> = mapOf(
    SManga.ONGOING.toLong() to (localized(MR.strings.ongoing) to 1L),
    SManga.COMPLETED.toLong() to (localized(MR.strings.completed) to 2L),
    SManga.PUBLISHING_FINISHED.toLong() to (localized(MR.strings.publishing_finished) to 3L),
    SManga.LICENSED.toLong() to (localized(MR.strings.licensed) to 4L),
    SManga.ON_HIATUS.toLong() to (localized(MR.strings.on_hiatus) to 5L),
    SManga.CANCELLED.toLong() to (localized(MR.strings.cancelled) to 6L),
)
// ZOUKAN <--
