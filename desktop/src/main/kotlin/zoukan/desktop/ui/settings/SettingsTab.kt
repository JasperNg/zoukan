package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.TooltipIconButton
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File

/** What a backup or restore started from Settings is doing, shown in place of its buttons. */
internal val backupStatusFlow = MutableStateFlow<String?>(null)

@Composable
fun SettingsTab() {
    val preferences = remember { Injekt.get<DesktopPreferences>() }
    val getCategories = remember { Injekt.get<GetCategories>() }
    val categories by getCategories.subscribe().collectAsState(emptyList())
    val navigator = LocalNavigator.currentOrThrow
    val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
    val securityPreferences = remember { Injekt.get<SecurityPreferences>() }
    val cbzPassword = remember { Injekt.get<CbzPassword>() }
    var passwordDialog by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(cbzPassword.isSet()) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", showBack = false)
        LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp)) {
            // ZOUKAN -->
            appearanceSettings()
            // ZOUKAN <--

            settingsGroup("Reader")
            item {
                ChoicePreference(
                    "Default reading mode",
                    preferences.defaultReadingMode(),
                    ReadingModes.all.map { it to ReadingModes.label(it) },
                )
            }
            item { ChoicePreference("Page scaling (paged modes)", preferences.readerFitMode(), FitModes.all.map { it to FitModes.label(it) }) }
            item {
                ChoicePreference("Background", preferences.readerTheme(), listOf(0 to "White", 1 to "Black", 2 to "Gray"))
            }
            item { SliderPreference("Webtoon side padding", preferences.webtoonSidePadding(), 0..25) { "$it%" } }
            item { SwitchPreference("Show page number", preferences.showPageNumber()) }

            settingsGroup("Library")
            // Android's "Edit categories": the number of categories, and the screen that edits them
            item {
                val count = categories.count { !it.isSystemCategory }
                SettingRow(
                    "Edit categories",
                    if (count == 1) "1 category" else "$count categories",
                    onClick = { navigator.push(CategoryScreen) },
                )
            }

            // ZOUKAN -->
            librarySettings(categories)
            // ZOUKAN <--

            settingsGroup("Downloads")
            // ZOUKAN: the storage location and its folders are in Data and storage, as on Android
            item { SwitchPreference("Save as CBZ archive", downloadPreferences.saveChaptersAsCBZ()) }
            item { SwitchPreference("Split tall images", downloadPreferences.splitTallImages(), "Improves reader performance") }
            item { SliderPreference("Concurrent source downloads", downloadPreferences.parallelSourceLimit(), 1..10) { "$it" } }
            item { SliderPreference("Concurrent page downloads", downloadPreferences.parallelPageLimit(), 1..15) { "$it" } }
            // ZOUKAN: "Include chapter URL hash" is in Advanced, as on Android
            item {
                SwitchPreference(
                    "Password-protect downloads",
                    securityPreferences.passwordProtectDownloads(),
                    if (hasPassword) "New CBZ downloads are encrypted with your password" else "Set a password first",
                )
            }
            item {
                SettingRow(
                    if (hasPassword) "Change the download password" else "Set the download password",
                    "Kept in ${Injekt.get<zoukan.desktop.secrets.SecretStore>().name}. Use the same password as on Android to read the same archives.",
                    onClick = { passwordDialog = true },
                )
            }
            item {
                ChoicePreference(
                    "Encryption",
                    securityPreferences.encryptionType(),
                    listOf(
                        SecurityPreferences.EncryptionType.AES_256 to "AES-256",
                        SecurityPreferences.EncryptionType.AES_128 to "AES-128",
                        SecurityPreferences.EncryptionType.ZIP_STANDARD to "Standard zip encryption",
                    ),
                )
            }

            // ZOUKAN -->
            downloadBehaviorSettings(categories)
            trackingSettings()
            browseSettings()
            ehSettings()
            mangaDexSettings()
            dataSaverSettings()
            dataSettings()
            syncSettings()
            securitySettings()
            discordSettings()
            advancedSettings()
            shortcutSettings()
            // ZOUKAN <--

            settingsGroup("General")
            item {
                SwitchPreference(
                    "Keep running in the tray",
                    preferences.closeToTray(),
                    "Closing the window keeps Zoukan in the system tray, so library updates, downloads and backups go on",
                )
            }

            // ZOUKAN -->
            aboutSettings()
            // ZOUKAN <--
        }
    }

    if (passwordDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordDialog = false },
            title = { Text("Download password") },
            text = {
                OutlinedTextField(
                    password,
                    { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = {
                    cbzPassword.set(password)
                    hasPassword = true
                    passwordDialog = false
                    Toasts.show("Download password saved")
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (hasPassword) {
                        TextButton(onClick = {
                            cbzPassword.set("")
                            securityPreferences.passwordProtectDownloads().set(false)
                            hasPassword = false
                            passwordDialog = false
                        }) { Text("Remove") }
                    }
                    TextButton(onClick = { passwordDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
}

internal fun createBackup(folder: File) {
    if (backupStatusFlow.value != null) return
    backupStatusFlow.value = "Backing up…"
    AppScope.launch {
        try {
            val file = File(folder.apply { mkdirs() }, DesktopBackups.defaultFileName())
            val backup = DesktopBackups(ZoukanRuntime.dirs.android).create(file, BackupOptions())
            Toasts.show("Backed up ${backup.backupManga.size} entries to ${file.name}")
        } catch (e: Exception) {
            Toasts.error("Backup failed", e)
        } finally {
            backupStatusFlow.value = null
        }
    }
}

internal fun restoreBackup(file: File) {
    if (backupStatusFlow.value != null) return
    backupStatusFlow.value = "Restoring ${file.name}…"
    AppScope.launch {
        try {
            val backups = DesktopBackups(ZoukanRuntime.dirs.android)
            val backup = backups.read(file)
            // Extension stores decide whose code runs, so a backup adds them only when the user
            // has seen which ones
            val newStores = backups.newExtensionStores(backup)
            val options = when {
                newStores.isEmpty() -> RestoreOptions()
                else -> when (confirmStores(newStores.map { "${it.name}: ${it.indexUrl}" })) {
                    true -> RestoreOptions()
                    false -> RestoreOptions(extensionStores = false)
                    null -> return@launch
                }
            }
            val errors = backups.restore(backup, options) { _, progress, total ->
                backupStatusFlow.value = "Restoring ${file.name}: $progress of $total"
            }
            Toasts.show(
                "Restored ${backup.backupManga.size} entries" + if (errors.isNotEmpty()) " with ${errors.size} errors" else "",
            )
        } catch (e: Exception) {
            Toasts.error("Restore failed", e)
        } finally {
            backupStatusFlow.value = null
        }
    }
}

/**
 * Asks whether a restore may add the extension stores a backup lists. True adds them, false
 * restores without them, null cancels the restore.
 */
private fun confirmStores(stores: List<String>): Boolean? {
    val shown = stores.take(8).map { it.take(140) } + listOfNotNull("and ${stores.size - 8} more".takeIf { stores.size > 8 })
    val message = "This backup adds ${if (stores.size == 1) "an extension store" else "${stores.size} extension stores"}:\n\n" +
        shown.joinToString("\n") + "\n\nExtensions run with full access to this computer. " +
        "Add only stores you trust.\n\nAdd ${if (stores.size == 1) "it" else "them"}?"
    var choice = javax.swing.JOptionPane.CANCEL_OPTION
    val ask = Runnable {
        choice = javax.swing.JOptionPane.showOptionDialog(
            null,
            message,
            "Restore backup",
            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf("Add", "Restore without", "Cancel"),
            "Restore without",
        )
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) ask.run() else javax.swing.SwingUtilities.invokeAndWait(ask)
    return when (choice) {
        0 -> true
        1 -> false
        else -> null
    }
}

/** A folder picker: the native one on macOS, Swing's on Windows (AWT's FileDialog picks only files there). */
internal fun chooseFolder(window: java.awt.Window?, start: File): File? {
    if (zoukan.desktop.Platform.current == zoukan.desktop.Platform.MACOS) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(window as java.awt.Frame?, "Choose the storage folder", FileDialog.LOAD).apply {
                directory = start.absolutePath
                isVisible = true
            }
            return dialog.file?.let { File(dialog.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = javax.swing.JFileChooser(start).apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose the storage folder"
    }
    return if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun openFolder(folder: File) {
    AppScope.launch {
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            runCatching { Desktop.getDesktop().open(folder) }.onFailure { Toasts.error("Could not open the folder", it) }
        }
    }
}
