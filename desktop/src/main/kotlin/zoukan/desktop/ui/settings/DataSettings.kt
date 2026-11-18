package zoukan.desktop.ui.settings

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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
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
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.local.Archives
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.collectAsState
import zoukan.i18n.desktop.DMR
import java.io.File

/**
 * Settings > Data and storage, as the Android app's SettingsDataScreen: the storage location,
 * backup and restore, storage usage and the chapter cache, and the library list export. Sync is
 * in its own group (SyncSettings), as on Android it follows these.
 */
fun LazyListScope.dataSettings() {
    settingsGroup(localized(MR.strings.label_data_storage))
    item(key = "data-storage-location") { StorageLocationPreference() }
    item(key = "data-storage-unavailable") { StorageUnavailableBanner() }
    item(key = "data-storage-cloud-sync") { StorageCloudSyncWarning() }
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
    if (Platform.current == Platform.MACOS) {
        item(key = "data-storage-spotlight") {
            val storage = remember { Injekt.get<ZoukanStorage>() }
            SwitchPreference(
                localized(DMR.strings.hide_storage_folder_from_spotlight),
                storage.hideFromSpotlight,
                localized(DMR.strings.hide_storage_folder_from_spotlight_summary),
            )
        }
        item(key = "data-storage-time-machine") {
            val storage = remember { Injekt.get<ZoukanStorage>() }
            SwitchPreference(
                localized(DMR.strings.leave_storage_folder_out_of_time_machine),
                storage.excludeFromTimeMachine,
                localized(DMR.strings.leave_storage_folder_out_of_time_machine_summary),
            )
        }
    }

    settingsGroup(localized(MR.strings.label_backup))
    item(key = "data-backup-buttons") { BackupButtons() }
    item(key = "data-install-missing-extensions") {
        val navigator = LocalNavigator.currentOrThrow
        SettingRow(
            localized(DMR.strings.install_missing_extensions),
            localized(DMR.strings.install_missing_extensions_summary),
            onClick = { navigator.push(MissingExtensionsScreen) },
        )
    }
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
        val last by preferences.lastAutoBackupTimestamp().collectAsState()
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
                // Custom covers (covers/custom) are kept
                zoukan.desktop.manga.CustomCovers.clearCache(dirs.covers)
                // ZOUKAN: and the other images kept on disk (extension icons, tracker covers, ...)
                runCatching { Images.clearDiskCache() }
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
    val folder by storage.folderPreference.collectAsState()
    val scope = rememberCoroutineScope()
    // The folder asked for ("" for the data folder) and what is in it, waiting for the user's answer
    var pending by remember { mutableStateOf<Pair<String, StorageLocation.Verdict.Ok>?>(null) }
    SettingRow(
        localized(MR.strings.pref_storage_location),
        // Read again when the setting changes
        remember(folder) { storage.root.path },
        onClick = {
            chooseFolder(window, storage.root)?.let { picked ->
                scope.launch {
                    // The folder may be on a network drive
                    when (val verdict = withContext(Dispatchers.IO) { StorageLocation.check(picked) }) {
                        is StorageLocation.Verdict.Ok -> pending = picked.path to verdict
                        StorageLocation.Verdict.NotWritable -> Toasts.show(localized(DMR.strings.storage_folder_not_writable, picked.path))
                    }
                }
            }
        },
    ) {
        if (folder.isNotBlank()) {
            TextButton(onClick = {
                scope.launch {
                    val hasDownloads = withContext(Dispatchers.IO) { File(Injekt.get<ZoukanDirs>().root, ZoukanStorage.DOWNLOADS).listFiles()?.isNotEmpty() == true }
                    pending = "" to StorageLocation.Verdict.Ok(hasDownloads)
                }
            }) { Text(localized(MR.strings.action_reset)) }
        }
    }
    pending?.let { (path, verdict) ->
        val newRoot = if (path.isBlank()) Injekt.get<ZoukanDirs>().root else File(path)
        StorageChangeDialog(
            oldRoot = storage.root,
            newRoot = newRoot,
            hasDownloads = verdict.hasDownloads,
            cloudSync = remember(newRoot) { cloudSyncOf(newRoot) },
            onOpenOld = { openFolder(storage.root) },
            onConfirm = { StorageLocation.apply(storage, path) { Injekt.get<DownloadManager>().invalidateCache() } },
            onDismiss = { pending = null },
        )
    }
}

/**
 * Asked before the storage folder changes: nothing in the old folder is moved, so its downloads,
 * local entries and automatic backups stay there and show as not downloaded until the user moves
 * them or switches back. A button opens the old folder.
 */
