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
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_display_language_badge
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.action_reset
import tachiyomi.i18n.label_auto
import tachiyomi.i18n.other_source
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.artist_cg
import tachiyomi.i18n.sy.auto_update_restrictions
import tachiyomi.i18n.sy.cosplay
import tachiyomi.i18n.sy.disable_favorites_uploading
import tachiyomi.i18n.sy.doujinshi
import tachiyomi.i18n.sy.eh_image_quality
import tachiyomi.i18n.sy.entry_type_manga
import tachiyomi.i18n.sy.force_sync_state_reset
import tachiyomi.i18n.sy.game_cg
import tachiyomi.i18n.sy.ignore_sync_errors
import tachiyomi.i18n.sy.misc
import tachiyomi.i18n.sy.non_h
import tachiyomi.i18n.sy.pref_google_drive_sign_in
import tachiyomi.i18n.sy.sync_state_reset
import tachiyomi.i18n.sy.time_between_batches
import tachiyomi.i18n.sy.time_between_batches_12_hours
import tachiyomi.i18n.sy.time_between_batches_1_hour
import tachiyomi.i18n.sy.time_between_batches_24_hours
import tachiyomi.i18n.sy.time_between_batches_2_hours
import tachiyomi.i18n.sy.time_between_batches_3_hours
import tachiyomi.i18n.sy.time_between_batches_48_hours
import tachiyomi.i18n.sy.time_between_batches_6_hours
import tachiyomi.i18n.sy.time_between_batches_never
import tachiyomi.i18n.sy.translated
import tachiyomi.i18n.sy.use_hentai_at_home_option_2
import tachiyomi.i18n.sy.western
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.exh.EhGalleryUpdater
import zoukan.desktop.exh.EhLogin
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.manga.formatDateTime
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.adding_of_to_exhentai
import zoukan.i18n.desktop.adding_of_to_the_library
import zoukan.i18n.desktop.adds_the_built_in_e_hentai
import zoukan.i18n.desktop.any_client_recommended
import zoukan.i18n.desktop.apply_to_my_account
import zoukan.i18n.desktop.applying_the_e_hentai_settings_failed
import zoukan.i18n.desktop.asian_porn
import zoukan.i18n.desktop.browse_opens_on_the_galleries_of
import zoukan.i18n.desktop.charging_on_mains_power
import zoukan.i18n.desktop.checking_the_library
import zoukan.i18n.desktop.checks_up_to_50_library_galleries
import zoukan.i18n.desktop.chinese
import zoukan.i18n.desktop.cleaning_up
import zoukan.i18n.desktop.copy_these_cookies_of_e_hentai
import zoukan.i18n.desktop.could_not_sign_in
import zoukan.i18n.desktop.do_not_add_read_galleries_to
import zoukan.i18n.desktop.done_with_errors
import zoukan.i18n.desktop.download_the_original_files_which_cost
import zoukan.i18n.desktop.downloading_favorites
import zoukan.i18n.desktop.dutch
import zoukan.i18n.desktop.e_hentai_settings_applied
import zoukan.i18n.desktop.english
import zoukan.i18n.desktop.enhanced_e_hentai_view
import zoukan.i18n.desktop.failed_2
import zoukan.i18n.desktop.favorites_are_only_downloaded_from_exhentai
import zoukan.i18n.desktop.favorites_sync_comparing
import zoukan.i18n.desktop.favorites_sync_ready
import zoukan.i18n.desktop.favorites_sync_starting
import zoukan.i18n.desktop.forget_what_was_synced_the_next
import zoukan.i18n.desktop.french
import zoukan.i18n.desktop.front_page_categories
import zoukan.i18n.desktop.german
import zoukan.i18n.desktop.hide_galleries_in_these_languages_from
import zoukan.i18n.desktop.hungarian
import zoukan.i18n.desktop.igneous_optional
import zoukan.i18n.desktop.image_set
import zoukan.i18n.desktop.incognito
import zoukan.i18n.desktop.is_in_several_categories_keep_it
import zoukan.i18n.desktop.italian
import zoukan.i18n.desktop.japanese
import zoukan.i18n.desktop.korean
import zoukan.i18n.desktop.language_filtering
import zoukan.i18n.desktop.last_run_of_galleries_checked
import zoukan.i18n.desktop.no_donators_only
import zoukan.i18n.desktop.original
import zoukan.i18n.desktop.original_images
import zoukan.i18n.desktop.polish
import zoukan.i18n.desktop.portuguese
import zoukan.i18n.desktop.removing_galleries_from_exhentai
import zoukan.i18n.desktop.removing_of_from_the_library
import zoukan.i18n.desktop.reset_the_sync_state
import zoukan.i18n.desktop.rewrite
import zoukan.i18n.desktop.russian
import zoukan.i18n.desktop.show_gallery_details_rating_tags_uploader
import zoukan.i18n.desktop.show_the_japanese_title_of_a
import zoukan.i18n.desktop.sign_in_with_an_e_hentai
import zoukan.i18n.desktop.sign_in_with_cookies
import zoukan.i18n.desktop.sign_out
import zoukan.i18n.desktop.signed_in_exhentai_sources_are_in
import zoukan.i18n.desktop.signed_in_to_exhentai
import zoukan.i18n.desktop.signing_in_to_exhentai_failed
import zoukan.i18n.desktop.spanish
import zoukan.i18n.desktop.sync_favorites
import zoukan.i18n.desktop.syncing_category_names
import zoukan.i18n.desktop.tag_filtering_threshold
import zoukan.i18n.desktop.tag_watching_threshold
import zoukan.i18n.desktop.thai
import zoukan.i18n.desktop.the_categories_searches_show_by_default
import zoukan.i18n.desktop.the_next_sync_treats_every_favorite
import zoukan.i18n.desktop.these_cookies_did_not_sign_in
import zoukan.i18n.desktop.two_way_sync_of_your_exhentai
import zoukan.i18n.desktop.update_galleries_now
import zoukan.i18n.desktop.updating_galleries_failed
import zoukan.i18n.desktop.uploads_these_settings_to_your_e
import zoukan.i18n.desktop.use_cookies
import zoukan.i18n.desktop.use_japanese_titles
import zoukan.i18n.desktop.vietnamese
import zoukan.i18n.desktop.watched_list_by_default

