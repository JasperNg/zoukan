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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.verticalScrollWithBar

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
            Toasts.show("Sync is already in progress")
            return
        }
        AppScope.launch { runCatching { manager.syncData() }.onFailure { Toasts.error("Sync failed", it) } }
    }

    /** Whether "Sync EH favorites" first shows the sync notes, which it does once. */
    fun showsNotes(): Boolean = Injekt.get<ExhPreferences>().exhShowSyncIntro().get()
}

/** The sync notes Android shows the first time favorites sync is picked (SyncFavoritesWarningDialog). */
@Composable
fun SyncFavoritesNotesDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Important favorites sync notes") },
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
        title = { Text("Sync EH favorites") },
        text = { Text("Are you sure you wish to sync your favorites with E-Hentai?") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                Injekt.get<FavoritesSyncHelper>().runSync(AppScope)
                Toasts.show("Syncing favorites. Settings > E-Hentai shows its progress.")
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val FAVORITES_SYNC_NOTES =
    "1. Changes to category names in the app are not synced. Change the category names on ExHentai instead; " +
        "they are copied from ExHentai on every sync.\n\n" +
        "2. The favorite categories on ExHentai correspond to the first 10 categories in the app (excluding Default). " +
        "Galleries in other categories are not synced.\n\n" +
        "3. Make sure you have a stable internet connection while sync runs. If it disconnects, your favorites may be " +
        "left partially synced.\n\n" +
        "4. Keep the app open while favorites sync.\n\n" +
        "5. Do not put favorites in more than one category. ExHentai allows each favorite in only one category, so " +
        "this confuses the sync.\n\n" +
        "This dialog only shows once."

/** Which favorites sync dialog shows: the one-time notes, as Android, or the confirmation. */
enum class FavoritesSyncDialog { Notes, Confirm }
