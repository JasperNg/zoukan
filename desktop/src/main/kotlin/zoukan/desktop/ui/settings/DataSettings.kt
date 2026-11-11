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
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.compat.localized
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.Archives
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR
import java.awt.FileDialog
import java.io.File

/**
 * Settings > Data and storage, as the Android app's SettingsDataScreen: the storage location,
 * backup and restore, storage usage and the chapter cache, and the library list export. Sync is
 * in its own group (SyncSettings), as on Android it follows these.
 */
fun LazyListScope.dataSettings() {
    settingsGroup(localized(MR.strings.label_data_storage))
    item(key = "data-storage-location") { StorageLocationPreference() }
    item(key = "data-storage-info") { InfoText(localized(MR.strings.pref_storage_location_info)) }
    item(key = "data-open-downloads") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow(localized(DMR.strings.open_the_downloads_folder), onClick = { openFolder(storage.downloads) })
    }
    item(key = "data-open-local") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow(
            localized(DMR.strings.open_the_local_source_folder),
            localized(DMR.strings.put_a_folder_per_entry_here),
            onClick = { openFolder(storage.local) },
        )
    }

    settingsGroup(localized(MR.strings.label_backup))
    item(key = "data-backup-buttons") { BackupButtons() }
    item(key = "data-backup-interval") {
        val preferences = remember { Injekt.get<BackupPreferences>() }
        ChoicePreference(
            localized(MR.strings.pref_backup_interval),
            preferences.backupInterval(),
            listOf(
                0 to localized(MR.strings.off),
                1 to localized(SYMR.strings.update_1hour),
                3 to localized(MR.strings.update_3hour),
                6 to localized(MR.strings.update_6hour),
                12 to localized(MR.strings.update_12hour),
                24 to localized(MR.strings.update_24hour),
                48 to localized(MR.strings.update_48hour),
                168 to localized(MR.strings.update_weekly),
            ),
        )
    }
    item(key = "data-backup-info") {
        val preferences = remember { Injekt.get<BackupPreferences>() }
        val last by preferences.lastAutoBackupTimestamp().changes().collectAsState(preferences.lastAutoBackupTimestamp().get())
        InfoText(
            localized(
                DMR.strings.backup_info,
                if (last > 0) zoukan.desktop.ui.manga.formatDateTime(last) else localized(MR.strings.lock_never),
            ),
        )
    }
    item(key = "data-backup-folder") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        SettingRow(localized(DMR.strings.open_the_backup_folder), onClick = { openFolder(dirs.backups) })
    }
    item(key = "data-auto-backup-folder") {
        val storage = remember { Injekt.get<ZoukanStorage>() }
        SettingRow(
            localized(DMR.strings.open_the_automatic_backup_folder),
            localized(DMR.strings.the_newest_are_kept_they_are, AutoBackup.MAX_AUTO_BACKUPS),
            onClick = { openFolder(storage.autoBackups) },
        )
    }

    settingsGroup(localized(MR.strings.pref_storage_usage))
    item(key = "data-storage-usage") { StorageUsage() }
    item(key = "data-chapter-cache") { ChapterCachePreference() }
    item(key = "data-auto-clear-cache") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(localized(MR.strings.pref_auto_clear_chapter_cache), preferences.autoClearChapterCache())
    }
    item(key = "data-cover-cache") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        val scope = rememberCoroutineScope()
        SettingRow(localized(DMR.strings.clear_cover_cache), localized(DMR.strings.covers_download_again_when_shown), onClick = {
            scope.launch(Dispatchers.IO) {
                dirs.covers.listFiles()?.forEach { it.delete() }
                Toasts.show(localized(DMR.strings.cover_cache_cleared))
            }
        })
    }
    item(key = "data-folder") {
        val dirs = remember { Injekt.get<ZoukanDirs>() }
        SettingRow(localized(DMR.strings.data_folder), dirs.root.absolutePath, onClick = { openFolder(dirs.root) })
    }

    settingsGroup(localized(MR.strings.export))
    item(key = "data-export") { ExportLibraryPreference() }
}

