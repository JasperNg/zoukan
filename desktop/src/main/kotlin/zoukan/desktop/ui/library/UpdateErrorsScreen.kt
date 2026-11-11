package zoukan.desktop.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdateErrors
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.common.coverOutline
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.pickScreen

/**
 * Komikku's library update errors screen: the entries whose last update failed, grouped by the
 * error, newest first within a group. Click an entry to migrate it, as on Android; its cover opens
 * it. Selected entries can be retried, migrated or their errors removed.
 */
class UpdateErrorsScreenModel(
    private val getErrors: GetLibraryUpdateErrorWithRelations = Injekt.get(),
    private val getMessages: GetLibraryUpdateErrorMessages = Injekt.get(),
    private val errors: LibraryUpdateErrors = Injekt.get(),
    private val updater: LibraryUpdater = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<UpdateErrorsScreenModel.State>(State()) {

    @Immutable
    data class Item(val error: LibraryUpdateErrorWithRelations, val sourceName: String) {
        val id: Long get() = error.errorId
        val cover = MangaCover(
            mangaId = error.mangaId,
            sourceId = error.mangaSource,
            isMangaFavorite = error.favorite,
            ogUrl = error.mangaThumbnail,
            lastModified = error.coverLastModified,
        )
    }

    @Immutable
    data class Group(val message: String, val items: List<Item>)

    @Immutable
    data class State(
        val loading: Boolean = true,
        val groups: List<Group> = emptyList(),
        val selection: Set<Long> = emptySet(),
    ) {
        val items: List<Item> = groups.flatMap { it.items }
        val selected: List<Item> get() = items.filter { it.id in selection }
    }

    /** The last plain selection, the anchor of a Shift range */
    private var anchor: Long? = null

    init {
        screenModelScope.launch {
            combine(getErrors.subscribeAll(), messages()) { list, messages -> group(list, messages) }
                .collectLatest { groups ->
                    mutableState.update { state ->
                        val ids = groups.flatMap { g -> g.items.map { it.id } }.toSet()
                        state.copy(loading = false, groups = groups, selection = state.selection intersect ids)
                    }
                }
        }
    }

    private fun messages(): Flow<Map<Long, String>> = getMessages.subscribe().map { list -> list.associate { it.id to it.message } }

    /** Grouped by message id, as Android sorts them; each group keeps the newest-first order of the view. */
    internal fun group(list: List<LibraryUpdateErrorWithRelations>, messages: Map<Long, String>): List<Group> =
        list.groupBy { it.messageId }
            .toSortedMap()
            .map { (messageId, errors) ->
                Group(
                    messages[messageId] ?: "Unknown error",
                    errors.map { Item(it, sourceManager.getOrStub(it.mangaSource).name) },
                )
            }

    fun toggle(item: Item, kind: ClickKind) {
        val anchorId = anchor
        anchor = item.id
        mutableState.update { state ->
            val ids = state.items.map { it.id }
            // The list may have changed since it was drawn (a retry cleared the entry)
            val to = ids.indexOf(item.id)
            if (to < 0) return@update state
            val from = anchorId?.let(ids::indexOf)?.takeIf { it >= 0 }
            val selection = if (kind == ClickKind.Range && from != null) {
                state.selection + ids.subList(minOf(from, to), maxOf(from, to) + 1)
            } else if (item.id in state.selection) {
                state.selection - item.id
            } else {
                state.selection + item.id
            }
            state.copy(selection = selection)
        }
    }

    fun selectAll() = mutableState.update { it.copy(selection = it.items.map { item -> item.id }.toSet()) }

    fun invertSelection() = mutableState.update { state ->
        state.copy(selection = state.items.map { it.id }.toSet() - state.selection)
    }

    fun clearSelection() = mutableState.update { it.copy(selection = emptySet()) }

    /** Removes the errors of the selection, or of [item], without updating the entries. */
    fun delete(item: Item? = null) {
        val ids = item?.let { listOf(it.id) } ?: state.value.selection.toList()
        if (ids.isEmpty()) return
        screenModelScope.launch {
            withContext(Dispatchers.IO) { errors.delete(ids) }
            mutableState.update { it.copy(selection = it.selection - ids.toSet()) }
        }
    }

    /** Updates the selected entries, or all of them, again; the ones that update leave the list. */
    fun retry(item: Item? = null) {
        val state = state.value
        val targets = item?.let(::listOf) ?: state.selected.ifEmpty { state.items }
        if (targets.isEmpty()) return
        val started = updater.start(mangaIds = targets.map { it.error.mangaId }.toSet()) { result ->
            val fixed = result.updated - result.failures.size
            Toasts.show(
                when {
                    result.failures.isEmpty() -> if (fixed == 1) "The entry updated" else "All $fixed entries updated"
                    fixed == 0 -> if (result.failures.size == 1) "The entry failed again" else "All ${result.failures.size} entries failed again"
                    else -> "$fixed updated, ${result.failures.size} failed again"
                },
            )
        }
        if (started) {
            Toasts.show(if (targets.size == 1) "Retrying ${targets.first().error.mangaTitle}" else "Retrying ${targets.size} entries")
            if (item == null) clearSelection()
        } else {
            Toasts.show("A library update is already running")
        }
    }

    suspend fun manga(item: Item) = withContext(Dispatchers.IO) { getManga.await(item.error.mangaId) }
}

object UpdateErrorsScreen : Screen {
    private fun readResolve(): Any = UpdateErrorsScreen

    override val key: ScreenKey = "library-update-errors"

    @Composable
    override fun Content() {
        val model = rememberScreenModel { UpdateErrorsScreenModel() }
        val state by model.state.collectAsState()
        val updating by remember { Injekt.get<LibraryUpdater>().progress }.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        fun migrate(item: UpdateErrorsScreenModel.Item) {
            scope.launch {
                val manga = model.manga(item) ?: return@launch Toasts.show("The entry is gone")
                navigator.push(pickScreen(manga, merge = false))
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (state.selection.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TooltipIconButton(Icons.Outlined.Close, "Clear selection", onClick = model::clearSelection)
                        Text(
                            "${state.selection.size} selected",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = model::selectAll)
                        TooltipIconButton(Icons.Outlined.FlipToBack, "Invert selection", onClick = model::invertSelection)
                        TooltipIconButton(Icons.Outlined.Refresh, "Retry", onClick = { model.retry() }, enabled = updating == null)
                        TooltipIconButton(
                            Icons.Outlined.FindReplace,
                            if (state.selection.size == 1) "Migrate" else "Migrate (one entry at a time)",
                            onClick = { state.selected.singleOrNull()?.let(::migrate) },
                            enabled = state.selection.size == 1,
                        )
                        TooltipIconButton(Icons.Outlined.DeleteOutline, "Delete", onClick = { model.delete() })
                    }
                }
            } else {
                ScreenHeader("Library update errors (${state.items.size})") {
                    TooltipIconButton(
                        Icons.Outlined.Refresh,
                        "Retry all",
                        onClick = { model.retry() },
                        enabled = updating == null && state.items.isNotEmpty(),
                    )
                    if (state.items.isNotEmpty()) TooltipIconButton(Icons.Outlined.SelectAll, "Select all", onClick = model::selectAll)
                }
            }
            LibraryUpdateProgress()
            when {
                state.loading -> LoadingScreen()
                state.items.isEmpty() -> EmptyScreen("You have no library update errors.")
                else -> {
                    ErrorList(
                        state,
                        listState,
                        Modifier.weight(1f),
                        onClick = { item, kind ->
                            when {
                                kind != ClickKind.Plain || state.selection.isNotEmpty() -> model.toggle(item, kind)
                                else -> migrate(item)
                            }
                        },
                        onOpen = { navigator.push(MangaScreen(it.error.mangaId)) },
                        onMigrate = ::migrate,
                        onRetry = { model.retry(it) },
                        onDelete = { model.delete(it) },
                        retryEnabled = updating == null,
                    )
                    ScrollBar(state, listState)
                }
            }
        }
    }
}

