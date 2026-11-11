package zoukan.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.md.utils.MdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.exh.MangaDexSync
import zoukan.desktop.track.TrackerLogin
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR

/**
 * Settings > MangaDex, as the Android app's SettingsMangadexScreen: sign in to MangaDex (MDList),
 * the preferred MangaDex source, add follows to the library, and follow the library on MangaDex.
 * Shown when a MangaDex source is installed and enabled, as on Android.
 */
fun LazyListScope.mangaDexSettings() {
    if (MdUtil.getEnabledMangaDexs(Injekt.get()).isEmpty()) return
    settingsGroup(localized(SYMR.strings.pref_category_mangadex))
    item(key = "mangadex-settings") { MangaDexSettings() }
}

/** The follow statuses of the sync dialog, as md_follows_options without "Unfollowed" (values 1 to 6). */
private val followOptions = listOf(
    MR.strings.reading,
    MR.strings.completed,
    MR.strings.on_hold,
    MR.strings.plan_to_read,
    MR.strings.dropped,
    MR.strings.repeating,
)

@Composable
private fun MangaDexSettings() {
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val mangaDexs = remember { MdUtil.getEnabledMangaDexs(sourcePreferences) }
    if (mangaDexs.isEmpty()) return
    val mdex = remember { MdUtil.getEnabledMangaDex(sourcePreferences) } ?: return
    val mdList = remember { Injekt.get<TrackerManager>().mdList }
    val login = remember { Injekt.get<TrackerLogin>() }
    val sync = remember { Injekt.get<MangaDexSync>() }
    val loggedIn by mdList.isLoggedInFlow.collectAsState(mdList.isLoggedIn)
    val progress by sync.progress.collectAsState()
    val running by sync.running.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    var syncDialog by remember { mutableStateOf(false) }

    Column {
        SettingRow(localized(DMR.strings.login, mdex.name), if (loggedIn) localized(DMR.strings.signed_in) else localized(DMR.strings.not_signed_in)) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp))
                loggedIn -> TextButton(onClick = { logoutDialog = true }) { Text(localized(MR.strings.logout)) }
                else -> OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { login.loginWithBrowser(mdList) }
                            .onSuccess { if (it) Toasts.show(localized(DMR.strings.signed_in_to_mangadex)) }
                            .onFailure { Toasts.error(localized(DMR.strings.signing_in_to_mangadex_failed), it) }
                        busy = false
                    }
                }) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            }
        }
        val preferredId by sourcePreferences.preferredMangaDexId().changes()
            .collectAsState(sourcePreferences.preferredMangaDexId().get())
        ChoiceSetting(
            localized(SYMR.strings.mangadex_preffered_source),
            preferredId,
            mangaDexs.map { it.id.toString() to it.toString() },
            subtitle = localized(SYMR.strings.mangadex_preffered_source_summary),
        ) { sourcePreferences.preferredMangaDexId().set(it) }
        SettingRow(
            localized(SYMR.strings.mangadex_sync_follows_to_library),
            progress?.let { "${it.title} (${it.done + 1}/${it.total})" }
                ?: localized(SYMR.strings.mangadex_sync_follows_to_library_summary),
            enabled = !running,
            onClick = { syncDialog = true },
        ) { if (running) CircularProgressIndicator(Modifier.size(24.dp)) }
        SettingRow(
            localized(SYMR.strings.mangadex_push_favorites_to_mangadex),
            localized(SYMR.strings.mangadex_push_favorites_to_mangadex_summary),
            enabled = !running,
            onClick = {
                AppScope.launch {
                    runCatching { sync.pushFavorites() }
                        .onSuccess { Toasts.show(localized(DMR.strings.followed_library_entries_on_mangadex, it)) }
                        .onFailure { Toasts.error(localized(DMR.strings.following_the_library_on_mangadex_failed), it) }
                }
            },
        )
    }

    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text(localized(MR.strings.logout)) },
            confirmButton = {
                TextButton(onClick = {
                    logoutDialog = false
                    scope.launch(Dispatchers.IO) {
                        val ok = runCatching { mdex.logout() }.getOrDefault(false)
                        Toasts.show(localized(if (ok) MR.strings.logout_success else MR.strings.unknown_error))
                    }
                }) { Text(localized(MR.strings.logout)) }
            },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }

    if (syncDialog) {
        SyncFollowsDialog(
            onDismiss = { syncDialog = false },
            onConfirm = { statuses ->
                syncDialog = false
                sourcePreferences.mangadexSyncToLibraryIndexes().set(statuses)
                AppScope.launch {
                    runCatching { sync.syncFollows() }
                        .onSuccess { Toasts.show(localized(DMR.strings.added_mangadex_follows_to_the_library, it)) }
                        .onFailure { Toasts.error(localized(DMR.strings.syncing_mangadex_follows_failed), it) }
                }
            },
        )
    }
}

/** Picks the follow statuses to add to the library; Reading and Re-reading are checked first, as on Android. */
@Composable
private fun SyncFollowsDialog(onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    val selection = remember { mutableStateListOf(*Array(followOptions.size) { it == 0 || it == 5 }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(SYMR.strings.mangadex_sync_follows_to_library)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                followOptions.forEachIndexed { index, option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selection[index] = !selection[index] },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selection[index], onCheckedChange = null)
                        Text(localized(option), Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // The follow status values (FollowStatus.long) of the checked rows
                onConfirm(followOptions.indices.filter { selection[it] }.map { (it + 1).toString() }.toSet())
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}