@Composable
internal fun StorageChangeDialog(
    oldRoot: File,
    newRoot: File,
    hasDownloads: Boolean,
    cloudSync: StorageLocation.CloudSync? = null,
    onOpenOld: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.pref_storage_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(newRoot.path, style = MaterialTheme.typography.bodySmall)
                Text(localized(DMR.strings.storage_change_warning, oldRoot.path))
                if (hasDownloads) Text(localized(DMR.strings.storage_folder_has_downloads))
                cloudSync?.let { Text(cloudSyncMessage(it), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onOpenOld) { Text(localized(DMR.strings.open_the_old_folder)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Android's Create backup and Restore backup buttons, side by side. */
@Composable
private fun BackupButtons() {
    val dirs = remember { Injekt.get<ZoukanDirs>() }
    val window = LocalWindow.current?.window
    val status by backupStatusFlow.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    SearchTerms(localized(MR.strings.pref_create_backup), localized(MR.strings.pref_restore_backup))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { navigator.push(CreateBackupScreen) }, enabled = status == null, modifier = Modifier.weight(1f)) {
                Text(localized(MR.strings.pref_create_backup))
            }
            OutlinedButton(
                onClick = { pickBackupFile(window, dirs)?.let { navigator.push(RestoreBackupScreen(it.path)) } },
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

/**
 * Asks for a backup file to restore, as Android's restore picker; null when none was chosen. The
 * file then opens in [RestoreBackupScreen]. The first-run setup uses it too.
 */
internal fun pickBackupFile(window: java.awt.Window?, dirs: ZoukanDirs = Injekt.get()): File? {
    return SystemEffects.current.pickFile(
        FileRequest(window, localized(MR.strings.file_select_backup), save = false, directory = dirs.backups) { name ->
            name.endsWith(".tachibk") || name.endsWith(".proto.gz")
        },
    )
}

/**
 * Shown while the chosen storage folder is missing, for example on a drive that is not connected.
 * Downloads, automatic backups and the local source then save nothing until it is back or another
 * folder is chosen.
 */
@Composable
private fun StorageUnavailableBanner() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val folder by storage.folderPreference.collectAsState()
    // Checked when the page opens and when the folder changes. No polling loop: an endless delay
    // loop keeps Compose UI tests from going idle
    val available by produceState(true, folder) { value = withContext(Dispatchers.IO) { storage.isAvailable } }
    if (!available) {
        Text(
            localized(DMR.strings.storage_folder_unavailable, storage.root.path),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp),
        )
    }
}

/** On macOS, how the storage folder at [root] may be synced to the cloud; null elsewhere or when it is not. */
private fun cloudSyncOf(root: File): StorageLocation.CloudSync? =
    if (Platform.current == Platform.MACOS) StorageLocation.cloudSync(root, File(System.getProperty("user.home"))) else null

private fun cloudSyncMessage(sync: StorageLocation.CloudSync): String = when (sync) {
    StorageLocation.CloudSync.SYNCED -> localized(DMR.strings.storage_folder_is_synced)
    StorageLocation.CloudSync.MAYBE_SYNCED -> localized(DMR.strings.storage_folder_may_be_synced)
}

/**
 * Shown on macOS while the storage folder is in iCloud Drive or another synced folder, or in
 * Desktop or Documents, which iCloud may sync. A warning only: the folder stays in use.
 */
@Composable
private fun StorageCloudSyncWarning() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val folder by storage.folderPreference.collectAsState()
    val sync = remember(folder) { cloudSyncOf(storage.root) } ?: return
    Text(
        cloudSyncMessage(sync),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp),
    )
}

/** Android's StorageInfo: the storage location's disk, used and free. */
@Composable
private fun StorageUsage() {
    val storage = remember { Injekt.get<ZoukanStorage>() }
    val folder by storage.folderPreference.collectAsState()
    // Never created here: a chosen folder that is missing is a disconnected drive (StorageUnavailableBanner)
    val root = remember(folder) { storage.root }
    val total = remember(root) { root.totalSpace }
    val available = remember(root) { root.usableSpace }
    SearchTerms(localized(MR.strings.pref_storage_usage), localized(DMR.strings.disk_space))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.SettingsRowInset, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Toasts.show(localizedPlural(DMR.plurals.cache_cleared_files_deleted, deleted, deleted))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                val target = SystemEffects.current.pickFile(
                    FileRequest(window, localized(DMR.strings.export_library), save = true, fileName = "komikku_library.csv"),
                ) ?: return@TextButton
                val options = LibraryCsv.Options(title, title && author, title && artist)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            target.writeText(LibraryCsv.generate(Injekt.get<GetFavorites>().await(), options))
                        }
                        Toasts.show(localized(MR.strings.library_exported))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
        Modifier.fillMaxWidth().checkRow(checked, enabled, onChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowCheckbox(checked = checked, onCheckedChange = null, enabled = enabled)
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
