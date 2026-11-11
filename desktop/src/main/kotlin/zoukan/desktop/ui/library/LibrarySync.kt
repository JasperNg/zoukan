package zoukan.desktop.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncManager
import exh.favorites.FavoritesSyncHelper
import exh.source.ExhPreferences
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR

/**
 * SY's library overflow entries: "Sync EH favorites" while signed in to ExHentai, and "Sync library"
 * while a sync service is set in Settings > Data and storage.
 */
object LibrarySync {
    /** Whether the library offers "Sync EH favorites", as Android's showSyncExh. */
    @Composable
    fun showFavorites(): Boolean {
        val preferences = remember { Injekt.get<ExhPreferences>() }
        val enabled by preferences.isHentaiEnabled().changes().collectAsState(preferences.isHentaiEnabled().get())
        val exhentai by preferences.enableExhentai().changes().collectAsState(preferences.enableExhentai().get())
        return enabled && exhentai
    }

    /** Whether the library offers "Sync library", as Android's isSyncEnabled. */
    @Composable
    fun showLibrary(): Boolean {
        val preferences = remember { Injekt.get<SyncPreferences>() }
        val service by preferences.syncService().changes().collectAsState(preferences.syncService().get())
        return service != SyncManager.SyncService.NONE.value
    }

    /** Starts a library sync, unless one is running (Android's SyncDataJob.startNow). */
    fun syncLibrary() {
        val manager = Injekt.get<SyncManager>()
        if (manager.running.value) {
            Toasts.show(localized(SYMR.strings.sync_in_progress))
            return
        }
        AppScope.launch { runCatching { manager.syncData() }.onFailure { Toasts.error(localized(DMR.strings.sync_failed), it) } }
    }

    /** Whether "Sync EH favorites" first shows the sync notes, which it does once. */
    fun showsNotes(): Boolean = Injekt.get<ExhPreferences>().exhShowSyncIntro().get()
}

/** The sync notes Android shows the first time favorites sync is picked (SyncFavoritesWarningDialog). */
@Composable
fun SyncFavoritesNotesDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.important_favorites_sync_notes)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScrollWithBar()) {
                Text(FAVORITES_SYNC_NOTES)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Injekt.get<ExhPreferences>().exhShowSyncIntro().set(false)
                onAccept()
            }) { Text("OK") }
        },
    )
}

/** Android's SyncFavoritesConfirmDialog; confirming starts the favorites sync. */
@Composable
fun SyncFavoritesConfirmDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(SYMR.strings.sync_favorites)) },
        text = { Text(localized(SYMR.strings.favorites_sync_conformation_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                Injekt.get<FavoritesSyncHelper>().runSync(AppScope)
                Toasts.show(localized(DMR.strings.syncing_favorites_settings_e_hentai_shows))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

private val FAVORITES_SYNC_NOTES get() =
    localized(DMR.strings.favorites_sync_notes)

/** Which favorites sync dialog shows: the one-time notes, as Android, or the confirmation. */
enum class FavoritesSyncDialog { Notes, Confirm }