/** Android's "Storage location": the folder for downloads, the local source and automatic backups. */
@Composable
internal fun StorageLocationPreference() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val window = LocalWindow.current?.window
    val folder by storage.folderPreference.changes().collectAsState(storage.folderPreference.get())
    SettingRow(
        localized(MR.strings.pref_storage_location),
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
            }) { Text(localized(MR.strings.action_reset)) }
        }
    }
}

/** Android's Create backup and Restore backup buttons, side by side. */
@Composable
private fun BackupButtons() {
    val dirs = remember { Injekt.get<ZoukanDirs>() }
    val window = LocalWindow.current?.window
    val status by backupStatusFlow.collectAsState()
    SearchTerms(localized(MR.strings.pref_create_backup), localized(MR.strings.pref_restore_backup))
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { createBackup(dirs.backups) }, enabled = status == null, modifier = Modifier.weight(1f)) {
                Text(localized(MR.strings.pref_create_backup))
            }
            OutlinedButton(
                onClick = { pickAndRestoreBackup(window, dirs) },
                enabled = status == null,
                modifier = Modifier.weight(1f),
            ) { Text(localized(MR.strings.pref_restore_backup)) }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status != null) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                status ?: localized(DMR.strings.backs_up_the_library_categories_history, dirs.backups),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Asks for a backup file and restores it; returns whether one was chosen. The first-run setup uses it too. */
internal fun pickAndRestoreBackup(window: java.awt.Window?, dirs: ZoukanDirs = Injekt.get()): Boolean {
    val dialog = FileDialog(window as java.awt.Frame?, localized(MR.strings.file_select_backup), FileDialog.LOAD).apply {
        directory = dirs.backups.absolutePath
        setFilenameFilter { _, name -> name.endsWith(".tachibk") || name.endsWith(".proto.gz") }
        isVisible = true
    }
    val name = dialog.file ?: return false
    restoreBackup(File(dialog.directory, name))
    return true
}

/** Android's StorageInfo: the storage location's disk, used and free. */
@Composable
private fun StorageUsage() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val folder by storage.folderPreference.changes().collectAsState(storage.folderPreference.get())
    val root = remember(folder) { storage.root.also { it.mkdirs() } }
    val total = remember(root) { root.totalSpace }
    val available = remember(root) { root.usableSpace }
    SearchTerms(localized(MR.strings.pref_storage_usage), localized(DMR.strings.disk_space))
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(root.absolutePath, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { if (total > 0) 1 - available / total.toFloat() else 0f },
            modifier = Modifier.clip(MaterialTheme.shapes.small).fillMaxWidth().height(12.dp),
        )
        Text(
            localized(MR.strings.available_disk_space_info, formatSize(available), formatSize(total)),
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
    SettingRow(localized(MR.strings.pref_clear_chapter_cache), localized(MR.strings.used_cache, formatSize(size)), onClick = {
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { Archives.clearCache() }
                Toasts.show(localized(DMR.strings.cache_cleared_files_deleted, deleted))
            } catch (e: Exception) {
                Toasts.error(localized(MR.strings.cache_delete_error), e)
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
    SettingRow(localized(MR.strings.library_list), onClick = { dialog = true })
    if (!dialog) return
    var title by remember { mutableStateOf(true) }
    var author by remember { mutableStateOf(true) }
    var artist by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text(localized(MR.strings.migration_dialog_what_to_include)) },
        text = {
            Column {
                CheckRow(localized(MR.strings.title), title) { title = it }
                CheckRow(localized(MR.strings.author), author, enabled = title) { author = it }
                CheckRow(localized(MR.strings.artist), artist, enabled = title) { artist = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dialog = false
                val chooser = FileDialog(window as java.awt.Frame?, localized(DMR.strings.export_library), FileDialog.SAVE).apply {
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
                        Toasts.show(localized(MR.strings.library_exported))
                    } catch (e: Exception) {
                        Toasts.error(localized(DMR.strings.could_not_export_the_library), e)
                    }
                }
            }) { Text(localized(MR.strings.action_save)) }
        },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text(localized(MR.strings.action_cancel)) } },
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
