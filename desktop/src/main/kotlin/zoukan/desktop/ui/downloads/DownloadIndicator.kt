package zoukan.desktop.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import zoukan.desktop.download.Download
import zoukan.desktop.ui.common.Tooltip

/**
 * A chapter's download button: download, queued or downloading (with progress; click to cancel),
 * failed (click to retry), or downloaded (click to delete).
 */
@Composable
fun DownloadIndicator(
    downloaded: Boolean,
    download: Download?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        val state = download?.state?.collectAsState()?.value
        val progress = download?.progress?.collectAsState()?.value
        // What a click does, since the icon alone only shows the state
        val label = when {
            downloaded && download == null -> "Downloaded"
            state == Download.State.ERROR -> "Download failed"
            state == Download.State.DOWNLOADING -> "Downloading"
            download != null -> "Queued"
            else -> "Download"
        }
        Tooltip(label) {
            when {
                downloaded && download == null -> IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chapter-downloaded")) {
                    Icon(Icons.Default.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                }
                state == Download.State.ERROR -> IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.ErrorOutline, "Download failed: ${download.error.orEmpty()}", tint = MaterialTheme.colorScheme.error)
                }
                download != null -> IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chapter-downloading")) {
                    Box(contentAlignment = Alignment.Center) {
                        val (done, total) = progress ?: (0 to 0)
                        if (state == Download.State.DOWNLOADING && total > 0) {
                            CircularProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                else -> IconButton(onClick = onDownload, modifier = Modifier.testTag("chapter-download")) {
                    Icon(Icons.Outlined.ArrowDownward, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (state == Download.State.ERROR) {
                download.error?.let { DropdownMenuItem(text = { Text(it, color = MaterialTheme.colorScheme.error) }, onClick = {}, enabled = false) }
                DropdownMenuItem(text = { Text("Retry") }, onClick = {
                    menu = false
                    onCancel()
                    onDownload()
                })
            }
            if (download != null) {
                DropdownMenuItem(text = { Text("Cancel download") }, onClick = {
                    menu = false
                    onCancel()
                })
            } else if (downloaded) {
                DropdownMenuItem(text = { Text("Delete download") }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}
