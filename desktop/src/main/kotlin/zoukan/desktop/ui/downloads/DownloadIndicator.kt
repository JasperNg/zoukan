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
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.download.Download
import zoukan.desktop.ui.common.Tooltip
import zoukan.i18n.desktop.DMR

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
            downloaded && download == null -> localized(MR.strings.label_downloaded)
            state == Download.State.ERROR -> localized(DMR.strings.download_failed)
            state == Download.State.DOWNLOADING -> localized(MR.strings.ext_downloading)
            download != null -> localized(DMR.strings.queued)
            else -> localized(MR.strings.action_download)
        }
        Tooltip(label) {
            when {
                downloaded && download == null -> IconButton(onClick = { menu = true }, modifier = Modifier.testTag("chapter-downloaded")) {
                    Icon(Icons.Default.CheckCircle, localized(MR.strings.label_downloaded), tint = MaterialTheme.colorScheme.primary)
                }
                state == Download.State.ERROR -> IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.ErrorOutline, localized(DMR.strings.download_failed_2, download.error.orEmpty()), tint = MaterialTheme.colorScheme.error)
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
                    Icon(Icons.Outlined.ArrowDownward, localized(MR.strings.action_download), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (state == Download.State.ERROR) {
                download.error?.let { DropdownMenuItem(text = { Text(it, color = MaterialTheme.colorScheme.error) }, onClick = {}, enabled = false) }
                DropdownMenuItem(text = { Text(localized(MR.strings.action_retry)) }, onClick = {
                    menu = false
                    onCancel()
                    onDownload()
                })
            }
            if (download != null) {
                DropdownMenuItem(text = { Text(localized(DMR.strings.cancel_download)) }, onClick = {
                    menu = false
                    onCancel()
                })
            } else if (downloaded) {
                DropdownMenuItem(text = { Text(localized(DMR.strings.delete_download)) }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}
