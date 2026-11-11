package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts

/** Set while a clean-up runs, so a second one waits, as Android's single job. */
private val cleanupRunning = mutableStateOf(false)

/**
 * SY's "Clean up downloaded chapters" (Settings > Advanced): deletes orphaned and unfinished
 * downloads, and optionally read chapters and entries no longer in the library.
 */
@Composable
fun CleanupDownloadsPreference() {
    var open by remember { mutableStateOf(false) }
    val running by cleanupRunning
    SettingRow(
        "Clean up downloaded chapters",
        "Delete non-existent, partially downloaded, and read chapter folders",
        enabled = !running,
        onClick = { open = true },
    ) { if (running) CircularProgressIndicator(Modifier.size(24.dp)) }
    if (open) {
        CleanupDownloadsDialog(onDismiss = { open = false }) { removeRead, removeNonFavorite ->
            open = false
            runCleanup(removeRead, removeNonFavorite)
        }
    }
}

private fun runCleanup(removeRead: Boolean, removeNonFavorite: Boolean) {
    if (cleanupRunning.value) return
    cleanupRunning.value = true
    Toasts.show("Starting cleanup")
    AppScope.launch {
        try {
            val cleared = Injekt.get<DownloadManager>().cleanup(
                removeRead,
                removeNonFavorite,
                allManga = { Injekt.get<MangaRepository>().getAll() },
                chaptersOf = { Injekt.get<GetChaptersByMangaId>().await(it) },
            )
            Toasts.show(
                when (cleared) {
                    0 -> "No folders to cleanup"
                    1 -> "Cleanup done. Removed 1 folder"
                    else -> "Cleanup done. Removed $cleared folders"
                },
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toasts.error("Cleaning up downloads failed", e)
        } finally {
            cleanupRunning.value = false
        }
    }
}

@Composable
private fun CleanupDownloadsDialog(onDismiss: () -> Unit, onConfirm: (removeRead: Boolean, removeNonFavorite: Boolean) -> Unit) {
    var removeRead by remember { mutableStateOf(true) }
    var removeNonFavorite by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean up downloaded chapters") },
        text = {
            Column {
                // Always on, as on Android
                CheckRow("Clean orphaned", checked = true, enabled = false) {}
                CheckRow("Clean read", removeRead) { removeRead = it }
                CheckRow("Clean entries not in library", removeNonFavorite) { removeNonFavorite = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(removeRead, removeNonFavorite) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}