/** Settings > E-Hentai: the built-in E-Hentai and ExHentai sources, as the Android app's E-Hentai settings. */
fun LazyListScope.ehSettings() {
    settingsGroup("E-Hentai")
    item(key = "eh-settings") { EhSettings() }
}

@Composable
private fun EhSettings() {
    val preferences = remember { Injekt.get<ExhPreferences>() }
    val login = remember { Injekt.get<EhLogin>() }
    val controller = remember(preferences, login) {
        EhSettingsController(
            preferences = preferences,
            loginAction = login::login,
            cookieLoginAction = login::loginWithCookies,
            logoutAction = login::logout,
            configureSiteAction = { EHConfigurator(Injekt.get<android.app.Application>()).configureAll() },
            runGalleryUpdateAction = { Injekt.get<EhGalleryUpdater>().run() },
            runFavoritesSyncAction = { Injekt.get<FavoritesSyncHelper>().runSync(AppScope) },
            resetFavoritesSyncAction = { Injekt.get<DeleteFavoriteEntries>().await() },
        )
    }
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
                exhentai -> TextButton(onClick = { controller.logout() }) { Text(localized(DMR.strings.sign_out)) }
                else -> Row {
                    TextButton(onClick = { cookieDialog = true }) { Text(localized(DMR.strings.use_cookies)) }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            controller.signIn()
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
        if (exhentai) EhSiteSettings(preferences, controller)
        EhUpdateSettings(preferences, controller)
        if (exhentai) EhFavoritesSync(preferences, controller)
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
                    EhCookieFields(memberId, { memberId = it }, passHash, { passHash = it }, igneous, { igneous = it })
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
                            when (controller.signInWithCookies(memberId, passHash, igneous)) {
                                EhSettingsController.CookieLoginResult.Success -> {
                                    cookieDialog = false
                                    Toasts.show(localized(DMR.strings.signed_in_to_exhentai))
                                }
                                EhSettingsController.CookieLoginResult.Rejected -> {
                                    error = localized(DMR.strings.these_cookies_did_not_sign_in)
                                }
                                is EhSettingsController.CookieLoginResult.Failed -> {
                                    error = localized(DMR.strings.could_not_sign_in)
                                }
                            }
                            busy = false
                        }
                    },
                ) { Text(localized(SYMR.strings.pref_google_drive_sign_in)) }
            },
            dismissButton = { TextButton(onClick = { cookieDialog = false }, enabled = !busy) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

/** The three cookies of the E-Hentai sign-in. */
@Composable
internal fun EhCookieFields(
    memberId: String,
    onMemberId: (String) -> Unit,
    passHash: String,
    onPassHash: (String) -> Unit,
    igneous: String,
    onIgneous: (String) -> Unit,
) {
    OutlinedTextField(memberId, { onMemberId(it.trim()) }, singleLine = true, label = { Text("ipb_member_id") })
    // ipb_pass_hash and igneous sign in to the account, so they are masked
    SecretTextField(passHash, { onPassHash(it.trim()) }, label = { Text("ipb_pass_hash") })
    SecretTextField(igneous, { onIgneous(it.trim()) }, label = { Text(localized(DMR.strings.igneous_optional)) })
}

/** The site settings Zoukan uploads to the account (EHConfigurator), as Android's E-Hentai settings. */
@Composable
private fun EhSiteSettings(preferences: ExhPreferences, controller: EhSettingsController) {
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
                    controller.applySiteSettings()
                        .onSuccess { Toasts.show(localized(DMR.strings.e_hentai_settings_applied)) }
                        .onFailure { Toasts.error(localized(DMR.strings.applying_the_e_hentai_settings_failed), it) }
                    busy = false
                }
            },
        ) { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) }
    }
    if (languages) LanguagesDialog(controller) { languages = false }
    if (categories) CategoriesDialog(controller) { categories = false }
}

