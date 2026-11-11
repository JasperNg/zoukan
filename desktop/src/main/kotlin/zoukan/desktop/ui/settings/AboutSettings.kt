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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.UpdateStates
import zoukan.desktop.update.Updates
import kotlin.system.exitProcess

/** Settings > About: version, updates, the log file and crash logs (Phase 7). */
fun LazyListScope.aboutSettings() {
    settingsGroup("About")
    item(key = "about-version") {
        SettingRow(
            "Zoukan ${AppVersion.name}",
            "Komikku ${AppInfo.getVersionName()} for Windows and macOS, on Java ${System.getProperty("java.runtime.version")}",
        )
    }
    item(key = "about-update") { UpdateRow() }
    // Android's "What's new": this version's release notes
    item(key = "about-whats-new") {
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.current
        SettingRow("What's new", "Release notes for Zoukan ${AppVersion.name}", onClick = {
            navigator?.push(zoukan.desktop.ui.whatsnew.WhatsNewScreen())
        })
    }
    item(key = "about-auto-update") {
        SwitchPreference("Check for updates automatically", Updates.autoCheck(), "Once a day while Zoukan runs")
    }
    item(key = "about-prereleases") {
        SwitchPreference("Include test releases", Updates.includePrereleases(), "Releases marked as pre-release on GitHub")
    }
    item(key = "about-token") { TokenRow() }
    item(key = "about-logs") {
        SettingRow(
            "Open the log folder",
            "zoukan.log has what Zoukan logged; attach it when reporting a problem",
            onClick = { CrashReporter.logDir?.let(::openFolder) },
        )
    }
    item(key = "about-crashes") {
        val reports = remember { CrashReporter.reports() }
        SettingRow(
            "Crash logs",
            if (reports.isEmpty()) "No crashes recorded" else "${reports.size} saved, newest ${reports.first().name}",
            onClick = { CrashReporter.crashDir?.let(::openFolder) },
        )
    }
}

@Composable
private fun UpdateRow() {
    val state by Updates.state.collectAsState()
    val subtitle = when (val s = state) {
        UpdateStates.State.Idle -> "Releases from github.com/${AppVersion.releaseRepo}"
        UpdateStates.State.Checking -> "Checking…"
        is UpdateStates.State.Result -> when (val r = s.result) {
            UpdateChecker.Result.UpToDate -> "Zoukan ${AppVersion.name} is the newest version"
            is UpdateChecker.Result.Failed -> r.message
            is UpdateChecker.Result.Available -> buildString {
                append("${r.release.name} is available")
                if (r.release.asset == null) append(", without an installer for ${Platform.id}")
                if (r.release.asset?.sha256 == null && r.release.asset != null) {
                    append(". GitHub lists no checksum for its installer, so get it from the release page")
                }
                r.release.notes.lines().filter { it.isNotBlank() }.take(8).takeIf { it.isNotEmpty() }?.let {
                    append("\n").append(it.joinToString("\n"))
                }
            }
        }
        is UpdateStates.State.Downloading ->
            "Downloading ${s.release.asset?.name}: ${s.read shr 20} of ${s.total shr 20} MB"
        is UpdateStates.State.Downloaded ->
            "${s.release.name} is downloaded" +
                if (Platform.current == Platform.WINDOWS) ". Installing closes Zoukan." else ". Drag Zoukan to Applications in the window that opens."
        is UpdateStates.State.Failed -> s.message
    }
    SettingRow(
        "Check for updates",
        subtitle,
        enabled = state !is UpdateStates.State.Checking && state !is UpdateStates.State.Downloading,
        onClick = { AppScope.launch { Updates.check(manual = true) } },
    ) {
        when (val s = state) {
            UpdateStates.State.Checking, is UpdateStates.State.Downloading -> CircularProgressIndicator(Modifier.size(24.dp))
            is UpdateStates.State.Result -> (s.result as? UpdateChecker.Result.Available)?.release?.let { release ->
                // Without a checksum the download is refused, so it is not offered
                if (release.asset?.sha256 != null) {
                    TextButton(onClick = { AppScope.launch { Updates.download(release) } }) { Text("Download") }
                }
                if (release.pageUrl.isNotBlank()) {
                    TextButton(onClick = { browse(release.pageUrl) }) { Text("Release page") }
                }
            }
            is UpdateStates.State.Failed -> s.release?.let { release ->
                // Trying again cannot help when the release has no checksum
                if (release.asset?.sha256 != null) {
                    TextButton(onClick = { AppScope.launch { Updates.download(release) } }) { Text("Try again") }
                }
                if (release.pageUrl.isNotBlank()) {
                    TextButton(onClick = { browse(release.pageUrl) }) { Text("Release page") }
                }
            }
            is UpdateStates.State.Downloaded -> TextButton(onClick = {
                runCatching { Updates.install(s.file) { exitProcess(0) } }
                    .onFailure { Toasts.error("Could not open the installer", it) }
            }) { Text("Install") }
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
        if (hasToken) "GitHub token: set" else "GitHub token",
        "Needed while the release repository is private: a fine-grained token with read access to its contents. Kept in $storeName.",
        onClick = { dialog = true },
    )
    if (dialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("GitHub token") },
            text = {
                OutlinedTextField(
                    value,
                    { value = it },
                    singleLine = true,
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    Updates.checker.token = value
                    hasToken = true
                    dialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                if (hasToken) {
                    TextButton(onClick = {
                        Updates.checker.token = null
                        hasToken = false
                        dialog = false
                    }) { Text("Remove") }
                }
                TextButton(onClick = { dialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun browse(url: String) {
    runCatching { zoukan.desktop.app.ExternalLinks.browse(url) }.onFailure { Toasts.error("Could not open the page", it) }
}
