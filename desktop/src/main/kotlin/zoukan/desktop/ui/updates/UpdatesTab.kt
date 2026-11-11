package zoukan.desktop.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.browse.sectionHeader
import zoukan.desktop.ui.common.Cover
import zoukan.desktop.ui.common.CoverImage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LoadingScreen
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.downloads.DownloadsButton
import zoukan.desktop.ui.library.LibraryUpdateProgress
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.upcoming.UpcomingScreen
import java.time.Instant
import java.time.temporal.ChronoUnit

class UpdatesScreenModel(
    getUpdates: GetUpdates = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
) : StateScreenModel<UpdatesScreenModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val items: List<UpdatesWithRelations> = emptyList(),
        val unreadOnly: Boolean = false,
    )

    private val unreadOnly = MutableStateFlow(false)

    init {
        screenModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            unreadOnly.flatMapLatest { unread ->
                getUpdates.subscribe(
                    Instant.now().minus(90, ChronoUnit.DAYS),
                    unread = if (unread) true else null,
                    started = null,
                    bookmarked = null,
                    hideExcludedScanlators = false,
                )
            }.collect { items -> mutableState.update { it.copy(loading = false, items = items) } }
        }
    }

    fun toggleUnreadOnly() {
        unreadOnly.value = !unreadOnly.value
        mutableState.update { it.copy(unreadOnly = unreadOnly.value) }
    }

    fun setRead(item: UpdatesWithRelations, read: Boolean) {
        screenModelScope.launch {
            updateChapter.await(ChapterUpdate(id = item.chapterId, read = read, lastPageRead = if (read) null else 0L))
            // ZOUKAN -->
            if (read && !item.read) {
                Injekt.get<tachiyomi.domain.chapter.interactor.GetChapter>().await(item.chapterId)?.let {
                    Injekt.get<zoukan.desktop.download.DownloadManager>().afterMarkedRead(listOf(it))
                }
            }
            // ZOUKAN <--
        }
    }

    fun refresh() {
        if (!Injekt.get<LibraryUpdater>().start()) Toasts.show("A library update is already running")
    }
}

@Composable
fun UpdatesTab(model: UpdatesScreenModel) {
    val state by model.state.collectAsState()
    val refresh by Injekt.get<LibraryUpdater>().progress.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Updates", subtitle = "New chapters in your library from the last 90 days", showBack = false) {
            FilterChip(selected = state.unreadOnly, onClick = model::toggleUnreadOnly, label = { Text("Unread only") })
            // Android's Updates toolbar opens Upcoming from its calendar button
            TooltipIconButton(Icons.Outlined.CalendarMonth, "View Upcoming Updates", onClick = { navigator.push(UpcomingScreen) })
            DownloadsButton()
            TooltipIconButton(Icons.Default.Refresh, "Update library", onClick = model::refresh, enabled = refresh == null)
        }
        LibraryUpdateProgress()
        when {
            state.loading -> LoadingScreen()
            state.items.isEmpty() -> EmptyScreen("No recent updates. Press refresh to check your library for new chapters.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.items.groupBy { zoukan.desktop.ui.common.Dates.relative(it.dateFetch) }.forEach { (date, items) ->
                    sectionHeader(date)
                    items(items, key = { it.chapterId }) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { navigator.push(ReaderScreen(item.mangaId, item.chapterId)) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(
                                Cover.of(item.coverData),
                                Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(6.dp)).clickable { navigator.push(MangaScreen(item.mangaId)) },
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
                                TooltipIconButton(Icons.Default.RemoveDone, "Mark as unread", onClick = { model.setRead(item, false) })
                            } else {
                                TooltipIconButton(Icons.Default.DoneAll, "Mark as read", onClick = { model.setRead(item, true) })
                            }
                        }
                    }
                }
            }
        }
    }
}
