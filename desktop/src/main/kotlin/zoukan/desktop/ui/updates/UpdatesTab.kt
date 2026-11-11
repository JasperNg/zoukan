package zoukan.desktop.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.local.LocalSource
import zoukan.desktop.ui.browse.sectionHeader
import zoukan.desktop.ui.common.ContextMenu
import zoukan.desktop.ui.common.ContextMenuDivider
import zoukan.desktop.ui.common.ContextMenuItem
import zoukan.desktop.ui.common.ContextMenuState
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.rememberContextMenuState
import zoukan.desktop.ui.downloads.DownloadIndicator
import zoukan.desktop.ui.downloads.DownloadsButton
import zoukan.desktop.ui.library.ClickKind
import zoukan.desktop.ui.library.LibraryUpdateProgress
import zoukan.desktop.ui.library.selectableClick
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.upcoming.UpcomingScreen
import java.time.Instant
import java.time.temporal.ChronoUnit

class UpdatesScreenModel(
    getUpdates: GetUpdates = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    val preferences: UpdatesPreferences = Injekt.get(),
) : StateScreenModel<UpdatesScreenModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val items: List<UpdatesWithRelations> = emptyList(),
        val filters: UpdatesFilters = UpdatesFilters(),
        // ZOUKAN -->
        /** Chapter ids picked for a bulk action, as Android's Updates selection */
        val selected: Set<Long> = emptySet(),
        /** Chapters downloaded to disk */
        val downloaded: Set<Long> = emptySet(),
        /** Chapters in the download queue, by chapter id */
        val queued: Map<Long, Download> = emptyMap(),
        // ZOUKAN <--
    ) {
        val selecting: Boolean get() = selected.isNotEmpty()
        val selectedItems: List<UpdatesWithRelations> get() = items.filter { it.chapterId in selected }
    }

    init {
        screenModelScope.launch {
            // As Android's UpdatesScreenModel: unread, started, bookmarked and excluded scanlators
            // filter in the query, downloaded on the result
            val filters = updatesFilters(preferences).distinctUntilChanged()

            @OptIn(ExperimentalCoroutinesApi::class)
            val updates = filters
                .map { it.copy(downloaded = TriState.DISABLED) }
                .distinctUntilChanged()
                .flatMapLatest { f ->
                    getUpdates.subscribe(
                        Instant.now().minus(90, ChronoUnit.DAYS),
                        unread = f.unread.toBooleanOrNull(),
                        started = f.started.toBooleanOrNull(),
                        bookmarked = f.bookmarked.toBooleanOrNull(),
                        hideExcludedScanlators = f.hideExcludedScanlators,
                    )
                }
            // ZOUKAN -->
            // More > Downloaded only shows only downloaded chapters, as Android's Updates
            val downloadedOnly = Injekt.get<zoukan.desktop.ui.common.DesktopPreferences>().downloadedOnly().changes()
            // ZOUKAN <--
            combine(updates, filters, downloadedOnly, downloadManager.changes) { items, f, only, _ ->
                items to if (only) f.copy(downloaded = TriState.ENABLED_IS) else f
            }
                .collectLatest { (all, f) ->
                    val items = if (f.downloaded == TriState.DISABLED) {
                        all
                    } else {
                        withContext(Dispatchers.IO) {
                            all.filter { item ->
                                val downloaded = runCatching { isDownloaded(downloadManager, item) }.getOrDefault(false)
                                if (f.downloaded == TriState.ENABLED_IS) downloaded else !downloaded
                            }
                        }
                    }
                    val ids = items.mapTo(HashSet()) { it.chapterId }
                    mutableState.update {
                        it.copy(
                            loading = false,
                            items = items,
                            filters = f,
                            // Chapters that left the list leave the selection too
                            selected = it.selected.filterTo(HashSet()) { id -> id in ids },
                        )
                    }
                    val downloaded = withContext(Dispatchers.IO) { downloadedIds(items) }
                    mutableState.update { it.copy(downloaded = downloaded) }
                }
        }
        screenModelScope.launch {
            downloadManager.queue
                .map { q -> q.associateBy { it.chapter.id } }
                .distinctUntilChanged()
                .collect { queued -> mutableState.update { it.copy(queued = queued) } }
        }
    }

    private suspend fun downloadedIds(items: List<UpdatesWithRelations>): Set<Long> =
        items.filter { it.sourceId != LocalSource.ID }.groupBy { it.mangaId }.flatMapTo(HashSet()) { (mangaId, list) ->
            val manga = getManga.await(mangaId) ?: return@flatMapTo emptyList()
            val chapters = list.mapNotNull { getChapter.await(it.chapterId) }
            downloadManager.downloadedIds(manga, chapters)
        }

    // ZOUKAN -->
    private fun isDownloaded(downloads: zoukan.desktop.download.DownloadManager, item: UpdatesWithRelations): Boolean {
        if (item.sourceId == zoukan.desktop.local.LocalSource.ID) return true
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(id = item.mangaId, source = item.sourceId, ogTitle = item.ogMangaTitle)
        val chapter = tachiyomi.domain.chapter.model.Chapter.create()
            .copy(id = item.chapterId, mangaId = item.mangaId, name = item.chapterName, scanlator = item.scanlator, url = item.chapterUrl)
        return downloads.isDownloaded(manga, chapter)
    }
    // ZOUKAN <--

    fun setRead(item: UpdatesWithRelations, read: Boolean) = setRead(listOf(item), read)

    // ZOUKAN -->
    fun setRead(items: List<UpdatesWithRelations>, read: Boolean) {
        screenModelScope.launch {
            items.forEach { item ->
                updateChapter.await(ChapterUpdate(id = item.chapterId, read = read, lastPageRead = if (read) null else 0L))
            }
            if (read) {
                val newlyRead = items.filter { !it.read }.mapNotNull { getChapter.await(it.chapterId) }
                if (newlyRead.isNotEmpty()) downloadManager.afterMarkedRead(newlyRead)
            }
        }
        done(items)
    }

    fun setBookmark(items: List<UpdatesWithRelations>, bookmark: Boolean) {
        screenModelScope.launch {
            items.forEach { updateChapter.await(ChapterUpdate(id = it.chapterId, bookmark = bookmark)) }
        }
        done(items)
    }

    /** Chapters of [items] with their entry, grouped by entry; local chapters are left out. */
    private suspend fun byEntry(items: List<UpdatesWithRelations>): List<Pair<Manga, List<Chapter>>> =
        items.filter { it.sourceId != LocalSource.ID }.groupBy { it.mangaId }.mapNotNull { (mangaId, list) ->
            val manga = getManga.await(mangaId) ?: return@mapNotNull null
            manga to list.mapNotNull { getChapter.await(it.chapterId) }
        }

    fun download(items: List<UpdatesWithRelations>) {
        screenModelScope.launch {
            byEntry(items).forEach { (manga, chapters) -> downloadManager.enqueue(manga, chapters) }
        }
        done(items)
    }

    fun deleteDownloads(items: List<UpdatesWithRelations>) {
        screenModelScope.launch {
            byEntry(items).forEach { (manga, chapters) -> downloadManager.delete(manga, chapters) }
        }
        done(items)
    }

    fun cancelDownload(item: UpdatesWithRelations) = downloadManager.cancel(setOf(item.chapterId))

    /**
     * Ends an action on [items]: an action on the selection clears it, and one on a chapter outside
     * the selection (its row's buttons or context menu) leaves the selection as it was.
     */
    private fun done(items: List<UpdatesWithRelations>) {
        if (items.mapTo(HashSet()) { it.chapterId } == state.value.selected) clearSelection()
    }

    fun toggleSelection(item: UpdatesWithRelations) = mutableState.update {
        it.copy(selected = if (item.chapterId in it.selected) it.selected - item.chapterId else it.selected + item.chapterId)
    }

    /**
     * Shift-click: selects every chapter between the last selected one and [item], as Android's
     * long-press range selection.
     */
    fun selectRange(item: UpdatesWithRelations) = mutableState.update { state ->
        val ids = state.items.map { it.chapterId }
        val to = ids.indexOf(item.chapterId)
        val from = ids.indexOfLast { it in state.selected }.takeIf { it >= 0 } ?: to
        val range = if (from <= to) from..to else to..from
        state.copy(selected = state.selected + range.map { ids[it] })
    }

    fun selectAll() = mutableState.update { s -> s.copy(selected = s.items.mapTo(HashSet()) { it.chapterId }) }

    fun invertSelection() = mutableState.update { s ->
        s.copy(selected = s.items.map { it.chapterId }.filterTo(HashSet()) { it !in s.selected })
    }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }
    // ZOUKAN <--

    fun refresh() {
        if (!Injekt.get<LibraryUpdater>().start()) Toasts.show("A library update is already running")
    }
}