/** The list index of each group's header. */
private fun headerIndexes(state: UpdateErrorsScreenModel.State): List<Int> {
    var index = 0
    return state.groups.map { group -> index.also { index += group.items.size + 1 } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorList(
    state: UpdateErrorsScreenModel.State,
    listState: LazyListState,
    modifier: Modifier,
    onClick: (UpdateErrorsScreenModel.Item, ClickKind) -> Unit,
    onOpen: (UpdateErrorsScreenModel.Item) -> Unit,
    onMigrate: (UpdateErrorsScreenModel.Item) -> Unit,
    onRetry: (UpdateErrorsScreenModel.Item) -> Unit,
    onDelete: (UpdateErrorsScreenModel.Item) -> Unit,
    retryEnabled: Boolean,
) {
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        state.groups.forEach { group ->
            stickyHeader(key = "header-${group.message}-${group.items.first().error.messageId}") {
                Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.message,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${group.items.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            group.items.forEach { item ->
                item(key = "error-${item.id}") {
                    val selected = item.id in state.selection
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("update-error")
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .selectableClick { onClick(item, it) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.selection.isNotEmpty()) {
                            Checkbox(selected, onCheckedChange = { onClick(item, ClickKind.Toggle) })
                        }
                        CoverImage(
                            Cover.of(item.cover),
                            Modifier.size(48.dp).clip(Corners.Medium).coverOutline(Corners.Medium).clickable(enabled = state.selection.isEmpty()) { onOpen(item) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.error.mangaTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                item.sourceName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.selection.isEmpty()) {
                            TooltipIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open entry", onClick = { onOpen(item) })
                            TooltipIconButton(Icons.Outlined.Refresh, "Retry", onClick = { onRetry(item) }, enabled = retryEnabled)
                            TooltipIconButton(Icons.Outlined.FindReplace, "Migrate", onClick = { onMigrate(item) })
                            TooltipIconButton(Icons.Outlined.DeleteOutline, "Delete", onClick = { onDelete(item) })
                        }
                    }
                }
            }
        }
    }
}

/** Android's bottom bar: to the top, the previous error group, the next one and the bottom. */
@Composable
private fun ScrollBar(state: UpdateErrorsScreenModel.State, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val headers = remember(state.groups) { headerIndexes(state) }
    val lastIndex = state.items.size + state.groups.size - 1
    val first by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    val canScrollForward by remember(listState) { derivedStateOf { listState.canScrollForward } }
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            TooltipIconButton(
                Icons.Outlined.VerticalAlignTop,
                "Scroll to top",
                onClick = { scope.launch { listState.scrollToItem(0) } },
                enabled = first > 0,
            )
            TooltipIconButton(
                Icons.Outlined.ArrowUpward,
                "Previous error",
                onClick = { scope.launch { listState.scrollToItem(headers.filter { it < first }.maxOrNull() ?: 0) } },
                enabled = headers.any { it < first },
            )
            TooltipIconButton(
                Icons.Outlined.ArrowDownward,
                "Next error",
                onClick = { scope.launch { headers.firstOrNull { it > first }?.let { listState.scrollToItem(it) } } },
                enabled = canScrollForward && headers.any { it > first },
            )
            TooltipIconButton(
                Icons.Outlined.VerticalAlignBottom,
                "Scroll to bottom",
                onClick = { scope.launch { listState.scrollToItem(lastIndex.coerceAtLeast(0)) } },
                enabled = canScrollForward,
            )
        }
    }
}
