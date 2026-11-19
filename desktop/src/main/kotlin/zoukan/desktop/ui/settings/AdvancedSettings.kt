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
import eu.kanade.domain.source.service.SourcePreferences
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
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import exh.log.EHLogLevel
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.browser.blocking.ContentBlockerPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.LocalWindow
import zoukan.desktop.ui.common.collectAsState
import zoukan.i18n.desktop.DMR
import java.io.File

@Composable
private fun rememberAdvancedController(): AdvancedSettingsController {
    val networkPreferences = remember { Injekt.get<NetworkPreferences>() }
    val store = remember { Injekt.get<PreferenceStore>() }
    val exhPreferences = remember { Injekt.get<ExhPreferences>() }
    val dirs = remember { Injekt.get<ZoukanDirs>() }
    return remember(networkPreferences, store, exhPreferences, dirs) {
        AdvancedSettingsController(
            networkPreferences = networkPreferences,
            contentBlockerPreferences = ContentBlockerPreferences(store),
            exhPreferences = exhPreferences,
            dirs = dirs,
        )
    }
}

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
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.pref_onboarding_guide), onClick = controller::reopenOnboarding)
    }

    settingsGroup(localized(MR.strings.label_data))
    item(key = "advanced-reindex") {
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.pref_invalidate_download_cache), localized(MR.strings.pref_invalidate_download_cache_summary), onClick = {
            controller.reindexDownloads()
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
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.pref_clear_cookies), localized(DMR.strings.signs_you_out_of_sites_and), onClick = {
            controller.clearCookies()
            Toasts.show(localized(MR.strings.cookies_cleared))
        })
    }
    item(key = "advanced-webview") {
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.pref_clear_webview_data), localized(DMR.strings.the_built_in_browsers_cookies_and), onClick = {
            AppScope.launch {
                controller.clearWebViewData()
                    .onSuccess { Toasts.show(localized(MR.strings.webview_data_deleted)) }
                    .onFailure { Toasts.error(localized(MR.strings.cache_delete_error), it) }
            }
        })
    }
    // ZOUKAN -->
    item(key = "advanced-content-blocker") {
        val preferences = remember { ContentBlockerPreferences(Injekt.get()) }
        SwitchPreference(
            localized(DMR.strings.block_ads_and_trackers),
            preferences.enabled(),
            localized(DMR.strings.block_ads_and_trackers_summary),
        )
    }
    item(key = "advanced-block-lists") { BlockListsPreference() }
    // ZOUKAN <--
    item(key = "advanced-doh") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val controller = rememberAdvancedController()
        val value by preferences.dohProvider().collectAsState()
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
                PREF_DOH_MULLVAD to "Mullvad",
                PREF_DOH_CONTROLD to "Control D",
                PREF_DOH_NJALLA to "Njalla",
            ),
        ) {
            if (controller.setDohProvider(it)) {
                Toasts.show(localized(MR.strings.requires_app_restart))
            }
        }
    }
    item(key = "advanced-user-agent") { UserAgentPreference() }
    item(key = "advanced-reset-user-agent") {
        val preferences = remember { Injekt.get<NetworkPreferences>() }
        val controller = rememberAdvancedController()
        val userAgent by preferences.defaultUserAgent().collectAsState()
        SettingRow(
            localized(MR.strings.pref_reset_user_agent_string),
            enabled = userAgent != preferences.defaultUserAgent().defaultValue(),
            onClick = {
                if (controller.resetUserAgent()) {
                    Toasts.show(localized(MR.strings.requires_app_restart))
                }
            },
        )
    }

    settingsGroup(localized(MR.strings.label_library), id = "advanced-library")
    item(key = "advanced-refresh-covers") {
        val controller = rememberAdvancedController()
        val running by controller.refreshingCoversState.collectAsState()
        SettingRow(
            localized(MR.strings.pref_refresh_library_covers),
            running,
            enabled = running == null,
            onClick = {
                AppScope.launch {
                    val failed = controller.refreshLibraryCovers() ?: return@launch
                    Toasts.show(
                        if (failed == 0) {
                            localized(DMR.strings.library_covers_refreshed)
                        } else {
                            localized(DMR.strings.library_covers_refreshed_failed, failed)
                        },
                    )
                }
            },
        )
    }
    item(key = "advanced-reset-viewer") {
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.pref_reset_viewer_flags), localized(MR.strings.pref_reset_viewer_flags_summary), onClick = {
            AppScope.launch {
                val success = controller.resetViewerFlags()
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

    settingsGroup(localized(MR.strings.pref_category_downloads), id = "advanced-downloads")
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
        val controller = rememberAdvancedController()
        SettingRow(localized(MR.strings.ext_revoke_trust), localized(DMR.strings.extensions_trusted_on_this_computer_must), onClick = {
            controller.revokeExtensionTrust()
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
        val preferences = remember { Injekt.get<SourcePreferences>() }
        SwitchPreference(
            localized(SYMR.strings.enable_source_blacklist),
            preferences.enableSourceBlacklist(),
            localized(DMR.strings.hide_extensions_sources_that_are_incompatible),
        )
    }
    // ZOUKAN <--
    item(key = "advanced-log-level") {
        val preferences = remember { Injekt.get<ExhPreferences>() }
        val controller = rememberAdvancedController()
        val level by preferences.logLevel(false).collectAsState()
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
            if (controller.setLogLevel(it)) {
                Toasts.show(localized(MR.strings.requires_app_restart))
            }
        }
    }
}

/** Android's "Default user agent string", an EditTextPreference checked by OkHttp's header rules. */
@Composable
private fun UserAgentPreference() {
    val preferences = remember { Injekt.get<NetworkPreferences>() }
    val controller = rememberAdvancedController()
    val userAgent by preferences.defaultUserAgent().collectAsState()
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
                when (controller.setUserAgent(value)) {
                    AdvancedSettingsController.UserAgentResult.Unchanged -> editing = false
                    AdvancedSettingsController.UserAgentResult.UpdatedRestartRequired -> {
                        Toasts.show(localized(MR.strings.requires_app_restart))
                        editing = false
                    }
                    AdvancedSettingsController.UserAgentResult.Invalid ->
                        Toasts.show(localized(MR.strings.error_user_agent_string_invalid))
                }
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Desktop only: the addresses of extra block lists for the built-in browser (see UserBlockLists). */
@Composable
private fun BlockListsPreference() {
    val preferences = remember { ContentBlockerPreferences(Injekt.get()) }
    val controller = rememberAdvancedController()
    val enabled by preferences.enabled().collectAsState()
    val urls by preferences.listUrls().collectAsState()
    var editing by remember { mutableStateOf(false) }
    SettingRow(
        localized(DMR.strings.extra_block_lists),
        localized(DMR.strings.extra_block_lists_summary),
        enabled = enabled,
        onClick = { editing = true },
    )
    if (!editing) return
    BlockListsDialog(urls, onDismiss = { editing = false }) { value ->
        controller.updateBlockLists(value)
        editing = false
    }
}

/** Edits the block list addresses, one per line; [onConfirm] gets the text as typed. */
@Composable
internal fun BlockListsDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.extra_block_lists)) },
        text = { OutlinedTextField(value, { value = it }, singleLine = false, minLines = 3) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(localized(MR.strings.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Android's "Share crash logs": the environment, the crash reports and the log, saved to a file. */
@Composable
private fun CrashLogsPreference() {
    val controller = rememberAdvancedController()
    val window = LocalWindow.current?.window
    SettingRow(localized(MR.strings.pref_dump_crash_logs), localized(MR.strings.pref_dump_crash_logs_summary), onClick = {
        val target = SystemEffects.current.pickFile(
            FileRequest(window, localized(MR.strings.pref_dump_crash_logs), save = true, fileName = "komikku_crash_logs.txt"),
        ) ?: return@SettingRow
        AppScope.launch {
            controller.saveCrashLogs(target)
                .onSuccess { Toasts.show(localized(DMR.strings.saved_crash_logs_to, target.name)) }
                .onFailure { Toasts.error(localized(DMR.strings.could_not_save_the_crash_logs), it) }
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
    AdvancedSettingsController.clearAppCookies()
    AdvancedSettingsController.deleteBrowserCookies()
}
