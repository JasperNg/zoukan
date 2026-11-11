package zoukan.desktop.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.OnShortcut
import zoukan.desktop.ui.Shortcuts
import zoukan.desktop.ui.browse.QuickLibraryToggleDialogs
import zoukan.desktop.ui.browse.rememberQuickLibraryToggle
import zoukan.desktop.ui.browse.sectionHeader
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.SearchField
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.library.ClickKind
import zoukan.desktop.ui.library.TriStateRow
import zoukan.desktop.ui.library.selectableClick
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Komikku's History filters, from its HistoryPreferences, so they carry over in backups. */
data class HistoryFilters(
    val unfinishedManga: TriState = TriState.DISABLED,
    val unfinishedChapter: TriState = TriState.DISABLED,
    val nonLibrary: TriState = TriState.DISABLED,
) {
    /** As Komikku's hasActiveFilters */
    val active: Boolean
        get() = listOf(unfinishedManga, unfinishedChapter, nonLibrary).any { it != TriState.DISABLED }
}

fun historyFilters(preferences: HistoryPreferences): Flow<HistoryFilters> = combine(
    preferences.filterUnfinishedManga().changes(),
    preferences.filterUnfinishedChapter().changes(),
    preferences.filterNonLibraryManga().changes(),
) { manga, chapter, nonLibrary -> HistoryFilters(manga, chapter, nonLibrary) }

/** What a tri-state filter asks the history query for: only, only not, or anything. */
internal fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}

/**
 * Selection over the history list by chapter id, as Komikku's HistoryScreenModel: Shift + click
 * selects the rows between the last selected one and the clicked one.
 */
internal object HistorySelection {
    fun toggle(selected: Set<Long>, id: Long): Set<Long> = if (id in selected) selected - id else selected + id

    fun range(ids: List<Long>, selected: Set<Long>, id: Long): Set<Long> {
        val to = ids.indexOf(id)
        if (to < 0) return selected
        val from = ids.indexOfLast { it in selected }.takeIf { it >= 0 } ?: to
        val range = if (from <= to) from..to else to..from
        return selected + range.map { ids[it] }
    }

    fun invert(ids: List<Long>, selected: Set<Long>): Set<Long> = ids.filterTo(LinkedHashSet()) { it !in selected }
}

class HistoryScreenModel(
    getHistory: GetHistory = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    val preferences: HistoryPreferences = Injekt.get(),
) : StateScreenModel<HistoryScreenModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val query: String = "",
        val items: List<HistoryWithRelations> = emptyList(),
        val filters: HistoryFilters = HistoryFilters(),
        /** Chapter names by chapter id, as the chapter list shows them */
        val chapterNames: Map<Long, String> = emptyMap(),
        // ZOUKAN -->
        /** Chapter ids picked for removal, as Komikku's History selection */
        val selected: Set<Long> = emptySet(),
        /** Komikku's selection mode, which the select button turns on before anything is picked */
        val selectionMode: Boolean = false,
        // ZOUKAN <--
    ) {
        val selecting: Boolean get() = selectionMode || selected.isNotEmpty()
        val selectedItems: List<HistoryWithRelations> get() = items.filter { it.chapterId in selected }
    }

    private val query = MutableStateFlow("")

    init {
        screenModelScope.launch {
            val filters = historyFilters(preferences).distinctUntilChanged()

            @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
            combine(query.debounce(200), filters) { q, f -> q to f }
                .distinctUntilChanged()
                .flatMapLatest { (q, f) ->
                    getHistory.subscribe(
                        q,
                        unfinishedManga = f.unfinishedManga.toBooleanOrNull(),
                        unfinishedChapter = f.unfinishedChapter.toBooleanOrNull(),
                        nonLibraryEntries = f.nonLibrary.toBooleanOrNull(),
                    ).map { items -> items to f }
                }
                .collect { (items, f) ->
                    val ids = items.mapTo(HashSet()) { it.chapterId }
                    mutableState.update {
                        it.copy(
                            loading = false,
                            items = items,
                            filters = f,
                            // Rows that left the list leave the selection too
                            selected = it.selected.filterTo(HashSet()) { id -> id in ids },
                        )
                    }
                    loadChapterNames(items)
                }
        }
    }

    // History rows only carry the chapter number; sources name chapters like "Group 2 Chapter 1"
    private suspend fun loadChapterNames(items: List<HistoryWithRelations>) {
        val known = state.value.chapterNames
        val missing = items.map { it.chapterId }.distinct().filterNot { it in known }
        if (missing.isEmpty()) return
        val names = missing.mapNotNull { id -> getChapter.await(id)?.let { id to it.name } }.toMap()
        mutableState.update { it.copy(chapterNames = it.chapterNames + names) }
    }

    fun search(text: String) {
        query.value = text
        mutableState.update { it.copy(query = text) }
    }

    /**
     * Removes [items] from the history, as Komikku's remove dialog: these chapters only, or with
     * [wholeEntry] every chapter of their entries.
     */
    fun remove(items: List<HistoryWithRelations>, wholeEntry: Boolean) {
        screenModelScope.launch {
            if (wholeEntry) {
                removeHistory.awaitManga(items.map { it.mangaId }.distinct())
            } else {
                removeHistory.await(items.map { it.id })
            }
        }
        clearSelection()
    }

    fun clearAll() {
        screenModelScope.launch {
            removeHistory.awaitAll()
            Toasts.show("History cleared")
        }
        clearSelection()
    }

    /** The chapter to open: this one if unfinished, else the next unread one. */
    suspend fun chapterToOpen(item: HistoryWithRelations): Long? {
        if (!item.read) return item.chapterId
        return getNextChapters.await(item.mangaId, item.chapterId, onlyUnread = true).firstOrNull()?.id
    }

    // ZOUKAN -->
    fun startSelection() = mutableState.update { it.copy(selectionMode = true) }

    fun toggleSelection(item: HistoryWithRelations) = mutableState.update {
        it.copy(selected = HistorySelection.toggle(it.selected, item.chapterId))
    }

    fun selectRange(item: HistoryWithRelations) = mutableState.update { s ->
        s.copy(selected = HistorySelection.range(s.items.map { it.chapterId }, s.selected, item.chapterId))
    }

    fun selectAll() = mutableState.update { s -> s.copy(selected = s.items.mapTo(HashSet()) { it.chapterId }) }

    fun invertSelection() = mutableState.update { s ->
        s.copy(selected = HistorySelection.invert(s.items.map { it.chapterId }, s.selected))
    }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet(), selectionMode = false) }
    // ZOUKAN <--
}

