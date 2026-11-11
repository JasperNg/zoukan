package zoukan.desktop.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.DropdownMenu
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.settings.openFolder
import zoukan.i18n.desktop.DMR

/** The download queue. */
object DownloadsScreen : Screen {
    private fun readResolve(): Any = DownloadsScreen

    override val key: ScreenKey = "downloads"

    @Composable
    override fun Content() {
        val manager = remember { Injekt.get<DownloadManager>() }
        val storage = remember { Injekt.get<ZoukanStorage>() }
        val queue by manager.queue.collectAsState()
        val running by manager.running.collectAsState()
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(localized(MR.strings.pref_category_downloads), subtitle = if (queue.isEmpty()) storage.downloads.path else localized(DMR.strings.in_the_queue, queue.size)) {
                if (queue.isNotEmpty()) {
                    if (running) {
                        TooltipIconButton(Icons.Outlined.Pause, localized(MR.strings.action_pause), onClick = manager::pause)
                    } else {
                        TooltipIconButton(Icons.Outlined.PlayArrow, localized(MR.strings.action_start), onClick = manager::start)
                    }
                    QueueSortButton(manager)
                    TooltipIconButton(Icons.Outlined.ClearAll, localized(MR.strings.action_cancel_all), onClick = manager::clearQueue)
                }
                TooltipIconButton(Icons.Outlined.FolderOpen, localized(DMR.strings.open_the_downloads_folder), onClick = { openFolder(storage.downloads) })
            }
            if (queue.isEmpty()) {
                EmptyScreen(localized(DMR.strings.no_downloads_queued_download_chapters_from))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(queue, key = { it.chapter.id }) { download ->
                        DownloadRow(download, manager)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(download: Download, manager: DownloadManager) {
    val state by download.state.collectAsState()
    val progress by download.progress.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(download.manga.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                download.chapter.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val (done, total) = progress
            when (state) {
                Download.State.DOWNLOADING -> if (total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                Download.State.ERROR -> Text(
                    download.error ?: localized(DMR.strings.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
                else -> {}
            }
        }
        if (state == Download.State.DOWNLOADING && progress.second > 0) {
            Text("${progress.first} / ${progress.second}", style = MaterialTheme.typography.labelMedium)
        }
        if (state == Download.State.QUEUED) {
            TooltipIconButton(Icons.Outlined.VerticalAlignTop, localized(DMR.strings.download_first), onClick = { manager.moveToTop(download) })
        }
        TooltipIconButton(Icons.Outlined.Close, localized(MR.strings.action_cancel), onClick = { manager.cancel(setOf(download.chapter.id)) })
        DownloadMenu(download, manager)
    }
}

/** Android's per-download menu: move it or its series to the top or bottom, or cancel the series. */
@Composable
private fun DownloadMenu(download: Download, manager: DownloadManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Outlined.MoreVert, localized(MR.strings.label_more), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(localized(MR.strings.action_move_to_top)) }, onClick = {
                open = false
                manager.moveToTop(download)
            })
            DropdownMenuItem(text = { Text(localized(MR.strings.action_move_to_top_all_for_series)) }, onClick = {
                open = false
                manager.moveSeries(download.manga.id, toTop = true)
            })
            DropdownMenuItem(text = { Text(localized(MR.strings.action_move_to_bottom)) }, onClick = {
                open = false
                manager.moveToBottom(download)
            })
            DropdownMenuItem(text = { Text(localized(MR.strings.action_move_to_bottom_all_for_series)) }, onClick = {
                open = false
                manager.moveSeries(download.manga.id, toTop = false)
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(localized(MR.strings.action_cancel)) }, onClick = {
                open = false
                manager.cancel(setOf(download.chapter.id))
            })
            DropdownMenuItem(text = { Text(localized(MR.strings.cancel_all_for_series)) }, onClick = {
                open = false
                manager.cancelSeries(download.manga.id)
            })
        }
    }
}

/** Android's queue sort: by upload date (newest or oldest) or chapter number, within each source. */
@Composable
private fun QueueSortButton(manager: DownloadManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.AutoMirrored.Outlined.Sort, localized(MR.strings.action_sort), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val sorts: List<Pair<String, () -> Unit>> = listOf(
                localized(DMR.strings.upload_date_newest) to { manager.sortQueue(descending = true) { it.chapter.dateUpload } },
                localized(DMR.strings.upload_date_oldest) to { manager.sortQueue(descending = false) { it.chapter.dateUpload } },
                localized(DMR.strings.chapter_number_ascending) to { manager.sortQueue(descending = false) { it.chapter.chapterNumber } },
                localized(DMR.strings.chapter_number_descending) to { manager.sortQueue(descending = true) { it.chapter.chapterNumber } },
            )
            sorts.forEach { (label, sort) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    sort()
                })
            }
        }
    }
}

/** The header button to the download queue, with the queue's size. */
@Composable
fun DownloadsButton() {
    val manager = remember { Injekt.get<DownloadManager>() }
    val queue by manager.queue.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    Box {
        BadgedBox(badge = { if (queue.isNotEmpty()) Badge { Text("${queue.size}") } }) {
            TooltipIconButton(Icons.Outlined.Download, localized(MR.strings.pref_category_downloads), onClick = { navigator.push(DownloadsScreen) })
        }
    }
}
