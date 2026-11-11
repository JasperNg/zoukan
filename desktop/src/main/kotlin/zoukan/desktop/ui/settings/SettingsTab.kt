package zoukan.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import exh.md.utils.MdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.AppVersion
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Toasts
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File

/** What a backup or restore started from Settings is doing, shown in place of its buttons. */
internal val backupStatusFlow = MutableStateFlow<String?>(null)

/**
 * The settings pages, in the order and with the names of the Android app's settings list. Desktop
 * extras go on the page they belong to: the tray and keyboard shortcuts in Advanced, Discord in
 * Connections, data sync and data saver in Data and storage.
 */
enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    Appearance("Appearance", "Theme, date & time format", Icons.Outlined.Palette),
    Library("Library", "Categories, global update", Icons.Outlined.CollectionsBookmark),
    Reader("Reader", "Reading mode, display, navigation", Icons.AutoMirrored.Outlined.ChromeReaderMode),
    Downloads("Downloads", "Automatic download, download ahead", Icons.Outlined.GetApp),
    Tracking("Tracking", "One-way progress sync, enhanced sync", Icons.Outlined.Sync),
    Connections("Connections", "Discord", Icons.Outlined.Link),
    Browse("Browse", "Sources, extensions, global search", Icons.Outlined.Explore),
    Data("Data and storage", "Manual & automatic backups, storage space", Icons.Outlined.Storage),
    Security("Security and privacy", "App lock, secure screen", Icons.Outlined.Security),
    EHentai("E-Hentai", "E-Hentai settings", Icons.Outlined.PhotoLibrary),
    MangaDex("MangaDex", "MangaDex settings", Icons.AutoMirrored.Outlined.MenuBook),
    Advanced("Advanced", "Tray, keyboard shortcuts, crash logs", Icons.Outlined.Code),
    About("About", "Zoukan ${AppVersion.name}", Icons.Outlined.Info),
}

/** The open settings page, kept while other tabs are open, as the Android app's settings navigator. */
private var selectedCategory by mutableStateOf(SettingsCategory.Appearance)

/** Opens Settings on [category] the next time the Settings tab shows. */
fun openSettingsCategory(category: SettingsCategory) {
    selectedCategory = category
}

/**
 * Settings, laid out as the Android app's tablet settings: the list of pages on the left (half the
 * width, at most 450dp) and the open page on the right.
 */
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
    val mangaDexInstalled = remember { MdUtil.getEnabledMangaDexs(Injekt.get()).isNotEmpty() }
    val pages = remember(mangaDexInstalled) {
        SettingsCategory.entries.filter { it != SettingsCategory.MangaDex || mangaDexInstalled }
    }
    val selected = selectedCategory.takeIf { it in pages } ?: SettingsCategory.Appearance

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listWidth = (maxWidth / 2).coerceAtMost(450.dp)
        Row(Modifier.fillMaxSize()) {
            Surface(color = palerSurface(), modifier = Modifier.width(listWidth).fillMaxHeight()) {
                SettingsCategoryList(pages, selected) { selectedCategory = it }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ScreenHeader(selected.title, showBack = false)
                // A new list for each page, so each page opens at its top
                key(selected) {
                    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp)) {
                        when (selected) {
                            SettingsCategory.Appearance -> appearanceSettings()
                            SettingsCategory.Library -> {
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
                                librarySettings(categories)
                            }
                            SettingsCategory.Reader -> readerSettings(preferences)
                            SettingsCategory.Downloads -> {
                                downloadSettings(downloadPreferences, securityPreferences, hasPassword) { passwordDialog = true }
                                downloadBehaviorSettings(categories)
                            }
                            SettingsCategory.Tracking -> trackingSettings()
                            SettingsCategory.Connections -> discordSettings()
                            SettingsCategory.Browse -> browseSettings()
                            SettingsCategory.Data -> {
                                dataSettings()
                                syncSettings()
                                dataSaverSettings()
                            }
                            SettingsCategory.Security -> securitySettings()
                            SettingsCategory.EHentai -> ehSettings()
                            SettingsCategory.MangaDex -> mangaDexSettings()
                            SettingsCategory.Advanced -> {
                                settingsGroup("General")
                                item {
                                    SwitchPreference(
                                        "Keep running in the tray",
                                        preferences.closeToTray(),
                                        "Closing the window keeps Zoukan in the system tray, so library updates, downloads and backups go on",
                                    )
                                }
                                shortcutSettings()
                                advancedSettings()
                            }
                            SettingsCategory.About -> aboutSettings()
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
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

/** The settings list on the left: one row per page, the open page highlighted, as on Android. */
@Composable
private fun SettingsCategoryList(pages: List<SettingsCategory>, selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // A back button when More opened Settings as its own screen
        ScreenHeader("Settings", color = Color.Transparent)
        LazyColumn(Modifier.fillMaxSize()) {
            items(pages, key = { it.name }) { page ->
                val isSelected = page == selected
                val contentColor = if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                            .clickable { onSelect(page) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, end = 24.dp).size(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(page.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), maxLines = 2)
                            Text(
                                page.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The background of the settings list: the surface made a little darker in dark themes and a
 * little lighter in light ones, as the Android app's tablet settings list.
 */
@Composable
private fun palerSurface(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) { palerSurface(surface) }
}

internal fun palerSurface(surface: Color): Color {
    val hsl = FloatArray(3)
    val r = surface.red
    val g = surface.green
    val b = surface.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val d = max - min
    val saturation = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d).mod(6f))
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    hsl[0] = hue
    hsl[1] = saturation.coerceIn(0f, 1f)
    // Android tells dark from light by the system setting; the theme's own surface is the better guide here
    hsl[2] = (if (lightness < 0.5f) lightness - 0.05f else lightness + 0.02f).coerceIn(0f, 1f)
    return Color.hsl(hsl[0], hsl[1], hsl[2], surface.alpha)
}

/** Settings > Reader. */
private fun LazyListScope.readerSettings(preferences: DesktopPreferences) {
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
    // ZOUKAN -->
    readerBehaviourSettings(preferences)
    // ZOUKAN <--
}

/** Settings > Downloads: the format, concurrency and download password. */
private fun LazyListScope.downloadSettings(
    downloadPreferences: DownloadPreferences,
    securityPreferences: SecurityPreferences,
    hasPassword: Boolean,
    onEditPassword: () -> Unit,
) {
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
            onClick = onEditPassword,
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
