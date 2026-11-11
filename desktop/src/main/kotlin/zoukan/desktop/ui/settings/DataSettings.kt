package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.Archives
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Toasts
import java.awt.FileDialog
import java.io.File

/**
 * Settings > Data and storage, as the Android app's SettingsDataScreen: the storage location,
 * backup and restore, storage usage and the chapter cache, and the library list export. Sync is
 * in its own group (SyncSettings), as on Android it follows these.
 */
fun LazyListScope.dataSettings() {
    settingsGroup("Data and storage")
    item(key = "data-storage-location") { StorageLocationPreference() }
    item(key = "data-storage-info") { InfoText("Used for automatic backups, chapter downloads, and local source.") }
    item(key = "data-open-downloads") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow("Open the downloads folder", onClick = { openFolder(storage.downloads) })
    }
    item(key = "data-open-local") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow(
            "Open the local source folder",
            "Put a folder per entry here, with chapters as folders, CBZ, ZIP, RAR, 7z, TAR or EPUB files",
            onClick = { openFolder(storage.local) },
        )
    }

    settingsGroup("Backup and restore")
    item(key = "data-backup-buttons") { BackupButtons() }
    item(key = "data-backup-interval") {
        val preferences = remember { Injekt.get<BackupPreferences>() }
        ChoicePreference(
            "Automatic backup frequency",
            preferences.backupInterval(),
            listOf(
                0 to "Off",
                1 to "Every hour",
                3 to "Every 3 hours",
                6 to "Every 6 hours",
                12 to "Every 12 hours",
                24 to "Daily",
                48 to "Every 2 days",
                168 to "Weekly",
            ),
        )
    }
    item(key = "data-backup-info") {
        val preferences = remember { Injekt.get<BackupPreferences>() }
        val last by preferences.lastAutoBackupTimestamp().changes().collectAsState(preferences.lastAutoBackupTimestamp().get())
        InfoText(
            "You should keep copies of backups in other places as well. Backups may contain sensitive data including any " +
                "stored passwords; be careful if sharing.\n\nLast automatically backed up: " +
                if (last > 0) zoukan.desktop.ui.manga.formatDateTime(last) else "Never",
        )
    }
    item(key = "data-backup-folder") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        SettingRow("Open the backup folder", onClick = { openFolder(dirs.backups) })
    }
    item(key = "data-auto-backup-folder") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow(
            "Open the automatic backup folder",
            "The newest ${AutoBackup.MAX_AUTO_BACKUPS} are kept. They are not encrypted, so keep the folder private.",
            onClick = { openFolder(storage.autoBackups) },
        )
    }

    settingsGroup("Storage usage")
    item(key = "data-storage-usage") { StorageUsage() }
    item(key = "data-chapter-cache") { ChapterCachePreference() }
    item(key = "data-auto-clear-cache") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference("Clear chapter cache on app launch", preferences.autoClearChapterCache())
    }
    item(key = "data-cover-cache") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        val scope = rememberCoroutineScope()
        SettingRow("Clear cover cache", "Covers download again when shown", onClick = {
            scope.launch(Dispatchers.IO) {
                dirs.covers.listFiles()?.forEach { it.delete() }
                Toasts.show("Cover cache cleared")
            }
        })
    }
    item(key = "data-folder") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        SettingRow("Data folder", dirs.root.absolutePath, onClick = { openFolder(dirs.root) })
    }

    settingsGroup("Export")
    item(key = "data-export") { ExportLibraryPreference() }
}

/** Android's "Storage location": the folder for downloads, the local source and automatic backups. */
@Composable
private fun StorageLocationPreference() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val window = LocalWindow.current?.window
    val folder by storage.folderPreference.changes().collectAsState(storage.folderPreference.get())
    SettingRow(
        "Storage location",
        // Read again when the setting changes
        remember(folder) { storage.root.path },
        onClick = {
            chooseFolder(window, storage.root)?.let {
                storage.folderPreference.set(it.path)
                Injekt.get<DownloadManager>().invalidateCache()
            }
        },
    ) {
        if (folder.isNotBlank()) {
            TextButton(onClick = {
                storage.folderPreference.set("")
                Injekt.get<DownloadManager>().invalidateCache()
            }) { Text("Reset") }
        }
    }
}

