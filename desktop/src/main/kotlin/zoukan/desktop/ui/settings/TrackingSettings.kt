package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.track.TrackerLogin
import zoukan.desktop.ui.common.Toasts

/** Settings > Tracking: the tracking options and the trackers' sign-ins, as on Android. */
fun LazyListScope.trackingSettings() {
    settingsGroup("Tracking")
    item(key = "tracking-options") { TrackingOptions() }
    val trackers = Injekt.get<TrackerManager>().trackers
    items(trackers, key = { "tracker-${it.id}" }) { TrackerSetting(it) }
    item(key = "tracking-info") {
        SettingRow(
            "About sign-ins",
            "MyAnimeList, AniList, Shikimori and Bangumi open their sign-in page in the built-in browser. " +
                "Komga, Kavita and Suwayomi track entries from their own sources and need no sign-in.",
        )
    }
}

@Composable
private fun TrackingOptions() {
    val preferences = remember { Injekt.get<TrackPreferences>() }
    Column {
        SwitchPreference("Track when adding to the library", preferences.trackOnAddingToLibrary())
        SwitchPreference("Update progress after reading", preferences.autoUpdateTrack())
        ChoicePreference(
            "Update progress when marking as read",
            preferences.autoUpdateTrackOnMarkRead(),
            listOf(AutoTrackState.ALWAYS to "Always", AutoTrackState.NEVER to "Never", AutoTrackState.ASK to "Ask"),
        )
        SwitchPreference(
            "Sync progress from trackers",
            preferences.autoSyncProgressFromTrackers(),
            "Mark chapters read when a tracker is further along",
        )
        SwitchPreference(
            "Resolve with source metadata",
            preferences.resolveUsingSourceMetadata(),
            "Find the tracker entry from the ids some sources (MangaDex, E-Hentai) list",
        )
    }
}

@Composable
private fun TrackerSetting(tracker: Tracker) {
    val login = remember { Injekt.get<TrackerLogin>() }
    val loggedIn by tracker.isLoggedInFlow.collectAsState(tracker.isLoggedIn)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val kind = login.kindOf(tracker)

    val subtitle = when {
        kind == TrackerLogin.Kind.ENHANCED -> {
            val sources = remember { Injekt.get<SourceManager>().getAll() }
            if ((tracker as EnhancedTracker).accept(sources)) "Tracks entries from its source" else "Its source extension is not installed"
        }
        loggedIn -> "Signed in" + tracker.getUsername().takeIf { it.isNotBlank() && kind == TrackerLogin.Kind.PASSWORD }?.let { " as $it" }.orEmpty()
        else -> "Not signed in"
    }
    SettingRow(tracker.name, subtitle) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp))
            kind == TrackerLogin.Kind.ENHANCED -> {
                if (loggedIn) {
                    TextButton(onClick = { tracker.logout() }) { Text("Turn off") }
                } else {
                    TextButton(onClick = { (tracker as EnhancedTracker).loginNoop() }) { Text("Turn on") }
                }
            }
            loggedIn -> TextButton(onClick = { confirmLogout = true }) { Text("Sign out") }
            kind == TrackerLogin.Kind.PASSWORD -> OutlinedButton(onClick = { passwordDialog = true }) { Text("Sign in") }
            else -> Row {
                TextButton(onClick = { pasteDialog = true }) { Text("Other browser") }
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { login.loginWithBrowser(tracker) }
                            .onSuccess { if (it) Toasts.show("Signed in to ${tracker.name}") }
                            .onFailure { Toasts.error("Signing in to ${tracker.name} failed", it) }
                        busy = false
                    }
                }) { Text("Sign in") }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out of ${tracker.name}?") },
            text = { Text("Tracked entries stay linked; their progress is not sent until you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    tracker.logout()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }

    if (passwordDialog) {
        var user by remember { mutableStateOf(tracker.getUsername()) }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) passwordDialog = false },
            title = { Text("Sign in to ${tracker.name}") },
            text = {
                Column {
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(login.userNameLabel(tracker)) })
                    OutlinedTextField(
                        password,
                        { password = it },
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && user.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { login.loginWithPassword(tracker, user.trim(), password) }
                                .onSuccess {
                                    passwordDialog = false
                                    Toasts.show("Signed in to ${tracker.name}")
                                }
                                .onFailure {
                                    tracker.logout()
                                    error = it.message ?: "Could not sign in"
                                }
                            busy = false
                        }
                    },
                ) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = false }, enabled = !busy) { Text("Cancel") } },
        )
    }

    if (pasteDialog) {
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) pasteDialog = false },
            title = { Text("Sign in with another browser") },
            text = {
                Column {
                    Text(
                        "Sign in on the page that opens. When it is done, the browser tries to open an address starting " +
                            "with komikku:// and cannot. Copy that address from the browser and paste it here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        login.authUrl(tracker)?.let { runCatching { zoukan.desktop.app.ExternalLinks.browse(it) } }
                    }) { Text("Open the sign-in page") }
                    OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("komikku://…") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && TrackerLogin.isRedirect(address.trim()),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { login.handleRedirect(address) }
                                .onSuccess { ok ->
                                    if (ok) {
                                        pasteDialog = false
                                        Toasts.show("Signed in to ${tracker.name}")
                                    } else {
                                        error = "${tracker.name} did not accept the sign-in"
                                    }
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}
