package zoukan.desktop.ui.library

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.TriState
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.category.interactor.GetCategories
import zoukan.desktop.compat.localized
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.common.Toasts
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

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
        // ZOUKAN -->
        /** How the tabs split the library: by category, source, status or tracking status (SY) */
        val group: Int = LibraryGroup.BY_DEFAULT,
        // ZOUKAN <--
    ) {
        val hasFilter: Boolean
            get() = listOf(downloaded, unread, started, bookmarked, completed).any { it != TriState.DISABLED } ||
                tracking.values.any { it != TriState.DISABLED }
    }

    /** One library entry with what its badges show. */
    data class Item(
        val library: LibraryManga,
        val downloadCount: Int,
        val sourceName: String,
        val language: String,
        val isLocal: Boolean,
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
    ) {
        val grouped: Boolean get() = settings.group != LibraryGroup.BY_DEFAULT

        // ZOUKAN -->
        /**
         * The tabs when the library is grouped by source, status or tracking status, with the entries
         * in each, as Android's getGroupedMangaItems. Ungrouped has none, so only All shows.
         */
        val groups: List<Pair<Category, Set<Long>>>
            get() {
                fun tab(id: Long, name: String, order: Long) = Category(id = id, name = name, order = order, flags = 0, hidden = false)
                return when (settings.group) {
                    LibraryGroup.BY_SOURCE -> items.groupBy { it.manga.source }
                        .map { (source, list) ->
                            val name = if (source == LocalSource.ID) "Local source" else sources[source]?.first?.ifBlank { null } ?: source.toString()
                            name to (source to list)
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
                        .mapIndexed { i, (name, pair) -> tab(pair.first, name, i.toLong()) to pair.second.mapTo(HashSet()) { it.id } }
                    LibraryGroup.BY_STATUS -> items.groupBy { it.manga.status }
                        .map { (status, list) ->
                            val (name, order) = STATUS_GROUPS[status] ?: ("Unknown" to 7L)
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

        /** Category tabs: the user's categories, plus Default when entries have no category. */
        val tabs: List<Category>
            get() {
                // ZOUKAN -->
                if (grouped) return groups.map { it.first }
                // ZOUKAN <--
                val hasUncategorized = items.any { it.categories.isEmpty() || Category.UNCATEGORIZED_ID in it.categories }
                return categories.filter { !it.hidden && (!it.isSystemCategory || hasUncategorized) }
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

        private fun TriState.test(value: Boolean): Boolean = when (this) {
            TriState.DISABLED -> true
            TriState.ENABLED_IS -> value
            TriState.ENABLED_NOT -> !value
        }

        fun passes(item: LibraryManga): Boolean {
            val s = settings
            if (query.isNotBlank() && !matches(item, query)) return false
            val isLocal = item.manga.source == LocalSource.ID
            if (!s.downloaded.test(isLocal || (downloadCounts[item.id] ?: 0) > 0)) return false
            if (!s.unread.test(item.unreadCount > 0)) return false
            if (!s.started.test(item.hasStarted)) return false
            if (!s.bookmarked.test(item.hasBookmarks)) return false
            if (!s.completed.test(item.manga.status.toInt() == eu.kanade.tachiyomi.source.model.SManga.COMPLETED)) return false
            val tracked = tracks[item.id].orEmpty().map { it.trackerId }.toSet()
            return s.tracking.all { (tracker, state) -> state.test(tracker in tracked) }
        }

        val visible: List<Item>
            get() {
                val filtered = inCategory(selectedCategory).filter(::passes)
                return sorted(filtered).map { lm ->
                    val (name, lang) = sources[lm.manga.source] ?: ("" to "")
                    Item(lm, downloadCounts[lm.id] ?: 0, name, lang, lm.manga.source == LocalSource.ID)
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
                else -> byTitle
            }
            return list.sortedWith((if (sort.isAscending) comparator else comparator.reversed()).thenComparing(byTitle))
        }

        private fun meanScore(id: Long): Double =
            tracks[id].orEmpty().filter { it.score > 0 }.map { it.score }.average().takeUnless { it.isNaN() } ?: -1.0

        private fun matches(item: LibraryManga, query: String): Boolean {
            val m = item.manga
            return query.split(",").map { it.trim() }.filter { it.isNotEmpty() }.all { term ->
                val exclude = term.startsWith("-")
                val t = term.removePrefix("-")
                val hit = m.title.contains(t, true) ||
                    m.author?.contains(t, true) == true ||
                    m.artist?.contains(t, true) == true ||
                    m.description?.contains(t, true) == true ||
                    m.genre.orEmpty().any { it.equals(t, true) } ||
                    sources[m.source]?.first?.contains(t, true) == true
                hit != exclude
            }
        }
    }

    init {
        screenModelScope.launch {
            // Download counts come from the same items they describe, so the two always match
            combine(getLibraryManga.subscribe(), getCategories.subscribe(), downloadManager.changes) { items, categories, _ ->
                items to categories
            }
                .collectLatest { (items, categories) ->
                    val counts = withContext(Dispatchers.IO) { downloadCounts(items) }
                    val sources = items.map { it.manga.source }.distinct().associateWith { id ->
                        val source = sourceManager.getOrStub(id)
                        source.name to source.lang
                    }
                    val ids = items.mapTo(HashSet()) { it.id }
                    mutableState.update {
                        it.copy(
                            loading = false,
                            items = items,
                            categories = categories,
                            downloadCounts = counts,
                            sources = sources,
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
                    val tab = if (settings.group != it.settings.group) null else it.selectedCategory
                    it.copy(settings = settings, selectedCategory = tab)
                }
            }
        }
    }

    private fun downloadCounts(items: List<LibraryManga>): Map<Long, Int> =
        items.distinctBy { it.id }.associate { it.id to runCatching { downloadManager.downloadCount(it.manga) }.getOrDefault(0) }

    /** The trackers the library can filter by: the ones signed in. */
    fun trackers() = trackerManager.loggedInTrackers()

    private fun settingsFlow(): Flow<Settings> {
        val p = preferences
        val trackerIds = trackerManager.loggedInTrackers().map { it.id }
        val flows: List<Flow<Any>> = listOf(
            p.displayMode().changes(), p.landscapeColumns().changes(), p.sortingMode().changes(),
            p.randomSortSeed().changes(), p.categorizedDisplaySettings().changes(),
            p.filterDownloaded().changes(), p.filterUnread().changes(), p.filterStarted().changes(),
            p.filterBookmarked().changes(), p.filterCompleted().changes(),
            p.unreadBadge().changes(), p.downloadBadge().changes(), p.localBadge().changes(),
            p.languageBadge().changes(), p.sourceBadge().changes(), p.showContinueReadingButton().changes(),
            p.categoryTabs().changes(), p.categoryNumberOfItems().changes(), p.groupLibraryBy().changes(),
        ) + trackerIds.map { p.filterTracking(it.toInt()).changes() }
        return combine(flows) { v ->
            Settings(
                display = v[0] as LibraryDisplayMode,
                columns = v[1] as Int,
                sort = v[2] as LibrarySort,
                randomSeed = v[3] as Int,
                categorized = v[4] as Boolean,
                downloaded = v[5] as TriState,
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
                group = v[18] as Int,
                tracking = trackerIds.mapIndexed { i, id -> id to v[19 + i] as TriState }.toMap(),
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
        // A source, status or tracking tab updates the entries in it
        val started = if (state.value.grouped && category != null) {
            Injekt.get<LibraryUpdater>().start(mangaIds = state.value.inCategory(category).mapTo(HashSet()) { it.id })
        } else {
            Injekt.get<LibraryUpdater>().start(categoryId = category)
        }
        // ZOUKAN <--
        if (!started) Toasts.show("A library update is already running")
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

    private fun selected(): List<Manga> {
        val ids = state.value.selection
        return state.value.items.filter { it.id in ids }.distinctBy { it.id }.map { it.manga }
    }

    /** The categories every selected entry has, and the ones only some have. */
    fun selectionCategories(): Pair<Set<Long>, Set<Long>> {
        val ids = state.value.selection
        val per = state.value.items.filter { it.id in ids }.groupBy { it.id }.map { (_, rows) -> rows.flatMap { it.categories }.toSet() }
        if (per.isEmpty()) return emptySet<Long>() to emptySet()
        val all = per.reduce { a, b -> a intersect b }
        val some = per.reduce { a, b -> a union b } - all
        return all to some
    }

    fun setCategories(add: Set<Long>, remove: Set<Long>) {
        val ids = state.value.selection
        val current = state.value.items.filter { it.id in ids }.groupBy { it.id }
            .mapValues { (_, rows) -> rows.flatMap { it.categories }.toSet() }
        screenModelScope.launch {
            current.forEach { (id, categories) ->
                val updated = (categories - remove + add).filter { it != Category.UNCATEGORIZED_ID }
                setMangaCategories.await(id, updated)
            }
            clearSelection()
        }
    }

    private suspend fun chaptersOf(manga: Manga): List<Chapter> =
        if (manga.source == MERGED_SOURCE_ID) getMergedChaptersByMangaId.await(manga.id) else getChaptersByMangaId.await(manga.id)

    fun markRead(read: Boolean) {
        val mangas = selected()
        screenModelScope.launch {
            mangas.forEach { manga ->
                val changed = chaptersOf(manga).filter { it.read != read }
                val updates = changed.map { ChapterUpdate(id = it.id, read = read, lastPageRead = if (read) null else 0L) }
                updateChapter.awaitAll(updates)
                // ZOUKAN -->
                if (read) downloadManager.afterMarkedRead(changed)
                // ZOUKAN <--
            }
            clearSelection()
        }
    }

    /** Queues the next [count] unread chapters of each selected entry, or all unread when null. */
    fun download(count: Int?) {
        val mangas = selected().filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            mangas.forEach { manga ->
                val unread = chaptersOf(manga).filter { !it.read }.sortedByDescending { it.sourceOrder }
                    .let { list -> if (count == null) list else list.take(count) }
                byEntry(manga, unread).forEach { (entry, chapters) ->
                    val missing = chapters.filter { !downloadManager.isDownloaded(entry, it) }
                    downloadManager.enqueue(entry, missing)
                }
            }
            clearSelection()
        }
    }

    fun deleteDownloads() {
        val mangas = selected().filter { it.source != LocalSource.ID }
        screenModelScope.launch {
            mangas.forEach { manga ->
                byEntry(manga, chaptersOf(manga)).forEach { (entry, chapters) -> downloadManager.delete(entry, chapters) }
            }
            clearSelection()
        }
    }

    fun removeFromLibrary(deleteDownloads: Boolean) {
        val mangas = selected()
        screenModelScope.launch {
            mangas.forEach { manga ->
                if (deleteDownloads && manga.source != LocalSource.ID) {
                    byEntry(manga, chaptersOf(manga)).forEach { (entry, chapters) -> downloadManager.delete(entry, chapters) }
                }
                mangaRepository.update(MangaUpdate(id = manga.id, favorite = false, dateAdded = 0))
            }
            Toasts.show(if (mangas.size == 1) "Removed from library" else "Removed ${mangas.size} entries from library")
            clearSelection()
        }
    }

    private suspend fun byEntry(manga: Manga, chapters: List<Chapter>): List<Pair<Manga, List<Chapter>>> {
        if (manga.source != MERGED_SOURCE_ID) return listOf(manga to chapters)
        return chapters.groupBy { it.mangaId }.mapNotNull { (id, list) -> getManga.await(id)?.let { it to list } }
    }

    /** The chapter "continue reading" opens: the next unread one. */
    suspend fun nextChapter(mangaId: Long): Chapter? = getNextChapters.await(mangaId, onlyUnread = true).firstOrNull()

    /** A random entry of what is shown, for "Open a random entry". */
    fun randomEntry(): Long? = state.value.visible.randomOrNull()?.id
}

// ZOUKAN -->
/** Status tabs' names and order, as Komikku's statusMap */
private val STATUS_GROUPS: Map<Long, Pair<String, Long>> = mapOf(
    SManga.ONGOING.toLong() to ("Ongoing" to 1L),
    SManga.COMPLETED.toLong() to ("Completed" to 2L),
    SManga.PUBLISHING_FINISHED.toLong() to ("Publishing finished" to 3L),
    SManga.LICENSED.toLong() to ("Licensed" to 4L),
    SManga.ON_HIATUS.toLong() to ("On hiatus" to 5L),
    SManga.CANCELLED.toLong() to ("Cancelled" to 6L),
)
// ZOUKAN <--