@Composable
fun UpdatesTab(model: UpdatesScreenModel) {
    val state by model.state.collectAsState()
    val refresh by Injekt.get<LibraryUpdater>().progress.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    // Ctrl/Cmd + A selects every chapter shown; Escape clears the selection before it means "back"
    zoukan.desktop.ui.OnShortcut { action ->
        when (action) {
            zoukan.desktop.ui.Shortcuts.Action.SelectAll -> {
                model.selectAll()
                true
            }
            zoukan.desktop.ui.Shortcuts.Action.Back -> state.selecting.also { if (it) model.clearSelection() }
            else -> false
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionHeader(state, model)
        } else {
            ScreenHeader("Updates", subtitle = "New chapters in your library from the last 90 days", showBack = false) {
                UpdatesFilterButton(model, state.filters)
                // Android's Updates toolbar opens Upcoming from its calendar button
                TooltipIconButton(Icons.Outlined.CalendarMonth, "View Upcoming Updates", onClick = { navigator.push(UpcomingScreen) })
                DownloadsButton()
                TooltipIconButton(Icons.Default.Refresh, "Update library", onClick = model::refresh, enabled = refresh == null)
            }
        }
        LibraryUpdateProgress()
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() && state.filters.active -> EmptyScreen("No updates match the filters")
            state.items.isEmpty() -> EmptyScreen("No recent updates. Press refresh to check your library for new chapters.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // Android's "Library last updated" line above the updates
                item(key = "updates-lastUpdated") { LastUpdatedLine() }
                state.items.groupBy { zoukan.desktop.ui.common.Dates.relative(it.dateFetch) }.forEach { (date, items) ->
                    sectionHeader(date)
                    items(items, key = { it.chapterId }) { item ->
                        UpdateRow(
                            item = item,
                            selected = item.chapterId in state.selected,
                            downloaded = item.chapterId in state.downloaded,
                            download = state.queued[item.chapterId],
                            onClick = { kind ->
                                when {
                                    kind == ClickKind.Range -> model.selectRange(item)
                                    kind == ClickKind.Toggle || state.selecting -> model.toggleSelection(item)
                                    else -> navigator.push(ReaderScreen(item.mangaId, item.chapterId))
                                }
                            },
                            onOpenManga = { navigator.push(MangaScreen(item.mangaId)) },
                            onRead = { model.setRead(item, !item.read) },
                            onDownload = { model.download(listOf(item)) },
                            onCancelDownload = { model.cancelDownload(item) },
                            onDeleteDownload = { model.deleteDownloads(listOf(item)) },
                        ) { menu ->
                            // A right click on a selected chapter acts on the whole selection; on
                            // any other chapter, on that chapter alone
                            val selected = item.chapterId in state.selected
                            UpdateContextMenu(
                                menu = menu,
                                state = state,
                                model = model,
                                item = item,
                                targets = if (selected) state.selectedItems else listOf(item),
                                selected = selected,
                                onOpen = { navigator.push(ReaderScreen(item.mangaId, item.chapterId)) },
                                onOpenManga = { navigator.push(MangaScreen(item.mangaId)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Library last updated: 5 minutes ago", kept current each minute, as Android's updatesLastUpdatedItem. */
@Composable
private fun LastUpdatedLine() {
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val last by preferences.lastUpdatedTimestamp().changes().collectAsState(preferences.lastUpdatedTimestamp().get())
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    Text(
        "Library last updated: ${zoukan.desktop.ui.common.Dates.timeAgo(last, now)}",
        fontStyle = FontStyle.Italic,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The toolbar while chapters are selected: the count and the bulk actions, as on Android. */
@Composable
private fun SelectionHeader(state: UpdatesScreenModel.State, model: UpdatesScreenModel) {
    val selected = state.selectedItems
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(Icons.Default.Close, "Clear selection", onClick = model::clearSelection)
        Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TooltipIconButton(Icons.Default.SelectAll, "Select all", onClick = model::selectAll)
        TooltipIconButton(Icons.Default.FlipToBack, "Invert selection", onClick = model::invertSelection)
        if (selected.any { !it.read }) {
            TooltipIconButton(Icons.Default.DoneAll, "Mark as read", onClick = { model.setRead(selected, true) })
        }
        if (selected.any { it.read || it.lastPageRead > 0 }) {
            TooltipIconButton(Icons.Default.RemoveDone, "Mark as unread", onClick = { model.setRead(selected, false) })
        }
        if (selected.any { !it.bookmark }) {
            TooltipIconButton(Icons.Default.Bookmark, "Bookmark", onClick = { model.setBookmark(selected, true) })
        }
        if (selected.any { it.bookmark }) {
            TooltipIconButton(Icons.Default.BookmarkBorder, "Remove bookmark", onClick = { model.setBookmark(selected, false) })
        }
        val remote = selected.filter { it.sourceId != LocalSource.ID }
        if (remote.any { it.chapterId !in state.downloaded && it.chapterId !in state.queued }) {
            TooltipIconButton(Icons.Outlined.Download, "Download", onClick = { model.download(remote) })
        }
        if (remote.any { it.chapterId in state.downloaded }) {
            TooltipIconButton(Icons.Outlined.Delete, "Delete downloads", onClick = { model.deleteDownloads(remote) })
        }
    }
}

/** A chapter's right-click menu: the selection toolbar's actions, for [targets]. */
@Composable
private fun ColumnScope.UpdateContextMenu(
    menu: ContextMenuState,
    state: UpdatesScreenModel.State,
    model: UpdatesScreenModel,
    item: UpdatesWithRelations,
    targets: List<UpdatesWithRelations>,
    selected: Boolean,
    onOpen: () -> Unit,
    onOpenManga: () -> Unit,
) {
    if (targets.size == 1) {
        ContextMenuItem(menu, "Read", Icons.AutoMirrored.Outlined.MenuBook, onClick = onOpen)
        ContextMenuItem(menu, "Open entry", Icons.AutoMirrored.Outlined.OpenInNew, onClick = onOpenManga)
    }
    if (selected) {
        ContextMenuItem(menu, "Deselect", Icons.Outlined.Deselect) { model.toggleSelection(item) }
    } else {
        ContextMenuItem(menu, "Select", Icons.Outlined.CheckCircle) { model.toggleSelection(item) }
    }
    ContextMenuDivider()
    if (targets.any { !it.read }) {
        ContextMenuItem(menu, "Mark as read", Icons.Outlined.DoneAll) { model.setRead(targets, true) }
    }
    if (targets.any { it.read || it.lastPageRead > 0 }) {
        ContextMenuItem(menu, "Mark as unread", Icons.Outlined.RemoveDone) { model.setRead(targets, false) }
    }
    if (targets.any { !it.bookmark }) {
        ContextMenuItem(menu, "Bookmark", Icons.Outlined.BookmarkAdd) { model.setBookmark(targets, true) }
    }
    if (targets.any { it.bookmark }) {
        ContextMenuItem(menu, "Remove bookmark", Icons.Outlined.BookmarkRemove) { model.setBookmark(targets, false) }
    }
    val remote = targets.filter { it.sourceId != LocalSource.ID }
    if (remote.any { it.chapterId !in state.downloaded && it.chapterId !in state.queued }) {
        ContextMenuItem(menu, "Download", Icons.Outlined.Download) { model.download(remote) }
    }
    if (remote.size == 1 && remote.single().chapterId in state.queued) {
        ContextMenuItem(menu, "Cancel download", Icons.Outlined.Close) { model.cancelDownload(remote.single()) }
    }
    if (remote.any { it.chapterId in state.downloaded }) {
        ContextMenuItem(menu, "Delete downloads", Icons.Outlined.Delete) { model.deleteDownloads(remote) }
    }
}

@Composable
private fun UpdateRow(
    item: UpdatesWithRelations,
    selected: Boolean,
    downloaded: Boolean,
    download: Download?,
    onClick: (ClickKind) -> Unit,
    onOpenManga: () -> Unit,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    contextMenu: @Composable ColumnScope.(ContextMenuState) -> Unit,
) {
    val menu = rememberContextMenuState()
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .selectableClick(menu::open, onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                Cover.of(item.coverData),
                Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenManga),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).alpha(if (item.read) 0.5f else 1f)) {
                Text(item.mangaTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.bookmark) {
                        Icon(Icons.Default.Bookmark, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        item.chapterName + if (!item.read && item.lastPageRead > 0) " · Page ${item.lastPageRead + 1}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.read) {
                TooltipIconButton(Icons.Default.RemoveDone, "Mark as unread", onClick = onRead)
            } else {
                TooltipIconButton(Icons.Default.DoneAll, "Mark as read", onClick = onRead)
            }
            // ZOUKAN -->
            // Android's Updates rows end with the chapter's download button
            if (item.sourceId != LocalSource.ID) {
                DownloadIndicator(
                    downloaded = downloaded,
                    download = download,
                    onDownload = onDownload,
                    onCancel = onCancelDownload,
                    onDelete = onDeleteDownload,
                )
            }
            // ZOUKAN <--
        }
        ContextMenu(menu) { contextMenu(menu) }
    }
}