private val EH_LANGUAGES = listOf(
    localized(DMR.strings.japanese), localized(DMR.strings.english), localized(DMR.strings.chinese), localized(DMR.strings.dutch), localized(DMR.strings.french), localized(DMR.strings.german), localized(DMR.strings.hungarian), localized(DMR.strings.italian), localized(DMR.strings.korean),
    localized(DMR.strings.polish), localized(DMR.strings.portuguese), localized(DMR.strings.russian), localized(DMR.strings.spanish), localized(DMR.strings.thai), localized(DMR.strings.vietnamese), "N/A", localized(MR.strings.other_source),
)

/**
 * The two E-Hentai filter settings in Android's format, which backups carry between the apps
 * (SettingsEhScreen's LanguageDialogState and FrontPageCategoriesDialogState). A value with lines
 * or parts missing (an older or hand-edited backup) reads them as off and is written back whole,
 * so a dialog never shows fewer rows than it can save.
 */
internal object EhSettingsFormat {
    const val LANGUAGES = 17
    const val CATEGORIES = 10

    /** Per language, original, translated and rewrite; Japanese has no "original" filter. */
    fun parseLanguages(value: String): List<List<Boolean>> {
        val lines = value.split("\n")
        return List(LANGUAGES) { row ->
            val parts = lines.getOrNull(row)?.split("*").orEmpty()
            List(3) { column -> !(row == 0 && column == 0) && parts.getOrNull(column)?.toBoolean() == true }
        }
    }

    /** "original*translated*rewrite" per language, one line each; Japanese's "original" is always false, as on Android. */
    fun formatLanguages(rows: List<List<Boolean>>): String = List(LANGUAGES) { row ->
        List(3) { column -> !(row == 0 && column == 0) && rows.getOrNull(row)?.getOrNull(column) == true }.joinToString("*")
    }.joinToString("\n")

    /** Whether each category shows on the front page; the setting stores "is hidden". */
    fun parseShownCategories(value: String): List<Boolean> {
        val hidden = value.split(",")
        return List(CATEGORIES) { hidden.getOrNull(it)?.toBoolean() != true }
    }

    fun formatShownCategories(shown: List<Boolean>): String =
        List(CATEGORIES) { (shown.getOrNull(it) == false).toString() }.joinToString(",")
}

/** "original*translated*rewrite" per language, one line each; Japanese has no "original" filter. */
@Composable
private fun LanguagesDialog(controller: EhSettingsController, onDismiss: () -> Unit) {
    val rows = remember { mutableStateListOf(*controller.loadLanguages().toTypedArray()) }
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
                    val row = rows[index]
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
                controller.saveLanguages(rows)
                onDismiss()
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

private val EH_CATEGORIES = listOf(
    localized(SYMR.strings.doujinshi), localized(SYMR.strings.entry_type_manga), localized(SYMR.strings.artist_cg), localized(SYMR.strings.game_cg), localized(SYMR.strings.western), localized(SYMR.strings.non_h), localized(DMR.strings.image_set), localized(SYMR.strings.cosplay), localized(DMR.strings.asian_porn), localized(SYMR.strings.misc),
)

/** Stored as "is hidden" per category, comma separated. */
@Composable
private fun CategoriesDialog(controller: EhSettingsController, onDismiss: () -> Unit) {
    val shown = remember { mutableStateListOf(*controller.loadShownCategories().toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.front_page_categories)) },
        text = {
            Column {
                EH_CATEGORIES.forEachIndexed { index, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(shown[index], onCheckedChange = { shown[index] = it })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.saveShownCategories(shown)
                onDismiss()
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

/** Gallery updates (EHentaiUpdateWorker on Android). */
@Composable
private fun EhUpdateSettings(preferences: ExhPreferences, controller: EhSettingsController) {
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
                    controller.updateGalleriesNow()
                        .onFailure { Toasts.error(localized(DMR.strings.updating_galleries_failed), it) }
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
private fun EhFavoritesSync(preferences: ExhPreferences, controller: EhSettingsController) {
    val sync = remember { Injekt.get<FavoritesSyncHelper>() }
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var reset by remember { mutableStateOf(false) }
    Column {
        SettingRow(
            localized(DMR.strings.sync_favorites),
            localized(DMR.strings.two_way_sync_of_your_exhentai, favoritesSyncText(status)),
            enabled = controller.canStartFavoritesSync(status),
            onClick = { controller.startFavoritesSync(status) },
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
                    scope.launch {
                        controller.resetFavoritesSyncState()
                            .onSuccess { Toasts.show(localized(SYMR.strings.sync_state_reset)) }
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
