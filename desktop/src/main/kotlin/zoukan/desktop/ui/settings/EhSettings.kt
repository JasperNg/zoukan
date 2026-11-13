package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import exh.favorites.FavoritesSyncHelper
import exh.favorites.FavoritesSyncStatus
import exh.source.ExhPreferences
import exh.uconfig.EHConfigurator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.exh.EhGalleryUpdater
import zoukan.desktop.exh.EhLogin
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.manga.formatDateTime
import zoukan.i18n.desktop.DMR

/** Settings > E-Hentai: the built-in E-Hentai and ExHentai sources, as the Android app's E-Hentai settings. */
fun LazyListScope.ehSettings() {
    settingsGroup("E-Hentai")
    item(key = "eh-settings") { EhSettings() }
}

@Composable
private fun EhSettings() {
    val preferences = remember { Injekt.get<ExhPreferences>() }
    val login = remember { Injekt.get<EhLogin>() }
    val enabled by preferences.isHentaiEnabled().collectAsState()
    val exhentai by preferences.enableExhentai().collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var cookieDialog by remember { mutableStateOf(false) }

    Column {
        SwitchPreference(
            "E-Hentai",
            preferences.isHentaiEnabled(),
            localized(DMR.strings.adds_the_built_in_e_hentai),
        )
        if (!enabled) return@Column
        SettingRow(
            "ExHentai",
            if (exhentai) localized(DMR.strings.signed_in_exhentai_sources_are_in) else localized(DMR.strings.sign_in_with_an_e_hentai),
        ) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp))
                exhentai -> TextButton(onClick = { login.logout() }) { Text(localized(DMR.strings.sign_out)) }
                else -> Row {
                    TextButton(onClick = { cookieDialog = true }) { Text(localized(DMR.strings.use_cookies)) }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            runCatching { login.login() }
                                .onSuccess { if (it) Toasts.show(localized(DMR.strings.signed_in_to_exhentai)) }
                                .onFailure { Toasts.error(localized(DMR.strings.signing_in_to_exhentai_failed), it) }
                            busy = false
                        }
                    }) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
                }
            }
        }
        SwitchPreference(localized(DMR.strings.use_japanese_titles), preferences.useJapaneseTitle(), localized(DMR.strings.show_the_japanese_title_of_a))
        SwitchPreference(localized(DMR.strings.original_images), preferences.exhUseOriginalImages(), localized(DMR.strings.download_the_original_files_which_cost))
        SwitchPreference(
            localized(DMR.strings.watched_list_by_default),
            preferences.exhWatchedListDefaultState(),
            localized(DMR.strings.browse_opens_on_the_galleries_of),
        )
        SwitchPreference(localized(DMR.strings.enhanced_e_hentai_view), preferences.enhancedEHentaiView(), localized(DMR.strings.show_gallery_details_rating_tags_uploader))
        SwitchPreference(localized(DMR.strings.incognito), preferences.ehIncognitoMode(), localized(DMR.strings.do_not_add_read_galleries_to))
        if (exhentai) EhSiteSettings(preferences)
        EhUpdateSettings(preferences)
        if (exhentai) EhFavoritesSync(preferences)
    }

    if (cookieDialog) {
        var memberId by remember { mutableStateOf("") }
        var passHash by remember { mutableStateOf("") }
        var igneous by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) cookieDialog = false },
            title = { Text(localized(DMR.strings.sign_in_with_cookies)) },
            text = {
                Column {
                    Text(
                        localized(DMR.strings.copy_these_cookies_of_e_hentai),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(memberId, { memberId = it.trim() }, singleLine = true, label = { Text("ipb_member_id") })
                    OutlinedTextField(passHash, { passHash = it.trim() }, singleLine = true, label = { Text("ipb_pass_hash") })
                    OutlinedTextField(igneous, { igneous = it.trim() }, singleLine = true, label = { Text(localized(DMR.strings.igneous_optional)) })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && memberId.isNotEmpty() && passHash.isNotEmpty(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            runCatching { login.loginWithCookies(memberId, passHash, igneous) }
                                .onSuccess { ok ->
                                    if (ok) {
                                        cookieDialog = false
                                        Toasts.show(localized(DMR.strings.signed_in_to_exhentai))
                                    } else {
                                        error = localized(DMR.strings.these_cookies_did_not_sign_in)
                                    }
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            },
            dismissButton = { TextButton(onClick = { cookieDialog = false }, enabled = !busy) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

/** The site settings Zoukan uploads to the account (EHConfigurator), as Android's E-Hentai settings. */
@Composable
private fun EhSiteSettings(preferences: ExhPreferences) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(false) }
    Column {
        ChoicePreference(
            localized(SYMR.strings.eh_image_quality),
            preferences.imageQuality(),
            listOf("auto" to localized(MR.strings.label_auto), "ovrs_2400" to "2400x", "ovrs_1600" to "1600x", "high" to "1280x", "med" to "980x", "low" to "780x"),
        )
        ChoicePreference(
            "Hentai@Home",
            preferences.useHentaiAtHome(),
            listOf(0 to localized(DMR.strings.any_client_recommended), 1 to localized(SYMR.strings.use_hentai_at_home_option_2), 2 to localized(DMR.strings.no_donators_only)),
        )
        SliderPreference(localized(DMR.strings.tag_filtering_threshold), preferences.ehTagFilterValue(), -9999..0) { it.toString() }
        SliderPreference(localized(DMR.strings.tag_watching_threshold), preferences.ehTagWatchingValue(), 0..9999) { it.toString() }
        SettingRow(localized(DMR.strings.language_filtering), localized(DMR.strings.hide_galleries_in_these_languages_from), onClick = { languages = true })
        SettingRow(localized(DMR.strings.front_page_categories), localized(DMR.strings.the_categories_searches_show_by_default), onClick = { categories = true })
        SettingRow(
            localized(DMR.strings.apply_to_my_account),
            localized(DMR.strings.uploads_these_settings_to_your_e),
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { EHConfigurator(Injekt.get<android.app.Application>()).configureAll() } }
                        .onSuccess { Toasts.show(localized(DMR.strings.e_hentai_settings_applied)) }
                        .onFailure { Toasts.error(localized(DMR.strings.applying_the_e_hentai_settings_failed), it) }
                    busy = false
                }
            },
        ) { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) }
    }
    if (languages) LanguagesDialog(preferences) { languages = false }
    if (categories) CategoriesDialog(preferences) { categories = false }
}

