package zoukan.desktop.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HistoryScreenModel(
    getHistory: GetHistory = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
) : StateScreenModel<HistoryScreenModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val query: String = "",
        val items: List<HistoryWithRelations> = emptyList(),
        /** Chapter names by chapter id, as the chapter list shows them */
        val chapterNames: Map<Long, String> = emptyMap(),
    )

    private val query = MutableStateFlow("")

    init {
        screenModelScope.launch {
            @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
            query.debounce(200).flatMapLatest { getHistory.subscribe(it, null, null, null) }
                .collect { items ->
                    mutableState.update { it.copy(loading = false, items = items) }
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

    fun remove(item: HistoryWithRelations, wholeEntry: Boolean) {
        screenModelScope.launch {
            if (wholeEntry) removeHistory.awaitManga(listOf(item.mangaId)) else removeHistory.await(listOf(item.id))
        }
    }

    fun clearAll() {
        screenModelScope.launch {
            removeHistory.awaitAll()
            Toasts.show("History cleared")
        }
    }

    /** The chapter to open: this one if unfinished, else the next unread one. */
    suspend fun chapterToOpen(item: HistoryWithRelations): Long? {
        if (!item.read) return item.chapterId
        return getNextChapters.await(item.mangaId, item.chapterId, onlyUnread = true).firstOrNull()?.id
    }
}

@Composable
fun HistoryTab(model: HistoryScreenModel) {
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    var confirmClear by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<HistoryWithRelations?>(null) }
    val timeFormat = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("History", showBack = false) {
            SearchField(state.query, model::search, "Search history", Modifier.width(280.dp).padding(end = 8.dp))
            TooltipIconButton(Icons.Outlined.DeleteSweep, "Clear history", onClick = { confirmClear = true }, enabled = state.items.isNotEmpty())
        }
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() -> EmptyScreen(if (state.query.isBlank()) "Nothing read yet" else "Nothing matches \"${state.query}\"")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.items.groupBy { it.readAt?.time?.let(zoukan.desktop.ui.common.Dates::relative) ?: "Unknown" }.forEach { (date, items) ->
                    sectionHeader(date)
                    items(items, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    model.screenModelScopeLaunch {
                                        val chapterId = model.chapterToOpen(item)
                                        if (chapterId != null) {
                                            navigator.push(ReaderScreen(item.mangaId, chapterId))
                                        } else {
                                            Toasts.show("No unread chapters left")
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(
                                Cover.of(item.coverData),
                                Modifier.size(44.dp, 60.dp).clip(Corners.Medium).coverOutline(Corners.Medium).clickable { navigator.push(MangaScreen(item.mangaId)) },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val chapter = state.chapterNames[item.chapterId]?.takeIf { it.isNotBlank() }
                                    ?: if (item.chapterNumber > -1) "Chapter ${formatNumber(item.chapterNumber)}" else "Chapter"
                                val time = item.readAt?.toInstant()?.atZone(java.time.ZoneId.systemDefault())?.let(timeFormat::format)
                                Text(
                                    listOfNotNull(chapter, time).joinToString(" · ") +
                                        if (!item.read && item.lastPageRead > 0) " · Page ${item.lastPageRead + 1}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TooltipIconButton(Icons.Outlined.Delete, "Remove", onClick = { remove = item })
                        }
                    }
                }
            }
        }
    }

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
    remove?.let { item ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove from history") },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(onClick = {
                    model.remove(item, wholeEntry = true)
                    remove = null
                }) { Text("All chapters of this entry") }
            },
            dismissButton = {
                TextButton(onClick = {
                    model.remove(item, wholeEntry = false)
                    remove = null
                }) { Text("This chapter only") }
            },
        )
    }
}

private fun HistoryScreenModel.screenModelScopeLaunch(block: suspend () -> Unit) {
    screenModelScope.launch { block() }
}

private fun formatNumber(number: Double): String =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
