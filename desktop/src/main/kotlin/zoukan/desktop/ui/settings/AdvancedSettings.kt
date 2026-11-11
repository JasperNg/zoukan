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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.compat.localized
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.ExtensionTrust
import zoukan.i18n.desktop.DMR
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
    settingsGroup(localized(MR.strings.pref_category_advanced))
    item(key = "advanced-crash-logs") { CrashLogsPreference() }
    // Android's "Onboarding guide": the first-run setup again
    item(key = "advanced-onboarding") {
        SettingRow(localized(MR.strings.pref_onboarding_guide), onClick = { zoukan.desktop.ui.onboarding.Onboarding.reopen.value = true })
    }

    settingsGroup(localized(MR.strings.label_data))
    item(key = "advanced-reindex") {
        SettingRow(localized(MR.strings.pref_invalidate_download_cache), localized(MR.strings.pref_invalidate_download_cache_summary), onClick = {
            Injekt.get<DownloadManager>().invalidateCache()
            Toasts.show(localized(MR.strings.download_cache_invalidated))
        })
    }
    item(key = "advanced-clear-database") {
        val navigator = LocalNavigator.currentOrThrow
        SettingRow(
            localized(MR.strings.pref_clear_database),
            localized(MR.strings.pref_clear_database_summary),
            onClick = { navigator.push(ClearDatabaseScreen) },
        )
    }

    settingsGroup(localized(MR.strings.label_network))
    item(key = "advanced-cookies") {
        SettingRow(localized(MR.strings.pref_clear_cookies), localized(DMR.strings.signs_you_out_of_sites_and), onClick = {
            clearCookies()
            Toasts.show(localized(MR.strings.cookies_cleared))
        })
    }
    item(key = "advanced-webview") {
        SettingRow(localized(MR.strings.pref_clear_webview_data), localized(DMR.strings.the_built_in_browsers_cookies_and), onClick = {
            AppScope.launch {
                try {
                    withContext(Dispatchers.IO) { clearBrowserData() }
                    Toasts.show(localized(MR.strings.webview_data_deleted))
                } catch (e: Exception) {
                    Toasts.error(localized(MR.strings.cache_delete_error), e)
                }
            }
        })
    }
    item(key = "advanced-doh") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val value by preferences.dohProvider().changes().collectAsState(preferences.dohProvider().get())
        ChoiceSetting(
            localized(MR.strings.pref_dns_over_https),
            value,
            listOf(
                -1 to localized(MR.strings.disabled),
                PREF_DOH_CLOUDFLARE to localized(KMR.strings.theme_cloudflare),
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
                Toasts.show(localized(MR.strings.requires_app_restart))
            }
        }
    }
    item(key = "advanced-user-agent") { UserAgentPreference() }
    item(key = "advanced-reset-user-agent") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val userAgent by preferences.defaultUserAgent().changes().collectAsState(preferences.defaultUserAgent().get())
        SettingRow(
            localized(MR.strings.pref_reset_user_agent_string),
            enabled = userAgent != preferences.defaultUserAgent().defaultValue(),
            onClick = {
                preferences.defaultUserAgent().delete()
                Toasts.show(localized(MR.strings.requires_app_restart))
            },
        )
    }

    settingsGroup(localized(MR.strings.label_library) + " ")
    item(key = "advanced-refresh-covers") {
        val running by refreshingCovers.collectAsState()
        SettingRow(
            localized(MR.strings.pref_refresh_library_covers),
            running,
            enabled = running == null,
            onClick = ::refreshLibraryCovers,
        )
    }
    item(key = "advanced-reset-viewer") {
        SettingRow(localized(MR.strings.pref_reset_viewer_flags), localized(MR.strings.pref_reset_viewer_flags_summary), onClick = {
            AppScope.launch {
                val success = runCatching { Injekt.get<ResetViewerFlags>().await() }.getOrDefault(false)
                Toasts.show(if (success) localized(MR.strings.pref_reset_viewer_flags_success) else localized(MR.strings.pref_reset_viewer_flags_error))
            }
        })
    }
    item(key = "advanced-update-titles") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            localized(MR.strings.pref_update_library_manga_titles),
            preferences.updateMangaTitles(),
            localized(MR.strings.pref_update_library_manga_titles_summary),
        )
    }
    item(key = "advanced-non-ascii") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            localized(MR.strings.pref_disallow_non_ascii_filenames),
            preferences.disallowNonAsciiFilenames(),
            localized(MR.strings.pref_disallow_non_ascii_filenames_details),
        )
    }

    settingsGroup(localized(MR.strings.pref_category_downloads) + " ")
    item(key = "advanced-url-hash") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference(
            localized(SYMR.strings.pref_include_chapter_url_hash),
            preferences.includeChapterUrlHash(),
            localized(SYMR.strings.pref_include_chapter_url_hash_desc),
        )
    }
    item(key = "advanced-cleanup-downloads") { CleanupDownloadsPreference() }

    settingsGroup(localized(MR.strings.label_extensions))
    item(key = "advanced-revoke-trust") {
        SettingRow(localized(MR.strings.ext_revoke_trust), localized(DMR.strings.extensions_trusted_on_this_computer_must), onClick = {
            Injekt.get<ExtensionTrust>().revokeAll()
            Toasts.show(localized(DMR.strings.revoked_trust_for_all_unknown_extensions))
        })
    }

    settingsGroup(localized(SYMR.strings.developer_tools))
    item(key = "advanced-delegated") {
        val preferences = remember { Injekt.get<DelegateSourcePreferences>() }
        SwitchPreference(
            localized(SYMR.strings.toggle_delegated_sources),
            preferences.delegateSources(),
            localized(DMR.strings.apply_komikku_enhancements_to_the_following),
        )
    }
    // ZOUKAN -->
    item(key = "advanced-source-blacklist") {
        val preferences = remember { Injekt.get<eu.kanade.domain.source.service.SourcePreferences>() }
        SwitchPreference(
            localized(SYMR.strings.enable_source_blacklist),
            preferences.enableSourceBlacklist(),
            localized(DMR.strings.hide_extensions_sources_that_are_incompatible),
        )
    }
    // ZOUKAN <--
    item(key = "advanced-log-level") {
        val preferences = remember { Injekt.get<ExhPreferences>() }
        val level by preferences.logLevel(false).changes().collectAsState(preferences.logLevel(false).get())
        ChoiceSetting(
            localized(SYMR.strings.log_level),
            level,
            listOf(
                EHLogLevel.MINIMAL.ordinal to localized(DMR.strings.minimal_critical_errors_only),
                EHLogLevel.EXTRA.ordinal to localized(DMR.strings.extra_log_everything),
                EHLogLevel.EXTREME.ordinal to localized(DMR.strings.extreme_network_inspection_mode),
            ),
            subtitle = localized(
                DMR.strings.log_level_summary,
                listOf(localized(SYMR.strings.log_minimal), localized(SYMR.strings.log_extra), localized(SYMR.strings.log_extreme)).getOrElse(level) { localized(SYMR.strings.log_minimal) },
            ),
        ) {
            if (it != level) {
                preferences.logLevel(false).set(it)
                Toasts.show(localized(MR.strings.requires_app_restart))
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
    SettingRow(localized(MR.strings.pref_user_agent_string), userAgent, onClick = { editing = true })
    if (!editing) return
    var value by remember { mutableStateOf(userAgent) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(localized(MR.strings.pref_user_agent_string)) },
        text = { OutlinedTextField(value, { value = it }, singleLine = false) },
        confirmButton = {
            TextButton(onClick = {
                try {
                    // OkHttp checks for valid values internally
                    Headers.Builder().add("User-Agent", value)
                    if (value != userAgent) {
                        preferences.defaultUserAgent().set(value)
                        Toasts.show(localized(MR.strings.requires_app_restart))
                    }
                    editing = false
                } catch (_: IllegalArgumentException) {
                    Toasts.show(localized(MR.strings.error_user_agent_string_invalid))
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Android's "Share crash logs": the environment, the crash reports and the log, saved to a file. */
@Composable
private fun CrashLogsPreference() {
    val window = LocalWindow.current?.window
    SettingRow(localized(MR.strings.pref_dump_crash_logs), localized(MR.strings.pref_dump_crash_logs_summary), onClick = {
        val dialog = FileDialog(window as java.awt.Frame?, localized(MR.strings.pref_dump_crash_logs), FileDialog.SAVE).apply {
            file = "komikku_crash_logs.txt"
            isVisible = true
        }
        val name = dialog.file ?: return@SettingRow
        val target = File(dialog.directory, name)
        AppScope.launch {
            try {
                withContext(Dispatchers.IO) { target.writeText(crashLogs(Injekt.get())) }
                Toasts.show(localized(DMR.strings.saved_crash_logs_to, target.name))
            } catch (e: Exception) {
                Toasts.error(localized(DMR.strings.could_not_save_the_crash_logs), e)
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
    refreshingCovers.value = localized(DMR.strings.refreshing_covers)
    AppScope.launch {
        try {
            val favorites = Injekt.get<GetFavorites>().await()
            val refresh = Injekt.get<RefreshManga>()
            var failed = 0
            favorites.forEachIndexed { index, manga ->
                refreshingCovers.value = localized(DMR.strings.refreshing_covers_of, index + 1, favorites.size)
                runCatching {
                    zoukan.desktop.ui.common.Images.forget(manga)
                    val updated = refresh(manga, fetchDetails = true, fetchChapters = false).manga
                    zoukan.desktop.ui.common.Images.forget(updated)
                }.onFailure { failed++ }
            }
            zoukan.desktop.ui.common.Images.loader.memoryCache?.clear()
            Toasts.show(if (failed == 0) localized(DMR.strings.library_covers_refreshed) else localized(DMR.strings.library_covers_refreshed_failed, failed))
        } finally {
            refreshingCovers.value = null
        }
    }
}
