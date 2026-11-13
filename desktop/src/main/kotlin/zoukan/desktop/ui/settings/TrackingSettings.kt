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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.track.TrackerLogin
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR

/** Settings > Tracking: the tracking options and the trackers' sign-ins, as on Android. */
fun LazyListScope.trackingSettings() {
    settingsGroup(localized(MR.strings.track))
    item(key = "tracking-options") { TrackingOptions() }
    val trackers = Injekt.get<TrackerManager>().trackers
    items(trackers, key = { "tracker-${it.id}" }) { TrackerSetting(it) }
    item(key = "tracking-info") {
        SettingRow(
            localized(DMR.strings.about_sign_ins),
            localized(DMR.strings.myanimelist_anilist_shikimori_and_bangumi_open),
        )
    }
}

@Composable
private fun TrackingOptions() {
    val preferences = remember { Injekt.get<TrackPreferences>() }
    Column {
        SwitchPreference(localized(DMR.strings.track_when_adding_to_the_library), preferences.trackOnAddingToLibrary())
        SwitchPreference(localized(MR.strings.pref_auto_update_manga_sync), preferences.autoUpdateTrack())
        ChoicePreference(
            localized(DMR.strings.update_progress_when_marking_as_read),
            preferences.autoUpdateTrackOnMarkRead(),
            listOf(AutoTrackState.ALWAYS to localized(MR.strings.lock_always), AutoTrackState.NEVER to localized(MR.strings.lock_never), AutoTrackState.ASK to localized(DMR.strings.ask)),
        )
        SwitchPreference(
            localized(DMR.strings.sync_progress_from_trackers),
            preferences.autoSyncProgressFromTrackers(),
            localized(DMR.strings.mark_chapters_read_when_a_tracker),
        )
        SwitchPreference(
            localized(DMR.strings.resolve_with_source_metadata),
            preferences.resolveUsingSourceMetadata(),
            localized(DMR.strings.find_the_tracker_entry_from_the),
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
            if ((tracker as EnhancedTracker).accept(sources)) localized(DMR.strings.tracks_entries_from_its_source) else localized(DMR.strings.its_source_extension_is_not_installed)
        }
        loggedIn -> tracker.getUsername().takeIf { it.isNotBlank() && kind == TrackerLogin.Kind.PASSWORD }
            ?.let { localized(DMR.strings.signed_in_as, it) } ?: localized(DMR.strings.signed_in)
        else -> localized(DMR.strings.not_signed_in)
    }
    SettingRow(tracker.name, subtitle) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp))
            kind == TrackerLogin.Kind.ENHANCED -> {
                if (loggedIn) {
                    TextButton(onClick = { tracker.logout() }) { Text(localized(DMR.strings.turn_off)) }
                } else {
                    TextButton(onClick = { (tracker as EnhancedTracker).loginNoop() }) { Text(localized(DMR.strings.turn_on)) }
                }
            }
            loggedIn -> TextButton(onClick = { confirmLogout = true }) { Text(localized(DMR.strings.sign_out)) }
            kind == TrackerLogin.Kind.PASSWORD -> OutlinedButton(onClick = { passwordDialog = true }) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            else -> Row {
                TextButton(onClick = { pasteDialog = true }) { Text(localized(DMR.strings.other_browser)) }
                OutlinedButton(onClick = {
                    busy = true
                    // In the app's scope: a sign-in finished after leaving Settings still reports its result
                    val attempt = AppScope.launch {
                        try {
                            if (login.loginWithBrowser(tracker)) Toasts.show(localized(DMR.strings.signed_in_to, tracker.name))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Toasts.error(localized(DMR.strings.signing_in_to_failed, tracker.name), e)
                        }
                    }
                    scope.launch {
                        attempt.join()
                        busy = false
                    }
                }) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(localized(DMR.strings.sign_out_of, tracker.name)) },
            text = { Text(localized(DMR.strings.tracked_entries_stay_linked_their_progress)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    tracker.logout()
                }) { Text(localized(DMR.strings.sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }

    if (passwordDialog) {
        var user by remember { mutableStateOf(tracker.getUsername()) }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) passwordDialog = false },
            title = { Text(localized(DMR.strings.sign_in_to, tracker.name)) },
            text = {
                Column {
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(login.userNameLabel(tracker)) })
                    OutlinedTextField(
                        password,
                        { password = it },
                        singleLine = true,
                        label = { Text(localized(MR.strings.password)) },
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
                        // In the app's scope, so leaving Settings doesn't stop the sign-in partway; only
                        // a real failure signs out
                        val userName = user.trim()
                        val attempt = AppScope.async {
                            try {
                                login.loginWithPassword(tracker, userName, password)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                tracker.logout()
                                throw e
                            }
                            Toasts.show(localized(DMR.strings.signed_in_to, tracker.name))
                        }
                        scope.launch {
                            try {
                                attempt.await()
                                passwordDialog = false
                            } catch (e: CancellationException) {
                                // The dialog is gone (Settings left) while the sign-in runs: a toast
                                // tells of a failure instead
                                attempt.invokeOnCompletion { cause ->
                                    if (cause != null && cause !is CancellationException) {
                                        Toasts.error(localized(DMR.strings.could_not_sign_in), cause)
                                    }
                                }
                                throw e
                            } catch (e: Throwable) {
                                error = e.message ?: localized(DMR.strings.could_not_sign_in)
                            }
                            busy = false
                        }
                    },
                ) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            },
            dismissButton = { TextButton(onClick = { passwordDialog = false }, enabled = !busy) { Text(localized(MR.strings.action_cancel)) } },
        )
    }

    if (pasteDialog) {
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) pasteDialog = false },
            title = { Text(localized(DMR.strings.sign_in_with_another_browser)) },
            text = {
                Column {
                    Text(
                        localized(DMR.strings.sign_in_on_the_page_that),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        login.authUrl(tracker)?.let { runCatching { zoukan.desktop.app.ExternalLinks.browse(it) } }
                    }) { Text(localized(DMR.strings.open_the_sign_in_page)) }
                    OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("komikku://…") })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && TrackerLogin.isRedirect(address.trim()),
                    onClick = {
                        busy = true
                        // In the app's scope, so leaving Settings doesn't stop the sign-in partway
                        val pasted = address
                        val attempt = AppScope.async {
                            login.handleRedirect(pasted).also { ok -> if (ok) Toasts.show(localized(DMR.strings.signed_in_to, tracker.name)) }
                        }
                        scope.launch {
                            try {
                                if (attempt.await()) {
                                    pasteDialog = false
                                } else {
                                    error = localized(DMR.strings.did_not_accept_the_sign_in, tracker.name)
                                }
                            } catch (e: CancellationException) {
                                // The dialog is gone (Settings left) while the sign-in runs: a toast
                                // tells of a failure instead
                                attempt.invokeOnCompletion { cause ->
                                    when {
                                        cause is CancellationException -> Unit
                                        cause != null -> Toasts.error(localized(DMR.strings.could_not_sign_in), cause)
                                        else -> AppScope.launch {
                                            if (!attempt.await()) Toasts.show(localized(DMR.strings.did_not_accept_the_sign_in, tracker.name))
                                        }
                                    }
                                }
                                throw e
                            } catch (e: Throwable) {
                                error = e.message
                            }
                            busy = false
                        }
                    },
                ) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }, enabled = !busy) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}
