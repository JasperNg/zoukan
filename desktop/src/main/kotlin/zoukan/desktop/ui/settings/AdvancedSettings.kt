package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import exh.log.EHLogLevel
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.ExtensionTrust
import java.awt.FileDialog
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager

/**
 * Settings > Advanced, as the Android app's SettingsAdvancedScreen, without its Android-only parts
 * (notifications, battery optimization, the reader's hardware bitmap and display profile, the APK
 * installer and the debug screens; see PARITY.md).
 */
fun LazyListScope.advancedSettings() {
    settingsGroup("Advanced")
    item(key = "advanced-crash-logs") { CrashLogsPreference() }
    // Android's "Onboarding guide": the first-run setup again
    item(key = "advanced-onboarding") {
        SettingRow("Onboarding guide", onClick = { zoukan.desktop.ui.onboarding.Onboarding.reopen.value = true })
    }

    settingsGroup("Data")
    item(key = "advanced-reindex") {
        SettingRow("Reindex downloads", "Force app to recheck downloaded chapters", onClick = {
            Injekt.get<DownloadManager>().invalidateCache()
            Toasts.show("Recreating download index")
        })
    }
    item(key = "advanced-clear-database") {
        val navigator = LocalNavigator.currentOrThrow
        SettingRow(
            "Clear database",
            "Delete history for entries that are not saved in your library",
            onClick = { navigator.push(ClearDatabaseScreen) },
        )
    }

    settingsGroup("Networking")
    item(key = "advanced-cookies") {
        SettingRow("Clear cookies", "Signs you out of sites and forgets passed Cloudflare checks", onClick = {
            clearCookies()
            Toasts.show("Cookies cleared")
        })
    }
    item(key = "advanced-webview") {
        SettingRow("Clear WebView data", "The built-in browser's cookies and cache", onClick = {
            AppScope.launch {
                try {
                    withContext(Dispatchers.IO) { clearBrowserData() }
                    Toasts.show("WebView data cleared")
                } catch (e: Exception) {
                    Toasts.error("Error occurred while clearing", e)
                }
            }
        })
    }
    item(key = "advanced-doh") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val value by preferences.dohProvider().changes().collectAsState(preferences.dohProvider().get())
        ChoiceSetting(
            "DNS over HTTPS (DoH)",
            value,
            listOf(
                -1 to "Disabled",
                PREF_DOH_CLOUDFLARE to "Cloudflare",
                PREF_DOH_GOOGLE to "Google",
                PREF_DOH_ADGUARD to "AdGuard",
                PREF_DOH_QUAD9 to "Quad9",
                PREF_DOH_ALIDNS to "AliDNS",
                PREF_DOH_DNSPOD to "DNSPod",
                PREF_DOH_360 to "360",
                PREF_DOH_QUAD101 to "Quad 101",
                PREF_DOH_MULLVAD to "Mullvad",
                PREF_DOH_CONTROLD to "Control D",
                PREF_DOH_NJALLA to "Njalla",
                PREF_DOH_SHECAN to "Shecan",
            ),
        ) {
            if (it != value) {
                preferences.dohProvider().set(it)
                Toasts.show("Requires app restart to take effect")
            }
        }
    }
    item(key = "advanced-user-agent") { UserAgentPreference() }
    item(key = "advanced-reset-user-agent") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val userAgent by preferences.defaultUserAgent().changes().collectAsState(preferences.defaultUserAgent().get())
        SettingRow(
            "Reset default user agent string",
            enabled = userAgent != preferences.defaultUserAgent().defaultValue(),
            onClick = {
                preferences.defaultUserAgent().delete()
                Toasts.show("Requires app restart to take effect")
            },
        )
    }

    settingsGroup("Library ")
    item(key = "advanced-refresh-covers") {
        val running by refreshingCovers.collectAsState()
        SettingRow(
            "Refresh library covers",
            running,
            enabled = running == null,
            onClick = ::refreshLibraryCovers,
        )
    }
    item(key = "advanced-reset-viewer") {
        SettingRow("Reset per-series reader settings", "Resets reading mode and orientation of all series", onClick = {
            AppScope.launch {
                val success = runCatching { Injekt.get<ResetViewerFlags>().await() }.getOrDefault(false)
                Toasts.show(if (success) "All reader settings reset" else "Couldn't reset reader settings")
            }
        })
    }
    item(key = "advanced-update-titles") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            "Update library manga titles to match source",
            preferences.updateMangaTitles(),
            "Warning: if a manga is renamed, it will be removed from the download queue (if present).",
        )
    }
    item(key = "advanced-non-ascii") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            "Disallow non-ASCII filenames",
            preferences.disallowNonAsciiFilenames(),
            "Ensures compatibility with certain storage media that don't support Unicode. When this is enabled, you'll need " +
                "to manually rename source and manga folders by replacing non-ASCII characters with their lowercase UTF-8 " +
                "hexadecimal representations. Chapter files don't need to be renamed.",
        )
    }

    settingsGroup("Downloads ")
    item(key = "advanced-url-hash") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference(
            "Include chapter URL hash",
            preferences.includeChapterUrlHash(),
            "Append the first six characters of the chapter URL's MD5 hash to the chapter file or folder name.",
        )
    }
    item(key = "advanced-cleanup-downloads") { CleanupDownloadsPreference() }

    settingsGroup("Extensions")
    item(key = "advanced-revoke-trust") {
        SettingRow("Revoke trusted unknown extensions", "Extensions trusted on this computer must be trusted again", onClick = {
            Injekt.get<ExtensionTrust>().revokeAll()
            Toasts.show("Revoked trust for all unknown extensions")
        })
    }

    settingsGroup("Developer tools")
    item(key = "advanced-delegated") {
        val preferences = remember { Injekt.get<DelegateSourcePreferences>() }
        SwitchPreference(
            "Enable delegated sources",
            preferences.delegateSources(),
            "Apply Komikku enhancements to the following sources if they are installed: MangaDex, NHentai, Pururin, " +
                "8Muses, LANraragi. Requires app restart to take effect",
        )
    }
    // ZOUKAN -->
    item(key = "advanced-source-blacklist") {
        val preferences = remember { Injekt.get<eu.kanade.domain.source.service.SourcePreferences>() }
        SwitchPreference(
            "Enable source blacklist",
            preferences.enableSourceBlacklist(),
            "Hide extensions/sources that are incompatible with Zoukan. Force-restart app after changing.",
        )
    }
    // ZOUKAN <--
    item(key = "advanced-log-level") {
        val preferences = remember { Injekt.get<ExhPreferences>() }
        val level by preferences.logLevel(false).changes().collectAsState(preferences.logLevel(false).get())
        ChoiceSetting(
            "Log level",
            level,
            listOf(
                EHLogLevel.MINIMAL.ordinal to "Minimal (Critical errors only)",
                EHLogLevel.EXTRA.ordinal to "Extra (Log everything)",
                EHLogLevel.EXTREME.ordinal to "Extreme (Network inspection mode)",
            ),
            subtitle = "Changing this can impact app performance. Force-restart app after changing. Current value: " +
                listOf("Minimal", "Extra", "Extreme").getOrElse(level) { "Minimal" },
        ) {
            if (it != level) {
                preferences.logLevel(false).set(it)
                Toasts.show("Requires app restart to take effect")
            }
        }
    }
}

