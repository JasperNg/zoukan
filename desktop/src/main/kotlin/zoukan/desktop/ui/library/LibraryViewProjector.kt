package zoukan.desktop.ui.library

import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.model.SManga
import exh.source.isEhBasedManga
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import exh.util.isLewd
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_display_local_badge
import tachiyomi.i18n.cancelled
import tachiyomi.i18n.completed
import tachiyomi.i18n.licensed
import tachiyomi.i18n.on_hiatus
import tachiyomi.i18n.ongoing
import tachiyomi.i18n.publishing_finished
import tachiyomi.i18n.unknown
import zoukan.desktop.compat.localized
import zoukan.desktop.local.LocalSource
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

/** Snapshot of library inputs that determine the visible list, tabs, groups, and counts. */
data class LibraryViewInput(
    val items: List<LibraryManga> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategory: Long? = null,
    val query: String = "",
    val settings: LibraryScreenModel.Settings = LibraryScreenModel.Settings(),
    val tracks: Map<Long, List<Track>> = emptyMap(),
    val downloadCounts: Map<Long, Int> = emptyMap(),
    val sources: Map<Long, Pair<String, String>> = emptyMap(),
    val trackStatuses: Map<Long, List<TrackStatus>> = emptyMap(),
    val searchResults: Set<Long>? = null,
    val sourceIcons: Map<Long, String> = emptyMap(),
    val stubSources: Set<Long> = emptySet(),
)

/** Immutable projected view derived from a [LibraryViewInput]. */
data class LibraryViewProjection(
    val groups: List<Pair<Category, Set<Long>>> = emptyList(),
    val tabs: List<Category> = emptyList(),
    val visible: List<LibraryScreenModel.Item> = emptyList(),
    val entryCount: Int = 0,
    val counts: Map<Long?, Int> = emptyMap(),
) {
    /** Returns the precomputed filtered entry count for [category] (or all entries when null). */
    fun countIn(category: Long?): Int = counts[category] ?: 0
}

/** Pure stateless functions for filtering, sorting, grouping, and projecting library view state. */
object LibraryViewProjector {

    internal class TrackFilterSet(tracking: Map<Long, TriState>) {
        val includedTracks: List<Long> = tracking.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val excludedTracks: List<Long> = tracking.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
    }

    /** Computes the full immutable [LibraryViewProjection] for [input]. */
    fun project(input: LibraryViewInput, withCounts: Boolean = input.settings.itemCounts): LibraryViewProjection {
        val grouped = isGrouped(input.settings)
        val groups = if (grouped) groups(input.items, input.settings, input.sources, input.trackStatuses) else emptyList()
        val trackFilters = TrackFilterSet(input.settings.tracking)
        val counts = LinkedHashMap<Long?, Int>()
        fun cachedCount(categoryId: Long?): Int =
            counts.getOrPut(categoryId) { countIn(input, categoryId, groups, trackFilters) }

        val tabs = if (grouped) {
            groups.map { it.first }
        } else {
            val hasUncategorized = input.items.any { it.categories.isEmpty() || Category.UNCATEGORIZED_ID in it.categories }
            // KMK: hidden categories show with "Show hidden categories"
            val shown = input.categories.filter {
                (input.settings.showHiddenCategories || !it.hidden) && (!it.isSystemCategory || hasUncategorized)
            }
            val searching = input.query.isNotBlank() || input.settings.hasFilter
            if (!searching || input.settings.showEmptyCategoriesSearch) {
                shown
            } else {
                shown.filter { cachedCount(it.id) > 0 }
            }
        }

        if (withCounts) {
            tabs.forEach { cachedCount(it.id) }
            cachedCount(null)
        }

        val selectedCategoryModel = input.categories.firstOrNull { it.id == input.selectedCategory }.takeIf { !grouped }
        val effectiveSort = selectedCategoryModel?.takeIf { input.settings.categorized }?.sort ?: input.settings.sort
        val filtered = inCategory(input.items, input.selectedCategory, grouped, groups).filter {
            passes(it, input.query, input.searchResults, input.settings, input.tracks, input.downloadCounts, trackFilters)
        }
        val visible = sorted(filtered, effectiveSort, input.settings, input.tracks).map { lm ->
            val (name, lang) = input.sources[lm.manga.source] ?: ("" to "")
            LibraryScreenModel.Item(
                library = lm,
                downloadCount = input.downloadCounts[lm.id] ?: 0,
                sourceName = name,
                language = lang,
                isLocal = lm.manga.source == LocalSource.ID,
                sourceIconUrl = input.sourceIcons[lm.manga.source],
                isStubSource = lm.manga.source in input.stubSources,
            )
        }
        val entryCount = input.items.distinctBy { it.id }.size
        return LibraryViewProjection(
            groups = groups,
            tabs = tabs,
            visible = visible,
            entryCount = entryCount,
            counts = counts.toMap(),
        )
    }

