package zoukan.desktop.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import tachiyomi.i18n.MR
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.InfoScreen
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR

/**
 * Android's What's new screen: the release notes of the running version, read from its GitHub
 * release. Opened from Settings > About and from the dialog after an update.
 */
class WhatsNewScreen : cafe.adriel.voyager.core.screen.Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var notes by remember { mutableStateOf<Result<UpdateChecker.Notes>?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            notes = null
            notes = Updates.checker.releaseNotes()
        }
        val loaded = notes?.getOrNull()
        InfoScreen(
            icon = Icons.Outlined.NewReleases,
            headingText = localized(MR.strings.whats_new),
            subtitleText = loaded?.name?.takeIf { it != "Zoukan ${AppVersion.name}" }?.let { localized(DMR.strings.zoukan_2, it, AppVersion.name) }
                ?: localized(DMR.strings.zoukan, AppVersion.name),
            acceptText = "OK",
            onAcceptClick = { navigator.pop() },
            secondaryButton = {
                val page = loaded?.pageUrl?.takeIf { it.isNotBlank() }
                    ?: "https://github.com/${AppVersion.releaseRepo}/releases"
                TextButton(onClick = {
                    runCatching { ExternalLinks.browse(page) }.onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_page), it) }
                }) {
                    Text(localized(MR.strings.update_check_open))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp).padding(start = 4.dp))
                }
            },
        ) {
            val result = notes
            when {
                result == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(localized(DMR.strings.loading_the_release_notes))
                }
                result.isFailure -> {
                    Text(result.exceptionOrNull()?.message ?: localized(DMR.strings.could_not_load_the_release_notes), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text(localized(DMR.strings.try_again)) }
                }
                loaded != null && loaded.body.isBlank() -> Text(localized(DMR.strings.this_release_has_no_notes))
                loaded != null -> ReleaseNotesText(loaded.body)
            }
        }
    }
}

/**
 * The first launch after an update, as Android's MainActivity after a migration: [updatedFrom]
 * is the version that ran before, or null when nothing should be said (a first run, the same
 * version, or an older one). Records the running version either way.
 */
object WhatsNew {
    fun updatedFrom(preferences: DesktopPreferences, current: Version = AppVersion.version): Version? {
        val last = preferences.lastVersion().get()
        preferences.lastVersion().set(current.toString())
        val previous = Version.parse(last) ?: return null
        return previous.takeIf { it < current }
    }
}

/** Android's WhatsNewDialog: "Updated to …" with OK and What's new. */
@Composable
fun UpdatedDialog(onDismiss: () -> Unit, onOpenWhatsNew: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.updated_to_zoukan, AppVersion.name)) },
        text = { Text(localized(DMR.strings.see_what_changed_in_this_version)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton = { TextButton(onClick = onOpenWhatsNew) { Text(localized(MR.strings.whats_new)) } },
    )
}