@Composable
fun HistoryTab(model: HistoryScreenModel) {
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val libraryToggle = rememberQuickLibraryToggle()
    val panorama by model.preferences.usePanoramaCover().let { p -> p.changes().collectAsState(p.get()) }
    var confirmClear by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<List<HistoryWithRelations>?>(null) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    // Ctrl/Cmd + A selects every row shown; Escape ends the selection before it means "back"
    OnShortcut { action ->
        when (action) {
            Shortcuts.Action.SelectAll -> {
                model.selectAll()
                true
            }
            Shortcuts.Action.Back -> state.selecting.also { if (it) model.clearSelection() }
            else -> false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionHeader(
                state = state,
                model = model,
                onRemove = { remove = state.selectedItems },
            )
        } else {
            ScreenHeader("History", showBack = false) {
                SearchField(state.query, model::search, "Search history", Modifier.width(280.dp).padding(end = 8.dp))
                HistoryFilterButton(model.preferences, state.filters)
                // Komikku's toolbar selects rows; removing them all clears the history
                TooltipIconButton(Icons.Outlined.Checklist, "Select", onClick = model::startSelection, enabled = state.items.isNotEmpty())
                TooltipIconButton(Icons.Outlined.DeleteSweep, "Clear history", onClick = { confirmClear = true }, enabled = state.items.isNotEmpty())
            }
        }
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() && state.query.isNotBlank() -> EmptyScreen("Nothing matches \"${state.query}\"")
            state.items.isEmpty() && state.filters.active -> EmptyScreen("No history matches the filters")
            state.items.isEmpty() -> EmptyScreen("Nothing read yet")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.items.groupBy { it.readAt?.time?.let(zoukan.desktop.ui.common.Dates::relative) ?: "Unknown" }.forEach { (date, items) ->
                    sectionHeader(date)
                    items(items, key = { it.id }) { item ->
                        val chapter = state.chapterNames[item.chapterId]?.takeIf { it.isNotBlank() }
                            ?: if (item.chapterNumber > -1) "Chapter ${formatNumber(item.chapterNumber)}" else "Chapter"
                        val time = item.readAt?.toInstant()?.atZone(java.time.ZoneId.systemDefault())?.let(timeFormat::format)
                        HistoryRow(
                            item = item,
                            subtitle = listOfNotNull(chapter, time).joinToString(" · ") +
                                if (!item.read && item.lastPageRead > 0) " · Page ${item.lastPageRead + 1}" else "",
                            selected = item.chapterId in state.selected,
                            panorama = panorama,
                            onClick = { kind ->
                                when {
                                    kind == ClickKind.Range -> model.selectRange(item)
                                    kind == ClickKind.Toggle || state.selecting -> model.toggleSelection(item)
                                    else -> model.screenModelScopeLaunch {
                                        val chapterId = model.chapterToOpen(item)
                                        if (chapterId != null) {
                                            navigator.push(ReaderScreen(item.mangaId, chapterId))
                                        } else {
                                            Toasts.show("No unread chapters left")
                                        }
                                    }
                                }
                            },
                            onOpenManga = { navigator.push(MangaScreen(item.mangaId)) },
                            onAddToLibrary = { libraryToggle.toggle(Manga.create().copy(id = item.mangaId)) },
                            onRemove = { remove = listOf(item) },
                        )
                    }
                }
            }
        }
    }

    QuickLibraryToggleDialogs(libraryToggle)
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This removes all reading history. Read chapters stay marked as read.") },
            confirmButton = {
                TextButton(onClick = {
                    model.clearAll()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    remove?.let { items ->
        RemoveDialog(
            items = items,
            onRemove = { wholeEntry ->
                model.remove(items, wholeEntry)
                remove = null
            },
            onDismiss = { remove = null },
        )
    }
}

/** The toolbar while rows are selected, as Komikku's: the count, select all, invert and remove. */
@Composable
private fun SelectionHeader(state: HistoryScreenModel.State, model: HistoryScreenModel, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("history-selection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(Icons.Outlined.Close, "Clear selection", onClick = model::clearSelection)
        Text("${state.selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = model::selectAll)
        TooltipIconButton(Icons.Outlined.FlipToBack, "Invert selection", onClick = model::invertSelection)
        TooltipIconButton(Icons.Outlined.DeleteSweep, "Remove from history", onClick = onRemove, enabled = state.selected.isNotEmpty())
    }
}

/**
 * Komikku's history row: the cover (a panorama one for a wide cover when that setting is on),
 * the title, a dot while the entry has unread chapters, the chapter and when it was read, and
 * the add to library and remove buttons. Read chapters are dimmed.
 */
@Composable
private fun HistoryRow(
    item: HistoryWithRelations,
    subtitle: String,
    selected: Boolean,
    panorama: Boolean,
    onClick: (ClickKind) -> Unit,
    onOpenManga: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemove: () -> Unit,
) {
    // Height over width of the loaded cover; Komikku shows a cover this wide as a panorama
    var ratio by remember(item.mangaId) { mutableFloatStateOf(1.5f) }
    val wide = panorama && ratio <= PANORAMA_RATIO
    Row(
        Modifier.fillMaxWidth()
            .testTag("history-item")
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectableClick(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = Corners.Medium
        CoverImage(
            Cover.of(item.coverData),
            Modifier.size(if (wide) 107.dp else 44.dp, 60.dp).clip(shape).coverOutline(shape).clickable(onClick = onOpenManga),
            onLoaded = { w, h -> if (w > 0) ratio = h.toFloat() / w },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (item.read) 0.5f else 1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.unreadCount > 0) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!item.coverData.isMangaFavorite) {
            TooltipIconButton(Icons.Outlined.FavoriteBorder, "Add to library", onClick = onAddToLibrary)
        }
        TooltipIconButton(Icons.Outlined.Delete, "Remove from history", onClick = onRemove)
    }
}

/** Komikku's remove dialog: the read date of these chapters, or every chapter of their entries. */
@Composable
private fun RemoveDialog(items: List<HistoryWithRelations>, onRemove: (wholeEntry: Boolean) -> Unit, onDismiss: () -> Unit) {
    var wholeEntry by remember { mutableStateOf(false) }
    val one = items.size == 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (one) "Remove from history" else "Remove ${items.size} from history") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (one) Text(items.first().title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (one) "This removes the read date of this chapter." else "This removes the read date of these chapters.",
                )
                Row(
                    Modifier.fillMaxWidth().clickable { wholeEntry = !wholeEntry },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = wholeEntry, onCheckedChange = { wholeEntry = it })
                    Text(if (one) "Remove every chapter of this entry" else "Remove every chapter of these entries")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRemove(wholeEntry) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The History toolbar's filter button, as Komikku's, with a dot while a filter is on. */
@Composable
private fun HistoryFilterButton(preferences: HistoryPreferences, filters: HistoryFilters) {
    var open by remember { mutableStateOf(false) }
    BadgedBox(badge = { if (filters.active) Badge() }) {
        TooltipIconButton(
            Icons.Outlined.FilterList,
            if (filters.active) "Filter (filtered)" else "Filter",
            onClick = { open = true },
        )
    }
    if (open) HistoryFilterDialog(preferences, onDismiss = { open = false })
}

@Composable
private fun HistoryFilterDialog(preferences: HistoryPreferences, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(Modifier.width(320.dp)) {
                TriStateRow("Unfinished entries", preferences.filterUnfinishedManga(), notLabel = "Finished entries")
                TriStateRow("Unfinished chapters", preferences.filterUnfinishedChapter(), notLabel = "Finished chapters")
                TriStateRow("Not in library", preferences.filterNonLibraryManga(), notLabel = "In library")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val panorama = preferences.usePanoramaCover()
                val on by panorama.changes().collectAsState(panorama.get())
                Row(
                    Modifier.fillMaxWidth().clickable { panorama.set(!on) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Panorama cover", modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { panorama.set(it) })
                }
            }
        },
    )
}

/** Komikku's RatioSwitchToPanorama: covers this wide or wider show as panoramas. */
private const val PANORAMA_RATIO = 0.75f

private fun HistoryScreenModel.screenModelScopeLaunch(block: suspend () -> Unit) {
    screenModelScope.launch { block() }
}

private fun formatNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
