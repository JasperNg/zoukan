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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.exh.EhGalleryUpdater
import zoukan.desktop.exh.EhLogin
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.manga.formatDateTime

/** Settings > E-Hentai: the built-in E-Hentai and ExHentai sources, as the Android app's E-Hentai settings. */
fun LazyListScope.ehSettings() {
    settingsGroup("E-Hentai")
    item(key = "eh-settings") { EhSettings() }
}

@Composable
private fun EhSettings() {
    val preferences = remember { Injekt.get<ExhPreferences>() }
    val login = remember { Injekt.get<EhLogin>() }
    val enabled by preferences.isHentaiEnabled().changes().collectAsState(preferences.isHentaiEnabled().get())
    val exhentai by preferences.enableExhentai().changes().collectAsState(preferences.enableExhentai().get())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var cookieDialog by remember { mutableStateOf(false) }

    Column {
        SwitchPreference(
            "E-Hentai",
            preferences.isHentaiEnabled(),
            "Adds the built-in E-Hentai sources to Browse",
        )
        if (!enabled) return@Column
        SettingRow(
            "ExHentai",
            if (exhentai) "Signed in. ExHentai sources are in Browse." else "Sign in with an E-Hentai account that can open ExHentai",
        ) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(24.dp))
                exhentai -> TextButton(onClick = { login.logout() }) { Text("Sign out") }
                else -> Row {
                    TextButton(onClick = { cookieDialog = true }) { Text("Use cookies") }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            runCatching { login.login() }
                                .onSuccess { if (it) Toasts.show("Signed in to ExHentai") }
                                .onFailure { Toasts.error("Signing in to ExHentai failed", it) }
                            busy = false
                        }
                    }) { Text("Sign in") }
                }
            }
        }
        SwitchPreference("Use Japanese titles", preferences.useJapaneseTitle(), "Show the Japanese title of a gallery when it has one")
        SwitchPreference("Original images", preferences.exhUseOriginalImages(), "Download the original files, which cost image limit")
        SwitchPreference(
            "Watched list by default",
            preferences.exhWatchedListDefaultState(),
            "Browse opens on the galleries of the tags you watch",
        )
        SwitchPreference("Enhanced E-Hentai view", preferences.enhancedEHentaiView(), "Show gallery details (rating, tags, uploader) on the entry")
        SwitchPreference("Incognito", preferences.ehIncognitoMode(), "Do not add read galleries to the history")
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
            title = { Text("Sign in with cookies") },
            text = {
                Column {
                    Text(
                        "Copy these cookies of e-hentai.org from a browser where you are signed in.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(memberId, { memberId = it.trim() }, singleLine = true, label = { Text("ipb_member_id") })
                    OutlinedTextField(passHash, { passHash = it.trim() }, singleLine = true, label = { Text("ipb_pass_hash") })
                    OutlinedTextField(igneous, { igneous = it.trim() }, singleLine = true, label = { Text("igneous (optional)") })
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
                                        Toasts.show("Signed in to ExHentai")
                                    } else {
                                        error = "These cookies did not sign in"
                                    }
                                }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { cookieDialog = false }, enabled = !busy) { Text("Cancel") } },
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
            "Image quality",
            preferences.imageQuality(),
            listOf("auto" to "Auto", "ovrs_2400" to "2400x", "ovrs_1600" to "1600x", "high" to "1280x", "med" to "980x", "low" to "780x"),
        )
        ChoicePreference(
            "Hentai@Home",
            preferences.useHentaiAtHome(),
            listOf(0 to "Any client (recommended)", 1 to "Default port clients only", 2 to "No (donators only)"),
        )
        SliderPreference("Tag filtering threshold", preferences.ehTagFilterValue(), -9999..0) { it.toString() }
        SliderPreference("Tag watching threshold", preferences.ehTagWatchingValue(), 0..9999) { it.toString() }
        SettingRow("Language filtering", "Hide galleries in these languages from searches", onClick = { languages = true })
        SettingRow("Front page categories", "The categories searches show by default", onClick = { categories = true })
        SettingRow(
            "Apply to my account",
            "Uploads these settings to your E-Hentai account profile, as the Android app does when you leave its settings",
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { EHConfigurator(Injekt.get<android.app.Application>()).configureAll() } }
                        .onSuccess { Toasts.show("E-Hentai settings applied") }
                        .onFailure { Toasts.error("Applying the E-Hentai settings failed", it) }
                    busy = false
                }
            },
        ) { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) }
    }
    if (languages) LanguagesDialog(preferences) { languages = false }
    if (categories) CategoriesDialog(preferences) { categories = false }
}

