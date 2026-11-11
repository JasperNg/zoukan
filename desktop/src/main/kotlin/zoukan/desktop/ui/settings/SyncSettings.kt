package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.AppScope
import java.text.DateFormat
import java.util.Date

/** Settings > Data sync: SyncYomi or WebDAV, as on Android. Google Drive needs the app's OAuth secrets. */
fun LazyListScope.syncSettings() {
    settingsGroup("Data sync")
    item(key = "sync") { SyncSettingsSection() }
}

private val INTERVALS = listOf(
    0 to "Manually",
    30 to "Every 30 minutes",
    60 to "Every hour",
    180 to "Every 3 hours",
    360 to "Every 6 hours",
    720 to "Every 12 hours",
    1440 to "Every day",
    2880 to "Every 2 days",
    10080 to "Every week",
)

@Composable
private fun SyncSettingsSection() {
    val preferences = remember { Injekt.get<SyncPreferences>() }
    val manager = remember { Injekt.get<SyncManager>() }
    val service by preferences.syncService().changes().collectAsState(preferences.syncService().get())
    val running by manager.running.collectAsState()
    val last by preferences.lastSyncTimestamp().changes().collectAsState(preferences.lastSyncTimestamp().get())
    var edit by remember { mutableStateOf<Pair<String, Preference<String>>?>(null) }
    var secret by remember { mutableStateOf(false) }
    var whatDialog by remember { mutableStateOf(false) }
    var triggersDialog by remember { mutableStateOf(false) }

    Column {
        ChoicePreference(
            "Sync service",
            preferences.syncService(),
            listOf(
                SyncManager.SyncService.NONE.value to "Off",
                SyncManager.SyncService.SYNCYOMI.value to "SyncYomi",
                SyncManager.SyncService.WEB_DAV.value to "WebDAV",
            ),
        )
        when (SyncManager.SyncService.fromInt(service)) {
            SyncManager.SyncService.SYNCYOMI -> {
                TextRow("Host", preferences.clientHost()) {
                    edit = it
                    secret = false
                }
                TextRow("API key", preferences.clientAPIKey(), hidden = true) {
                    edit = it
                    secret = true
                }
            }
            SyncManager.SyncService.WEB_DAV -> {
                TextRow("Server URL", preferences.webDavUrl()) {
                    edit = it
                    secret = false
                }
                TextRow("Username", preferences.webDavUsername()) {
                    edit = it
                    secret = false
                }
                TextRow("Password", preferences.webDavPassword(), hidden = true) {
                    edit = it
                    secret = true
                }
                TextRow("Folder", preferences.webDavFolder()) {
                    edit = it
                    secret = false
                }
            }
            else -> Unit
        }
        if (service != SyncManager.SyncService.NONE.value) {
            ChoicePreference("Automatic sync", preferences.syncInterval(), INTERVALS)
            SettingRow("What to sync", onClick = { whatDialog = true })
            SettingRow("When to sync", "On start, when the window comes back, or when reading", onClick = { triggersDialog = true })
            SettingRow(
                if (running) "Syncing…" else "Sync now",
                if (last == 0L) "Never synced" else "Last synced ${DateFormat.getDateTimeInstance().format(Date(last))}",
                onClick = { if (!running) AppScope.launch { manager.syncData() } },
            )
            SettingRow(
                "Reset the last sync time",
                "The next sync uploads this library first, as a first sync does",
                onClick = { preferences.lastSyncTimestamp().set(0L) },
            )
        }
    }

    edit?.let { (title, preference) ->
        TextDialog(title, preference, secret) { edit = null }
    }
    if (whatDialog) WhatDialog(preferences) { whatDialog = false }
    if (triggersDialog) TriggersDialog(preferences) { triggersDialog = false }
}

@Composable
private fun TextRow(title: String, preference: Preference<String>, hidden: Boolean = false, onClick: (Pair<String, Preference<String>>) -> Unit) {
    val value by preference.changes().collectAsState(preference.get())
    SettingRow(
        title,
        when {
            value.isEmpty() -> "Not set"
            hidden -> "••••••••"
            else -> value
        },
        onClick = { onClick(title to preference) },
    )
}

@Composable
private fun TextDialog(title: String, preference: Preference<String>, secret: Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(preference.get()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                preference.set(text.trim())
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun WhatDialog(preferences: SyncPreferences, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(preferences.getSyncSettings()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What to sync") },
        text = {
            Column {
                CheckRow("Library entries", s.libraryEntries) { s = s.copy(libraryEntries = it) }
                CheckRow("Categories", s.categories) { s = s.copy(categories = it) }
                CheckRow("Chapters", s.chapters) { s = s.copy(chapters = it) }
                CheckRow("Tracking", s.tracking) { s = s.copy(tracking = it) }
                CheckRow("History", s.history) { s = s.copy(history = it) }
                CheckRow("Custom details", s.customInfo) { s = s.copy(customInfo = it) }
                CheckRow("Read entries outside the library", s.readEntries) { s = s.copy(readEntries = it) }
                CheckRow("Saved searches and feeds", s.savedSearchesFeeds) { s = s.copy(savedSearchesFeeds = it) }
                CheckRow("App settings", s.appSettings) { s = s.copy(appSettings = it) }
                CheckRow("Extension repos", s.extensionStores) { s = s.copy(extensionStores = it) }
                CheckRow("Source settings", s.sourceSettings) { s = s.copy(sourceSettings = it) }
                CheckRow("Private settings (logins, keys)", s.privateSettings) { s = s.copy(privateSettings = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.setSyncSettings(s)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TriggersDialog(preferences: SyncPreferences, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(preferences.getSyncTriggerOptions()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When to sync") },
        text = {
            Column {
                CheckRow("When Zoukan starts", t.syncOnAppStart) { t = t.copy(syncOnAppStart = it) }
                CheckRow("When the window comes back", t.syncOnAppResume) { t = t.copy(syncOnAppResume = it) }
                CheckRow("When a chapter opens", t.syncOnChapterOpen) { t = t.copy(syncOnChapterOpen = it) }
                CheckRow("When a chapter is read", t.syncOnChapterRead) { t = t.copy(syncOnChapterRead = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.setSyncTriggerOptions(t)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