    /** True when the library uses dynamic tabs (source, status, or tracking status) rather than categories. */
    fun isGrouped(settings: LibraryScreenModel.Settings): Boolean =
        settings.effectiveGroup != LibraryGroup.BY_DEFAULT

    /**
     * The tabs when the library is grouped by source, status or tracking status, with the entries
     * in each, as Android's getGroupedMangaItems. Ungrouped has none, so only All shows.
     */
    fun groups(
        items: List<LibraryManga>,
        settings: LibraryScreenModel.Settings,
        sources: Map<Long, Pair<String, String>>,
        trackStatuses: Map<Long, List<TrackStatus>>,
    ): List<Pair<Category, Set<Long>>> {
        fun tab(id: Long, name: String, order: Long) =
            Category(id = id, name = name, order = order, flags = 0, hidden = false)

        return when (settings.effectiveGroup) {
            LibraryGroup.BY_SOURCE -> items.groupBy { it.manga.source }
                .map { (source, list) ->
                    val name = if (source == LocalSource.ID) {
                        localized(MR.strings.action_display_local_badge)
                    } else {
                        sources[source]?.first?.ifBlank { null } ?: source.toString()
                    }
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

    /** Returns the distinct entries belonging to [category] (or all entries when null). */
    fun inCategory(
        items: List<LibraryManga>,
        category: Long?,
        grouped: Boolean,
        groups: List<Pair<Category, Set<Long>>>,
    ): List<LibraryManga> = when {
        category == null -> items
        grouped -> groups.firstOrNull { it.first.id == category }?.second
            .orEmpty().let { ids -> items.filter { it.id in ids } }
        category == Category.UNCATEGORIZED_ID -> items.filter { it.categories.isEmpty() || category in it.categories }
        else -> items.filter { category in it.categories }
    }.distinctBy { it.id }

    /** Counts filtered entries in [category] (or all entries when null). */
    internal fun countIn(
        input: LibraryViewInput,
        category: Long?,
        groups: List<Pair<Category, Set<Long>>> = groups(input.items, input.settings, input.sources, input.trackStatuses),
        trackFilters: TrackFilterSet = TrackFilterSet(input.settings.tracking),
    ): Int {
        val grouped = isGrouped(input.settings)
        return inCategory(input.items, category, grouped, groups).count {
            passes(it, input.query, input.searchResults, input.settings, input.tracks, input.downloadCounts, trackFilters)
        }
    }

    /**
     * What updating [category] updates while the library is grouped, as LibraryUpdateJob's
     * addMangaToQueue under SY's groupLibraryUpdateType: null for the global update (by the
     * library update categories), or the ids of the tab's entries; the whole library when
     * ungrouped.
     */
    fun groupUpdateIds(
        input: LibraryViewInput,
        category: Long?,
        mode: GroupLibraryMode,
        groups: List<Pair<Category, Set<Long>>> = groups(input.items, input.settings, input.sources, input.trackStatuses),
    ): Set<Long>? {
        val group = input.settings.effectiveGroup
        val global = mode == GroupLibraryMode.GLOBAL ||
            (mode == GroupLibraryMode.ALL_BUT_UNGROUPED && group == LibraryGroup.UNGROUPED)
        val grouped = isGrouped(input.settings)
        return when {
            global -> null
            group == LibraryGroup.UNGROUPED -> inCategory(input.items, null, grouped, groups).mapTo(HashSet()) { it.id }
            category == null -> null
            else -> inCategory(input.items, category, grouped, groups).mapTo(HashSet()) { it.id }
        }
    }

    /** Returns true if [item] passes the active search query and library filters. */
    internal fun passes(
        item: LibraryManga,
        query: String,
        searchResults: Set<Long>?,
        settings: LibraryScreenModel.Settings,
        tracks: Map<Long, List<Track>>,
        downloadCounts: Map<Long, Int>,
        trackFilters: TrackFilterSet = TrackFilterSet(settings.tracking),
    ): Boolean {
        // SY: the search runs in the background; until its first result, nothing is hidden
        if (query.isNotBlank() && searchResults != null && item.id !in searchResults) return false
        val isLocal = item.manga.source == LocalSource.ID
        if (!settings.downloaded.test(isLocal || (downloadCounts[item.id] ?: 0) > 0)) return false
        if (!settings.unread.test(item.unreadCount > 0)) return false
        if (!settings.started.test(item.hasStarted)) return false
        if (!settings.bookmarked.test(item.hasBookmarks)) return false
        if (!settings.completed.test(item.manga.status.toInt() == SManga.COMPLETED)) return false
        // KMK -->
        if (settings.skipOutsideReleasePeriod && !settings.intervalCustom.test(item.manga.fetchInterval < 0)) return false
        if (settings.lewd != TriState.DISABLED && !settings.lewd.test(item.manga.isLewd())) return false
        return passesTracking(item, tracks, trackFilters) && passesCategories(item, settings)
    }

    private fun TriState.test(value: Boolean): Boolean = when (this) {
        TriState.DISABLED -> true
        TriState.ENABLED_IS -> value
        TriState.ENABLED_NOT -> !value
    }

    /** KMK's tracking filter: no excluded tracker, and one of the included ones if any */
    private fun passesTracking(
        item: LibraryManga,
        tracks: Map<Long, List<Track>>,
        trackFilters: TrackFilterSet,
    ): Boolean {
        val includedTracks = trackFilters.includedTracks
        val excludedTracks = trackFilters.excludedTracks
        if (includedTracks.isEmpty() && excludedTracks.isEmpty()) return true
        val mangaTracks = tracks[item.id].orEmpty()
        val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.any { it.trackerId in excludedTracks }
        val isIncluded = includedTracks.isEmpty() || mangaTracks.any { it.trackerId in includedTracks }
        return !isExcluded && isIncluded
    }

    /** KMK's category filter: in every included category and in no excluded one */
    private fun passesCategories(item: LibraryManga, settings: LibraryScreenModel.Settings): Boolean {
        if (!settings.filterCategories) return true
        val mangaCategories = item.categories.filterNot { it == 0L }.toSet()
        if (mangaCategories.isEmpty()) return settings.includedCategories.isEmpty()
        val isExcluded = settings.excludedCategories.any { it in mangaCategories }
        val isIncluded = settings.includedCategories.isEmpty() || settings.includedCategories.all { it in mangaCategories }
        return !isExcluded && isIncluded
    }

    /** Sorts [list] according to [sort], falling back to title order. */
    fun sorted(
        list: List<LibraryManga>,
        sort: LibrarySort,
        settings: LibraryScreenModel.Settings,
        tracks: Map<Long, List<Track>>,
    ): List<LibraryManga> {
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
            LibrarySort.Type.TrackerMean -> compareBy { meanScore(tracks, it.id) }
            // SY's tag sorting: by the first sort tag the entry has; entries without one first
            LibrarySort.Type.TagList -> compareBy { item ->
                val genres = item.manga.genre.orEmpty()
                settings.sortTags.indexOfFirst { it in genres }
            }
            else -> byTitle
        }
        return list.sortedWith((if (sort.isAscending) comparator else comparator.reversed()).thenComparing(byTitle))
    }

    private fun meanScore(tracks: Map<Long, List<Track>>, id: Long): Double =
        tracks[id].orEmpty().filter { it.score > 0 }.map { it.score }.average().takeUnless { it.isNaN() } ?: -1.0

    /** Whether the selection bar shows Download: only when no selected entry is from the local source. */
    fun selectionCanDownload(items: List<LibraryManga>, selection: Set<Long>): Boolean =
        selection.isNotEmpty() && items.none { it.id in selection && it.manga.source == LocalSource.ID }

    /** Whether any selected entry is not from the local source. */
    fun selectionHasRemote(items: List<LibraryManga>, selection: Set<Long>): Boolean =
        items.any { it.id in selection && it.manga.source != LocalSource.ID }

    /** Computes which selection overflow-menu actions apply in one pass over [items]. */
    fun selectionFlags(items: List<LibraryManga>, selection: Set<Long>): LibraryScreenModel.SelectionFlags {
        var clean = false
        var mangadex = false
        var reset = false
        if (selection.isNotEmpty()) {
            for (item in items) {
                if (item.id !in selection) continue
                val manga = item.manga
                clean = clean || manga.isEhBasedManga() || manga.source in nHentaiSourceIds
                mangadex = mangadex || manga.source in mangaDexSourceIds
                reset = reset ||
                    manga.title != manga.ogTitle ||
                    manga.author != manga.ogAuthor ||
                    manga.artist != manga.ogArtist ||
                    manga.thumbnailUrl != manga.ogThumbnailUrl ||
                    manga.description != manga.ogDescription ||
                    manga.genre != manga.ogGenre ||
                    manga.status != manga.ogStatus
                if (clean && mangadex && reset) break
            }
        }
        return LibraryScreenModel.SelectionFlags(clean, mangadex, reset)
    }
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