/** Android's Create backup and Restore backup buttons, side by side. */
@Composable
private fun BackupButtons() {
    val dirs = remember { Injekt.get<ZoukanDirs>() }
    val window = LocalWindow.current?.window
    val status by backupStatusFlow.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { createBackup(dirs.backups) }, enabled = status == null, modifier = Modifier.weight(1f)) {
                Text("Create backup")
            }
            OutlinedButton(
                onClick = {
                    val dialog = FileDialog(window as java.awt.Frame?, "Select backup file", FileDialog.LOAD).apply {
                        directory = dirs.backups.absolutePath
                        setFilenameFilter { _, name -> name.endsWith(".tachibk") || name.endsWith(".proto.gz") }
                        isVisible = true
                    }
                    val name = dialog.file
                    if (name != null) restoreBackup(File(dialog.directory, name))
                },
                enabled = status == null,
                modifier = Modifier.weight(1f),
            ) { Text("Restore backup") }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status != null) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                status ?: "Backs up the library, categories, history, tracking and settings to ${dirs.backups}. " +
                    "Restores Komikku, Mihon, TachiyomiSY and Zoukan backups (.tachibk).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Android's StorageInfo: the storage location's disk, used and free. */
@Composable
private fun StorageUsage() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val folder by storage.folderPreference.changes().collectAsState(storage.folderPreference.get())
    val root = remember(folder) { storage.root.also { it.mkdirs() } }
    val total = remember(root) { root.totalSpace }
    val available = remember(root) { root.usableSpace }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(root.absolutePath, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { if (total > 0) 1 - available / total.toFloat() else 0f },
            modifier = Modifier.clip(MaterialTheme.shapes.small).fillMaxWidth().height(12.dp),
        )
        Text(
            "Available: ${formatSize(available)} / Total: ${formatSize(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Android's "Clear chapter cache". The desktop reader keeps online pages in memory, so its chapter
 * cache on disk is the unpacked RAR, 7z and TAR chapters.
 */
@Composable
private fun ChapterCachePreference() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val size by produceState(0L, version) { value = withContext(Dispatchers.IO) { Archives.cacheSize() } }
    SettingRow("Clear chapter cache", "Used: ${formatSize(size)}", onClick = {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { Archives.clearCache() }
                Toasts.show("Cache cleared, $deleted files deleted")
            } catch (e: Exception) {
                Toasts.error("Error occurred while clearing", e)
            }
            version++
        }
    })
}

/** Android's Export > Library List: the library's titles, authors and artists as CSV. */
@Composable
private fun ExportLibraryPreference() {
    val window = LocalWindow.current?.window
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    SettingRow("Library List", onClick = { dialog = true })
    if (!dialog) return
    var title by remember { mutableStateOf(true) }
    var author by remember { mutableStateOf(true) }
    var artist by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("Select data to include") },
        text = {
            Column {
                CheckRow("Title", title) { title = it }
                CheckRow("Author", author, enabled = title) { author = it }
                CheckRow("Artist", artist, enabled = title) { artist = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dialog = false
                val chooser = FileDialog(window as java.awt.Frame?, "Export library", FileDialog.SAVE).apply {
                    file = "komikku_library.csv"
                    isVisible = true
                }
                val name = chooser.file ?: return@TextButton
                val target = File(chooser.directory, name)
                val options = LibraryCsv.Options(title, title && author, title && artist)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            target.writeText(LibraryCsv.generate(Injekt.get<GetFavorites>().await(), options))
                        }
                        Toasts.show("Library Exported")
                    } catch (e: Exception) {
                        Toasts.error("Could not export the library", e)
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Android LibraryExporter's CSV: the chosen columns, quoted where needed, CRLF between rows. */
object LibraryCsv {
    data class Options(val title: Boolean, val author: Boolean, val artist: Boolean)

    private val escapeRequired = listOf("\r", "\n", "\"", ",")

    fun generate(favorites: List<Manga>, options: Options): String = favorites.joinToString("\r\n") { manga ->
        buildList {
            if (options.title) add(manga.title)
            if (options.author) add(manga.author)
            if (options.artist) add(manga.artist)
        }.joinToString(",") { column ->
            when {
                column.isNullOrBlank() -> ""
                escapeRequired.any { column.contains(it) } -> "\"${column.replace("\"", "\"\"")}\""
                else -> column
            }
        }
    }
}

/** Sizes as Android's Formatter.formatFileSize: 1000-based units, as Android 8 and later. */
fun formatSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB", "PB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.2f %s".format(value, units[unit])
}
