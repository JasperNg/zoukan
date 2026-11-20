package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.AppInfo
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_close
import tachiyomi.i18n.action_download
import tachiyomi.i18n.action_install
import tachiyomi.i18n.check_for_updates
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.release_page
import tachiyomi.i18n.licenses
import tachiyomi.i18n.pref_category_about
import tachiyomi.i18n.whats_new
import zoukan.desktop.Platform
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.browser.BrowserWindow
import zoukan.desktop.browser.JcefRuntime
import zoukan.desktop.compat.localized
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.UpdateStates
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.check_for_updates_automatically
import zoukan.i18n.desktop.checking
import zoukan.i18n.desktop.could_not_open_the_installer
import zoukan.i18n.desktop.could_not_open_the_page
import zoukan.i18n.desktop.crash_logs
import zoukan.i18n.desktop.downloading_of_mb
import zoukan.i18n.desktop.drag_zoukan_to_applications
import zoukan.i18n.desktop.include_test_releases
import zoukan.i18n.desktop.installing_closes_zoukan
import zoukan.i18n.desktop.is_available
import zoukan.i18n.desktop.is_downloaded
import zoukan.i18n.desktop.komikku_for_windows_and_macos_on
import zoukan.i18n.desktop.license_apache_2
import zoukan.i18n.desktop.license_mpl_2
import zoukan.i18n.desktop.licenses_notice
import zoukan.i18n.desktop.licenses_summary
import zoukan.i18n.desktop.no_crashes_recorded
import zoukan.i18n.desktop.once_a_day_while_zoukan_runs
import zoukan.i18n.desktop.open_the_log_folder
import zoukan.i18n.desktop.release_notes_for_zoukan
import zoukan.i18n.desktop.releases_from_github_com
import zoukan.i18n.desktop.releases_marked_as_pre_release_on
import zoukan.i18n.desktop.saved_newest
import zoukan.i18n.desktop.source_code
import zoukan.i18n.desktop.try_again
import zoukan.i18n.desktop.update_no_checksum
import zoukan.i18n.desktop.update_no_installer
import zoukan.i18n.desktop.zoukan
import zoukan.i18n.desktop.zoukan_is_the_newest_version
import zoukan.i18n.desktop.zoukan_log_has_what_zoukan_logged

/** Settings > About: version, updates, licenses, the log file and crash logs. */
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
    item(key = "about-licenses") { LicensesRow() }
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

internal enum class BundledLicense(val resourcePath: String) {
    APACHE_2("licenses/LICENSE-Apache-2.0.txt"),
    MPL_2("licenses/LICENSE-android-compat-MPL-2.0.txt"),
}

internal fun loadBundledLicense(license: BundledLicense): String =
    BundledLicense::class.java.classLoader
        ?.getResourceAsStream(license.resourcePath)
        ?.bufferedReader()
        ?.use { it.readText() }
        .orEmpty()

@Composable
private fun LicensesRow() {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        localized(MR.strings.licenses),
        localized(DMR.strings.licenses_summary),
        onClick = { open = true },
    )
    if (open) {
        LicensesDialog(onDismiss = { open = false })
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(BundledLicense.APACHE_2) }
    val licenseText = remember(selected) { loadBundledLicense(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(MR.strings.licenses)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized(DMR.strings.licenses_notice), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selected == BundledLicense.APACHE_2,
                        onClick = { selected = BundledLicense.APACHE_2 },
                        label = { Text(localized(DMR.strings.license_apache_2)) },
                    )
                    FilterChip(
                        selected = selected == BundledLicense.MPL_2,
                        onClick = { selected = BundledLicense.MPL_2 },
                        label = { Text(localized(DMR.strings.license_mpl_2)) },
                    )
                }
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { browse("https://github.com/${AppVersion.releaseRepo}") }) {
                Text(localized(DMR.strings.source_code))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) }
        },
    )
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
                runCatching { SystemEffects.current.installUpdate(s.file) { exitApp(0) } }
                    .onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_installer), it) }
            }) { Text(localized(MR.strings.action_install)) }
            else -> Unit
        }
    }
}

private fun browse(url: String) {
    runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }.onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_page), it) }
}

/**
 * Exits for the update install: the built-in browser windows are disposed and the browser's
 * profile is cleaned, in the same order as at any quit (see main).
 */
private fun exitApp(code: Int) {
    runCatching { BrowserWindow.disposeAllForQuit() }
    JcefRuntime.cleanProfileAtQuit()
    SystemEffects.current.exit(code)
}