private val EH_LANGUAGES = listOf(
    "Japanese", "English", "Chinese", "Dutch", "French", "German", "Hungarian", "Italian", "Korean",
    "Polish", "Portuguese", "Russian", "Spanish", "Thai", "Vietnamese", "N/A", "Other",
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
        title = { Text("Language filtering") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScrollWithBar()) {
                Row {
                    Text("Language", Modifier.width(120.dp))
                    listOf("Original", "Translated", "Rewrite").forEach { Text(it, Modifier.width(90.dp)) }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val EH_CATEGORIES = listOf(
    "Doujinshi", "Manga", "Artist CG", "Game CG", "Western", "Non-H", "Image set", "Cosplay", "Asian porn", "Misc",
)

/** Stored as "is hidden" per category, comma separated. */
@Composable
private fun CategoriesDialog(preferences: ExhPreferences, onDismiss: () -> Unit) {
    val shown = remember { mutableStateListOf(*preferences.exhEnabledCategories().get().split(",").map { !it.toBoolean() }.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Front page categories") },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            "Time between update batches",
            preferences.exhAutoUpdateFrequency(),
            listOf(0 to "Never update galleries", 1 to "1 hour", 2 to "2 hours", 3 to "3 hours", 6 to "6 hours", 12 to "12 hours", 24 to "24 hours", 48 to "48 hours"),
        )
        // Android also offers Wi-Fi and unmetered network, which a desktop cannot tell; they stay in the setting
        val frequency by preferences.exhAutoUpdateFrequency().changes().collectAsState(preferences.exhAutoUpdateFrequency().get())
        MultiSelectPreference(
            "Auto update restrictions",
            preferences.exhAutoUpdateRequirements(),
            listOf(LibraryPreferences.DEVICE_CHARGING to "Charging (on mains power)"),
            enabled = frequency > 0,
        )
        val stats = updater.stats()
        SettingRow(
            "Update galleries now",
            stats?.let { "Last run ${formatDateTime(it.startTime)}: ${it.updateCount} of ${it.possibleUpdates} galleries checked" }
                ?: "Checks up to 50 library galleries for new versions",
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { updater.run() }.onFailure { Toasts.error("Updating galleries failed", it) }
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
            "Sync favorites",
            "Two-way sync of your ExHentai favorites with the library: ${favoritesSyncText(status)}",
            enabled = status is FavoritesSyncStatus.Idle || status is FavoritesSyncStatus.SyncError || status is FavoritesSyncStatus.CompleteWithErrors ||
                status is FavoritesSyncStatus.BadLibraryState,
            onClick = { sync.runSync(AppScope) },
        )
        SwitchPreference("Disable favorites uploading", preferences.exhReadOnlySync(), "Favorites are only downloaded from ExHentai")
        SwitchPreference("Ignore sync errors when possible", preferences.exhLenientSync())
        SettingRow("Force sync state reset", "Forget what was synced; the next sync compares everything again", onClick = { reset = true })
    }
    if (reset) {
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("Reset the sync state?") },
            text = { Text("The next sync treats every favorite as new.") },
            confirmButton = {
                TextButton(onClick = {
                    reset = false
                    scope.launch(Dispatchers.IO) {
                        Injekt.get<DeleteFavoriteEntries>().await()
                        Toasts.show("Sync state reset")
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } },
        )
    }
}

fun favoritesSyncText(status: FavoritesSyncStatus): String = when (status) {
    FavoritesSyncStatus.Idle -> "ready"
    FavoritesSyncStatus.Initializing -> "starting"
    FavoritesSyncStatus.Processing.VerifyingLibrary -> "checking the library"
    FavoritesSyncStatus.Processing.DownloadingFavorites -> "downloading favorites"
    FavoritesSyncStatus.Processing.CalculatingRemoteChanges, FavoritesSyncStatus.Processing.CalculatingLocalChanges -> "comparing"
    FavoritesSyncStatus.Processing.SyncingCategoryNames -> "syncing category names"
    is FavoritesSyncStatus.Processing.RemovingRemoteGalleries -> "removing ${status.galleryCount} galleries from ExHentai"
    is FavoritesSyncStatus.Processing.AddingGalleryToRemote -> "adding ${status.index} of ${status.total} to ExHentai"
    is FavoritesSyncStatus.Processing.RemovingGalleryFromLocal -> "removing ${status.index} of ${status.total} from the library"
    is FavoritesSyncStatus.Processing.AddingGalleryToLocal -> "adding ${status.index} of ${status.total} to the library"
    FavoritesSyncStatus.Processing.CleaningUp -> "cleaning up"
    is FavoritesSyncStatus.BadLibraryState.MangaInMultipleCategories ->
        "${status.mangaTitle} is in several categories (${status.categories.joinToString()}); keep it in one"
    is FavoritesSyncStatus.CompleteWithErrors -> "done with ${status.messages.size} errors"
    is FavoritesSyncStatus.SyncError -> "failed (${status::class.simpleName})"
}