private val EH_LANGUAGES = listOf(
    localized(DMR.strings.japanese), localized(DMR.strings.english), localized(DMR.strings.chinese), localized(DMR.strings.dutch), localized(DMR.strings.french), localized(DMR.strings.german), localized(DMR.strings.hungarian), localized(DMR.strings.italian), localized(DMR.strings.korean),
    localized(DMR.strings.polish), localized(DMR.strings.portuguese), localized(DMR.strings.russian), localized(DMR.strings.spanish), localized(DMR.strings.thai), localized(DMR.strings.vietnamese), "N/A", localized(MR.strings.other_source),
)

/** "original*translated*rewrite" per language, one line each; Japanese has no "original" filter. */
@Composable
private fun LanguagesDialog(preferences: ExhPreferences, onDismiss: () -> Unit) {
    val rows = remember {
        mutableStateListOf(
            *preferences.exhSettingsLanguages().get().split("\n").map { line -> line.split("*").map { it.toBoolean() } }.toTypedArray(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.language_filtering)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScrollWithBar()) {
                Row {
                    Text(localized(MR.strings.action_display_language_badge), Modifier.width(120.dp))
                    listOf(localized(DMR.strings.original), localized(SYMR.strings.translated), localized(DMR.strings.rewrite)).forEach { Text(it, Modifier.width(90.dp)) }
                }
                EH_LANGUAGES.forEachIndexed { index, language ->
                    val row = rows.getOrNull(index) ?: listOf(false, false, false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(language, Modifier.width(120.dp))
                        (0..2).forEach { column ->
                            Box(Modifier.width(90.dp)) {
                                if (index != 0 || column != 0) {
                                    Checkbox(row[column], onCheckedChange = { checked ->
                                        rows[index] = row.toMutableList().also { it[column] = checked }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.exhSettingsLanguages().set(rows.joinToString("\n") { it.joinToString("*") })
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

private val EH_CATEGORIES = listOf(
    localized(SYMR.strings.doujinshi), localized(SYMR.strings.entry_type_manga), localized(SYMR.strings.artist_cg), localized(SYMR.strings.game_cg), localized(SYMR.strings.western), localized(SYMR.strings.non_h), localized(DMR.strings.image_set), localized(SYMR.strings.cosplay), localized(DMR.strings.asian_porn), localized(SYMR.strings.misc),
)

/** Stored as "is hidden" per category, comma separated. */
@Composable
private fun CategoriesDialog(preferences: ExhPreferences, onDismiss: () -> Unit) {
    val shown = remember { mutableStateListOf(*preferences.exhEnabledCategories().get().split(",").map { !it.toBoolean() }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.front_page_categories)) },
        text = {
            Column {
                EH_CATEGORIES.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(shown.getOrElse(index) { true }, onCheckedChange = { if (index < shown.size) shown[index] = it })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.exhEnabledCategories().set(shown.joinToString(",") { (!it).toString() })
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Gallery updates (EHentaiUpdateWorker on Android). */
@Composable
private fun EhUpdateSettings(preferences: ExhPreferences) {
    val updater = remember { Injekt.get<EhGalleryUpdater>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column {
        ChoicePreference(
            localized(SYMR.strings.time_between_batches),
            preferences.exhAutoUpdateFrequency(),
            listOf(0 to localized(SYMR.strings.time_between_batches_never), 1 to localized(SYMR.strings.time_between_batches_1_hour), 2 to localized(SYMR.strings.time_between_batches_2_hours), 3 to localized(SYMR.strings.time_between_batches_3_hours), 6 to localized(SYMR.strings.time_between_batches_6_hours), 12 to localized(SYMR.strings.time_between_batches_12_hours), 24 to localized(SYMR.strings.time_between_batches_24_hours), 48 to localized(SYMR.strings.time_between_batches_48_hours)),
        )
        // Android also offers Wi-Fi and unmetered network, which a desktop cannot tell; they stay in the setting
        val frequency by preferences.exhAutoUpdateFrequency().collectAsState()
        MultiSelectPreference(
            localized(SYMR.strings.auto_update_restrictions),
            preferences.exhAutoUpdateRequirements(),
            listOf(LibraryPreferences.DEVICE_CHARGING to localized(DMR.strings.charging_on_mains_power)),
            enabled = frequency > 0,
        )
        val stats = updater.stats()
        SettingRow(
            localized(DMR.strings.update_galleries_now),
            stats?.let { localized(DMR.strings.last_run_of_galleries_checked, formatDateTime(it.startTime), it.updateCount, it.possibleUpdates) }
                ?: localized(DMR.strings.checks_up_to_50_library_galleries),
            enabled = !busy,
            onClick = {
                busy = true
                // In the app's scope, so leaving Settings doesn't stop the update partway
                val job = AppScope.launch {
                    try {
                        updater.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Toasts.error(localized(DMR.strings.updating_galleries_failed), e)
                    }
                }
                scope.launch {
                    job.join()
                    busy = false
                }
            },
        ) { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) }
    }
}

/** Favorites sync between the ExHentai favorites and the library categories. */
@Composable
private fun EhFavoritesSync(preferences: ExhPreferences) {
    val sync = remember { Injekt.get<FavoritesSyncHelper>() }
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var reset by remember { mutableStateOf(false) }
    Column {
        SettingRow(
            localized(DMR.strings.sync_favorites),
            localized(DMR.strings.two_way_sync_of_your_exhentai, favoritesSyncText(status)),
            enabled = status is FavoritesSyncStatus.Idle || status is FavoritesSyncStatus.SyncError || status is FavoritesSyncStatus.CompleteWithErrors ||
                status is FavoritesSyncStatus.BadLibraryState,
            onClick = { sync.runSync(AppScope) },
        )
        SwitchPreference(localized(SYMR.strings.disable_favorites_uploading), preferences.exhReadOnlySync(), localized(DMR.strings.favorites_are_only_downloaded_from_exhentai))
        SwitchPreference(localized(SYMR.strings.ignore_sync_errors), preferences.exhLenientSync())
        SettingRow(localized(SYMR.strings.force_sync_state_reset), localized(DMR.strings.forget_what_was_synced_the_next), onClick = { reset = true })
    }
    if (reset) {
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text(localized(DMR.strings.reset_the_sync_state)) },
            text = { Text(localized(DMR.strings.the_next_sync_treats_every_favorite)) },
            confirmButton = {
                TextButton(onClick = {
                    reset = false
                    scope.launch(Dispatchers.IO) {
                        Injekt.get<DeleteFavoriteEntries>().await()
                        Toasts.show(localized(SYMR.strings.sync_state_reset))
                    }
                }) { Text(localized(MR.strings.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { reset = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

fun favoritesSyncText(status: FavoritesSyncStatus): String = when (status) {
    FavoritesSyncStatus.Idle -> localized(DMR.strings.favorites_sync_ready)
    FavoritesSyncStatus.Initializing -> localized(DMR.strings.favorites_sync_starting)
    FavoritesSyncStatus.Processing.VerifyingLibrary -> localized(DMR.strings.checking_the_library)
    FavoritesSyncStatus.Processing.DownloadingFavorites -> localized(DMR.strings.downloading_favorites)
    FavoritesSyncStatus.Processing.CalculatingRemoteChanges, FavoritesSyncStatus.Processing.CalculatingLocalChanges -> localized(DMR.strings.favorites_sync_comparing)
    FavoritesSyncStatus.Processing.SyncingCategoryNames -> localized(DMR.strings.syncing_category_names)
    is FavoritesSyncStatus.Processing.RemovingRemoteGalleries -> localized(DMR.strings.removing_galleries_from_exhentai, status.galleryCount)
    is FavoritesSyncStatus.Processing.AddingGalleryToRemote -> localized(DMR.strings.adding_of_to_exhentai, status.index, status.total)
    is FavoritesSyncStatus.Processing.RemovingGalleryFromLocal -> localized(DMR.strings.removing_of_from_the_library, status.index, status.total)
    is FavoritesSyncStatus.Processing.AddingGalleryToLocal -> localized(DMR.strings.adding_of_to_the_library, status.index, status.total)
    FavoritesSyncStatus.Processing.CleaningUp -> localized(DMR.strings.cleaning_up)
    is FavoritesSyncStatus.BadLibraryState.MangaInMultipleCategories ->
        localized(DMR.strings.is_in_several_categories_keep_it, status.mangaTitle, status.categories.joinToString())
    is FavoritesSyncStatus.CompleteWithErrors -> localized(DMR.strings.done_with_errors, status.messages.size)
    is FavoritesSyncStatus.SyncError -> localized(DMR.strings.failed_2, status::class.simpleName)
}
