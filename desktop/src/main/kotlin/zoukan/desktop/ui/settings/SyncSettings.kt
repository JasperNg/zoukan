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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.collectAsState
import zoukan.i18n.desktop.DMR
import java.text.DateFormat
import java.util.Date

/** Settings > Data sync: SyncYomi or WebDAV, as on Android. Google Drive needs the app's OAuth secrets. */
fun LazyListScope.syncSettings() {
    settingsGroup(localized(DMR.strings.data_sync))
    item(key = "sync") { SyncSettingsSection() }
}

private val INTERVALS = listOf(
    0 to localized(DMR.strings.manually),
    30 to localized(SYMR.strings.update_30min),
    60 to localized(SYMR.strings.update_1hour),
    180 to localized(MR.strings.update_3hour),
    360 to localized(MR.strings.update_6hour),
    720 to localized(MR.strings.update_12hour),
    1440 to localized(DMR.strings.every_day),
    2880 to localized(MR.strings.update_48hour),
    10080 to localized(DMR.strings.every_week),
)

@Composable
private fun SyncSettingsSection() {
    val preferences = remember { Injekt.get<SyncPreferences>() }
    val manager = remember { Injekt.get<SyncManager>() }
    val service by preferences.syncService().collectAsState()
    val running by manager.running.collectAsState()
    val last by preferences.lastSyncTimestamp().collectAsState()
    var edit by remember { mutableStateOf<Pair<String, Preference<String>>?>(null) }
    var secret by remember { mutableStateOf(false) }
    var whatDialog by remember { mutableStateOf(false) }
    var triggersDialog by remember { mutableStateOf(false) }

    Column {
        ChoicePreference(
            localized(DMR.strings.sync_service),
            preferences.syncService(),
            listOf(
                SyncManager.SyncService.NONE.value to localized(MR.strings.off),
                SyncManager.SyncService.SYNCYOMI.value to localized(SYMR.strings.syncyomi),
                SyncManager.SyncService.WEB_DAV.value to localized(KMR.strings.web_dav),
            ),
        )
        when (SyncManager.SyncService.fromInt(service)) {
            SyncManager.SyncService.SYNCYOMI -> {
                TextRow(localized(SYMR.strings.pref_sync_host), preferences.clientHost()) {
                    edit = it
                    secret = false
                }
                TextRow(localized(SYMR.strings.pref_sync_api_key), preferences.clientAPIKey(), hidden = true) {
                    edit = it
                    secret = true
                }
            }
            SyncManager.SyncService.WEB_DAV -> {
                TextRow(localized(DMR.strings.server_url), preferences.webDavUrl()) {
                    edit = it
                    secret = false
                }
                TextRow(localized(MR.strings.username), preferences.webDavUsername()) {
                    edit = it
                    secret = false
                }
                TextRow(localized(MR.strings.password), preferences.webDavPassword(), hidden = true) {
                    edit = it
                    secret = true
                }
                TextRow(localized(DMR.strings.folder), preferences.webDavFolder()) {
                    edit = it
                    secret = false
                }
            }
            else -> Unit
        }
        if (service != SyncManager.SyncService.NONE.value) {
            ChoicePreference(localized(DMR.strings.automatic_sync), preferences.syncInterval(), INTERVALS)
            SettingRow(localized(DMR.strings.what_to_sync), onClick = { whatDialog = true })
            SettingRow(localized(DMR.strings.when_to_sync), localized(DMR.strings.on_start_when_the_window_comes), onClick = { triggersDialog = true })
            SettingRow(
                if (running) localized(DMR.strings.syncing) else localized(SYMR.strings.pref_sync_now),
                if (last == 0L) localized(DMR.strings.never_synced) else localized(DMR.strings.last_synced, DateFormat.getDateTimeInstance().format(Date(last))),
                onClick = { if (!running) AppScope.launch { manager.syncData() } },
            )
            SettingRow(
                localized(DMR.strings.reset_the_last_sync_time),
                localized(DMR.strings.the_next_sync_uploads_this_library),
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
    val value by preference.collectAsState()
    SettingRow(
        title,
        when {
            value.isEmpty() -> localized(DMR.strings.not_set)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
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
        title = { Text(localized(DMR.strings.what_to_sync)) },
        text = {
            Column {
                CheckRow(localized(MR.strings.manga), s.libraryEntries) { s = s.copy(libraryEntries = it) }
                CheckRow(localized(MR.strings.categories), s.categories) { s = s.copy(categories = it) }
                CheckRow(localized(MR.strings.chapters), s.chapters) { s = s.copy(chapters = it) }
                CheckRow(localized(MR.strings.track), s.tracking) { s = s.copy(tracking = it) }
                CheckRow(localized(MR.strings.history), s.history) { s = s.copy(history = it) }
                CheckRow(localized(DMR.strings.custom_details), s.customInfo) { s = s.copy(customInfo = it) }
                CheckRow(localized(DMR.strings.read_entries_outside_the_library), s.readEntries) { s = s.copy(readEntries = it) }
                CheckRow(localized(DMR.strings.saved_searches_and_feeds), s.savedSearchesFeeds) { s = s.copy(savedSearchesFeeds = it) }
                CheckRow(localized(MR.strings.app_settings), s.appSettings) { s = s.copy(appSettings = it) }
                CheckRow(localized(DMR.strings.extension_repos), s.extensionStores) { s = s.copy(extensionStores = it) }
                CheckRow(localized(MR.strings.source_settings), s.sourceSettings) { s = s.copy(sourceSettings = it) }
                CheckRow(localized(DMR.strings.private_settings_logins_keys), s.privateSettings) { s = s.copy(privateSettings = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.setSyncSettings(s)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun TriggersDialog(preferences: SyncPreferences, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(preferences.getSyncTriggerOptions()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.when_to_sync)) },
        text = {
            Column {
                CheckRow(localized(DMR.strings.when_zoukan_starts), t.syncOnAppStart) { t = t.copy(syncOnAppStart = it) }
                CheckRow(localized(DMR.strings.when_the_window_comes_back), t.syncOnAppResume) { t = t.copy(syncOnAppResume = it) }
                CheckRow(localized(DMR.strings.when_a_chapter_opens), t.syncOnChapterOpen) { t = t.copy(syncOnChapterOpen = it) }
                CheckRow(localized(DMR.strings.when_a_chapter_is_read), t.syncOnChapterRead) { t = t.copy(syncOnChapterRead = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.setSyncTriggerOptions(t)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
