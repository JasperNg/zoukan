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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.Download
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.common.EmptyScreen
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.TooltipIconButton
import zoukan.desktop.ui.settings.openFolder

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
            ScreenHeader("Downloads", subtitle = if (queue.isEmpty()) storage.downloads.path else "${queue.size} in the queue") {
                if (queue.isNotEmpty()) {
                    if (running) {
                        TooltipIconButton(Icons.Default.Pause, "Pause", onClick = manager::pause)
                    } else {
                        TooltipIconButton(Icons.Default.PlayArrow, "Start", onClick = manager::start)
                    }
                    QueueSortButton(manager)
                    TooltipIconButton(Icons.Default.ClearAll, "Cancel all", onClick = manager::clearQueue)
                }
                TooltipIconButton(Icons.Default.FolderOpen, "Open the downloads folder", onClick = { openFolder(storage.downloads) })
            }
            if (queue.isEmpty()) {
                EmptyScreen("No downloads queued. Download chapters from an entry's chapter list.")
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
                    download.error ?: "Failed",
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
            TooltipIconButton(Icons.Default.VerticalAlignTop, "Download first", onClick = { manager.moveToTop(download) })
        }
        TooltipIconButton(Icons.Default.Close, "Cancel", onClick = { manager.cancel(setOf(download.chapter.id)) })
        DownloadMenu(download, manager)
    }
}

/** Android's per-download menu: move it or its series to the top or bottom, or cancel the series. */
@Composable
private fun DownloadMenu(download: Download, manager: DownloadManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Default.MoreVert, "More", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Move to top") }, onClick = {
                open = false
                manager.moveToTop(download)
            })
            DropdownMenuItem(text = { Text("Move series to top") }, onClick = {
                open = false
                manager.moveSeries(download.manga.id, toTop = true)
            })
            DropdownMenuItem(text = { Text("Move to bottom") }, onClick = {
                open = false
                manager.moveToBottom(download)
            })
            DropdownMenuItem(text = { Text("Move series to bottom") }, onClick = {
                open = false
                manager.moveSeries(download.manga.id, toTop = false)
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Cancel") }, onClick = {
                open = false
                manager.cancel(setOf(download.chapter.id))
            })
            DropdownMenuItem(text = { Text("Cancel all for this series") }, onClick = {
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
        TooltipIconButton(Icons.AutoMirrored.Filled.Sort, "Sort", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val sorts: List<Pair<String, () -> Unit>> = listOf(
                "Upload date, newest" to { manager.sortQueue(descending = true) { it.chapter.dateUpload } },
                "Upload date, oldest" to { manager.sortQueue(descending = false) { it.chapter.dateUpload } },
                "Chapter number, ascending" to { manager.sortQueue(descending = false) { it.chapter.chapterNumber } },
                "Chapter number, descending" to { manager.sortQueue(descending = true) { it.chapter.chapterNumber } },
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
            TooltipIconButton(Icons.Outlined.Download, "Downloads", onClick = { navigator.push(DownloadsScreen) })
        }
    }
}
