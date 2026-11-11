package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import eu.kanade.tachiyomi.AppInfo
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.compat.localized
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.UpdateStates
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import kotlin.system.exitProcess

/** Settings > About: version, updates, the log file and crash logs (Phase 7). */
fun LazyListScope.aboutSettings() {
    settingsGroup(localized(MR.strings.pref_category_about))
    item(key = "about-version") {
        SettingRow(
            localized(DMR.strings.zoukan, AppVersion.name),
            localized(DMR.strings.komikku_for_windows_and_macos_on, AppInfo.getVersionName(), System.getProperty("java.runtime.version")),
        )
    }
    item(key = "about-update") { UpdateRow() }
    // Android's "What's new": this version's release notes
    item(key = "about-whats-new") {
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current
        SettingRow(localized(MR.strings.whats_new), localized(DMR.strings.release_notes_for_zoukan, AppVersion.name), onClick = {
            navigator?.push(zoukan.desktop.ui.whatsnew.WhatsNewScreen())
        })
    }
    item(key = "about-auto-update") {
        SwitchPreference(localized(DMR.strings.check_for_updates_automatically), Updates.autoCheck(), localized(DMR.strings.once_a_day_while_zoukan_runs))
    }
    item(key = "about-prereleases") {
        SwitchPreference(localized(DMR.strings.include_test_releases), Updates.includePrereleases(), localized(DMR.strings.releases_marked_as_pre_release_on))
    }
    item(key = "about-token") { TokenRow() }
    item(key = "about-logs") {
        SettingRow(
            localized(DMR.strings.open_the_log_folder),
            localized(DMR.strings.zoukan_log_has_what_zoukan_logged),
            onClick = { CrashReporter.logDir?.let(::openFolder) },
        )
    }
    item(key = "about-crashes") {
        val reports = remember { CrashReporter.reports() }
        SettingRow(
            localized(DMR.strings.crash_logs),
            if (reports.isEmpty()) localized(DMR.strings.no_crashes_recorded) else localized(DMR.strings.saved_newest, reports.size, reports.first().name),
            onClick = { CrashReporter.crashDir?.let(::openFolder) },
        )
    }
}

@Composable
private fun UpdateRow() {
    val state by Updates.state.collectAsState()
    val subtitle = when (val s = state) {
        UpdateStates.State.Idle -> localized(DMR.strings.releases_from_github_com, AppVersion.releaseRepo)
        UpdateStates.State.Checking -> localized(DMR.strings.checking)
        is UpdateStates.State.Result -> when (val r = s.result) {
            UpdateChecker.Result.UpToDate -> localized(DMR.strings.zoukan_is_the_newest_version, AppVersion.name)
            is UpdateChecker.Result.Failed -> r.message
            is UpdateChecker.Result.Available -> buildString {
                val available = localized(DMR.strings.is_available, r.release.name)
                append(
                    when {
                        r.release.asset == null -> localized(DMR.strings.update_no_installer, available, Platform.id)
                        r.release.asset?.sha256 == null -> localized(DMR.strings.update_no_checksum, available)
                        else -> available
                    },
                )
                r.release.notes.lines().filter { it.isNotBlank() }.take(8).takeIf { it.isNotEmpty() }?.let {
                    append("\n").append(it.joinToString("\n"))
                }
            }
        }
        is UpdateStates.State.Downloading ->
            localized(DMR.strings.downloading_of_mb, s.release.asset?.name, s.read shr 20, s.total shr 20)
        is UpdateStates.State.Downloaded ->
            localized(
                if (Platform.current == Platform.WINDOWS) DMR.strings.installing_closes_zoukan else DMR.strings.drag_zoukan_to_applications,
                localized(DMR.strings.is_downloaded, s.release.name),
            )
        is UpdateStates.State.Failed -> s.message
    }
    SettingRow(
        localized(MR.strings.check_for_updates),
        subtitle,
        enabled = state !is UpdateStates.State.Checking && state !is UpdateStates.State.Downloading,
        onClick = { AppScope.launch { Updates.check(manual = true) } },
    ) {
        when (val s = state) {
            UpdateStates.State.Checking, is UpdateStates.State.Downloading -> CircularProgressIndicator(Modifier.size(24.dp))
            is UpdateStates.State.Result -> (s.result as? UpdateChecker.Result.Available)?.release?.let { release ->
                // Without a checksum the download is refused, so it is not offered
                if (release.asset?.sha256 != null) {
                    TextButton(onClick = { AppScope.launch { Updates.download(release) } }) { Text(localized(MR.strings.action_download)) }
                }
                if (release.pageUrl.isNotBlank()) {
                    TextButton(onClick = { browse(release.pageUrl) }) { Text(localized(KMR.strings.release_page)) }
                }
            }
            is UpdateStates.State.Failed -> s.release?.let { release ->
                // Trying again cannot help when the release has no checksum
                if (release.asset?.sha256 != null) {
                    TextButton(onClick = { AppScope.launch { Updates.download(release) } }) { Text(localized(DMR.strings.try_again)) }
                }
                if (release.pageUrl.isNotBlank()) {
                    TextButton(onClick = { browse(release.pageUrl) }) { Text(localized(KMR.strings.release_page)) }
                }
            }
            is UpdateStates.State.Downloaded -> TextButton(onClick = {
                runCatching { Updates.install(s.file) { exitProcess(0) } }
                    .onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_installer), it) }
            }) { Text(localized(MR.strings.action_install)) }
            else -> Unit
        }
    }
}

@Composable
private fun TokenRow() {
    var dialog by remember { mutableStateOf(false) }
    var hasToken by remember { mutableStateOf(Updates.checker.token != null) }
    val storeName = remember { Injekt.get<SecretStore>().name }
    SettingRow(
        if (hasToken) localized(DMR.strings.github_token_set) else localized(DMR.strings.github_token),
        localized(DMR.strings.needed_while_the_release_repository_is, storeName),
        onClick = { dialog = true },
    )
    if (dialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(localized(DMR.strings.github_token)) },
            text = {
                OutlinedTextField(
                    value,
                    { value = it },
                    singleLine = true,
                    label = { Text(localized(SYMR.strings.token)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    Updates.checker.token = value
                    hasToken = true
                    dialog = false
                }) { Text(localized(MR.strings.action_save)) }
            },
            dismissButton = {
                if (hasToken) {
                    TextButton(onClick = {
                        Updates.checker.token = null
                        hasToken = false
                        dialog = false
                    }) { Text(localized(MR.strings.action_remove)) }
                }
                TextButton(onClick = { dialog = false }) { Text(localized(MR.strings.action_cancel)) }
            },
        )
    }
}

private fun browse(url: String) {
    runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }.onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_page), it) }
}