/** Android's "Default user agent string", an EditTextPreference checked by OkHttp's header rules. */
@Composable
private fun UserAgentPreference() {
    val preferences = remember { Injekt.get<NetworkPreferences>() }
    val userAgent by preferences.defaultUserAgent().changes().collectAsState(preferences.defaultUserAgent().get())
    var editing by remember { mutableStateOf(false) }
    SettingRow("Default user agent string", userAgent, onClick = { editing = true })
    if (!editing) return
    var value by remember { mutableStateOf(userAgent) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Default user agent string") },
        text = { OutlinedTextField(value, { value = it }, singleLine = false) },
        confirmButton = {
            TextButton(onClick = {
                try {
                    // OkHttp checks for valid values internally
                    Headers.Builder().add("User-Agent", value)
                    if (value != userAgent) {
                        preferences.defaultUserAgent().set(value)
                        Toasts.show("Requires app restart to take effect")
                    }
                    editing = false
                } catch (_: IllegalArgumentException) {
                    Toasts.show("Invalid user agent string")
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
}

/** Android's "Share crash logs": the environment, the crash reports and the log, saved to a file. */
@Composable
private fun CrashLogsPreference() {
    val window = LocalWindow.current?.window
    SettingRow("Share crash logs", "Saves error logs to a file for sharing with the developers", onClick = {
        val dialog = FileDialog(window as java.awt.Frame?, "Share crash logs", FileDialog.SAVE).apply {
            file = "komikku_crash_logs.txt"
            isVisible = true
        }
        val name = dialog.file ?: return@SettingRow
        val target = File(dialog.directory, name)
        AppScope.launch {
            try {
                withContext(Dispatchers.IO) { target.writeText(crashLogs(Injekt.get())) }
                Toasts.show("Saved crash logs to ${target.name}")
            } catch (e: Exception) {
                Toasts.error("Could not save the crash logs", e)
            }
        }
    })
}

/** As Android's CrashLogUtil: the device and app details, then the logs, oldest first. */
internal fun crashLogs(dirs: ZoukanDirs): String = buildString {
    CrashReporter.environment().forEach { (k, v) -> appendLine("$k: $v") }
    appendLine()
    CrashReporter.reports().reversed().forEach { report ->
        appendLine("===== ${report.name} =====")
        appendLine(runCatching { report.readText() }.getOrDefault(""))
    }
    // zoukan.3.log is the oldest of the rolled logs
    (listOf(File(dirs.logs, "zoukan.log")) + (1..3).map { File(dirs.logs, "zoukan.$it.log") })
        .filter { it.isFile }
        .reversed()
        .forEach { log ->
            appendLine("===== ${log.name} =====")
            appendLine(runCatching { log.readText() }.getOrDefault(""))
        }
}

/** Android's networkHelper.cookieJar.removeAll(), for the app's and Chromium's cookies. */
internal fun clearCookies() {
    (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    runCatching { Injekt.get<NetworkHelper>().cookieJar.removeAll() }
    deleteBrowserCookies()
}

private fun deleteBrowserCookies() {
    // Only when Chromium is running: getting the cookie manager would start it
    if (org.cef.CefApp.getState() != org.cef.CefApp.CefAppState.INITIALIZED) return
    runCatching { org.cef.network.CefCookieManager.getGlobalManager()?.deleteCookies("", "") }
}

/**
 * Android's "Clear WebView data": the built-in browser's cookies, then its cache and site data
 * (local storage, cached pages). Chromium holds some files while it runs; those stay until the
 * next time.
 */
private fun clearBrowserData() {
    deleteBrowserCookies()
    Injekt.get<ZoukanDirs>().browserCache.listFiles()?.forEach { it.deleteRecursively() }
}

/** Android's MetadataUpdateJob: each library entry's details and cover, again from its source. */
private val refreshingCovers = MutableStateFlow<String?>(null)

private fun refreshLibraryCovers() {
    if (refreshingCovers.value != null) return
    refreshingCovers.value = "Refreshing covers…"
    AppScope.launch {
        try {
            val favorites = Injekt.get<GetFavorites>().await()
            val refresh = Injekt.get<RefreshManga>()
            var failed = 0
            favorites.forEachIndexed { index, manga ->
                refreshingCovers.value = "Refreshing covers: ${index + 1} of ${favorites.size}"
                runCatching {
                    zoukan.desktop.ui.common.Images.forget(manga)
                    val updated = refresh(manga, fetchDetails = true, fetchChapters = false).manga
                    zoukan.desktop.ui.common.Images.forget(updated)
                }.onFailure { failed++ }
            }
            zoukan.desktop.ui.common.Images.loader.memoryCache?.clear()
            Toasts.show(if (failed == 0) "Library covers refreshed" else "Library covers refreshed, $failed failed")
        } finally {
            refreshingCovers.value = null
        }
    }
}
