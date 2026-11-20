package zoukan.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_edit_categories
import tachiyomi.i18n.action_restore
import tachiyomi.i18n.action_search_settings
import tachiyomi.i18n.action_settings
import tachiyomi.i18n.browse
import tachiyomi.i18n.label_data_storage
import tachiyomi.i18n.label_library
import tachiyomi.i18n.pref_appearance_summary
import tachiyomi.i18n.pref_backup_summary
import tachiyomi.i18n.pref_browse_summary
import tachiyomi.i18n.pref_category_about
import tachiyomi.i18n.pref_category_advanced
import tachiyomi.i18n.pref_category_appearance
import tachiyomi.i18n.pref_category_downloads
import tachiyomi.i18n.pref_category_general
import tachiyomi.i18n.pref_category_reader
import tachiyomi.i18n.pref_category_security
import tachiyomi.i18n.pref_download_concurrent_pages
import tachiyomi.i18n.pref_download_concurrent_sources
import tachiyomi.i18n.pref_downloads_summary
import tachiyomi.i18n.pref_reader_summary
import tachiyomi.i18n.pref_restore_backup
import tachiyomi.i18n.pref_security_summary
import tachiyomi.i18n.pref_show_page_number
import tachiyomi.i18n.pref_tracking_summary
import tachiyomi.i18n.pref_viewer_type
import tachiyomi.i18n.save_chapter_as_cbz
import tachiyomi.i18n.split_tall_images
import tachiyomi.i18n.split_tall_images_summary
import tachiyomi.i18n.track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.app.AppScope
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Toasts
import zoukan.desktop.backup.BackupJobs
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.RestoreOutcome
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.common.ScreenHeader
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.snapshotFailedPrompt
import zoukan.desktop.ui.reader.ReaderSettingChoices
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.background
import zoukan.i18n.desktop.categories
import zoukan.i18n.desktop.categories_global_update
import zoukan.i18n.desktop.change_the_download_password
import zoukan.i18n.desktop.choose_the_storage_folder
import zoukan.i18n.desktop.clear_search
import zoukan.i18n.desktop.closing_the_window_keeps_zoukan_in
import zoukan.i18n.desktop.could_not_open_the_folder
import zoukan.i18n.desktop.e_hentai_settings
import zoukan.i18n.desktop.encryption
import zoukan.i18n.desktop.existing_downloads_keep_their_password
import zoukan.i18n.desktop.keep_running_in_the_tray
import zoukan.i18n.desktop.kept_in_use_the_same_password
import zoukan.i18n.desktop.mangadex_settings
import zoukan.i18n.desktop.new_cbz_downloads_are_encrypted_with
import zoukan.i18n.desktop.no_settings_match
import zoukan.i18n.desktop.page_scaling_paged_modes
import zoukan.i18n.desktop.password_protect_downloads
import zoukan.i18n.desktop.restore_sensitive_settings
import zoukan.i18n.desktop.restore_without
import zoukan.i18n.desktop.search_results
import zoukan.i18n.desktop.set_a_download_password_first_only_cbz
import zoukan.i18n.desktop.set_the_download_password
import zoukan.i18n.desktop.standard_zip_encryption
import zoukan.i18n.desktop.storage_folder_unavailable
import zoukan.i18n.desktop.tray_keyboard_shortcuts_crash_logs
import zoukan.i18n.desktop.webtoon_side_padding
import zoukan.i18n.desktop.zoukan
import java.io.File

/** The backups and restores started from Settings; the screens and the settings pages share this one. */
internal val backupJobs = BackupJobs(
    scope = AppScope,
    backups = { DesktopBackups(ZoukanRuntime.dirs.android) },
    sensitivePrompt = { count -> sensitiveSettingsChoice(count) },
    snapshotPrompt = { error -> snapshotFailedPrompt.continueWithout(error) },
    storePrompt = SwingStorePrompt,
)

/** What a backup or restore started from Settings is doing, shown in place of its buttons. */
internal val backupStatusFlow: StateFlow<String?> get() = backupJobs.status

/**
 * The settings pages, in the order and with the names of the Android app's settings list. Desktop
 * extras go on the page they belong to: the tray and keyboard shortcuts in Advanced, data sync
 * and data saver in Data and storage.
 */
enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    Appearance(localized(MR.strings.pref_category_appearance), localized(MR.strings.pref_appearance_summary), Icons.Outlined.Palette),
    Library(localized(MR.strings.label_library), localized(DMR.strings.categories_global_update), Icons.Outlined.CollectionsBookmark),
    Reader(localized(MR.strings.pref_category_reader), localized(MR.strings.pref_reader_summary), Icons.AutoMirrored.Outlined.ChromeReaderMode),
    Downloads(localized(MR.strings.pref_category_downloads), localized(MR.strings.pref_downloads_summary), Icons.Outlined.GetApp),
    Tracking(localized(MR.strings.track), localized(MR.strings.pref_tracking_summary), Icons.Outlined.Sync),
    Browse(localized(MR.strings.browse), localized(MR.strings.pref_browse_summary), Icons.Outlined.Explore),
    Data(localized(MR.strings.label_data_storage), localized(MR.strings.pref_backup_summary), Icons.Outlined.Storage),
    Security(localized(MR.strings.pref_category_security), localized(MR.strings.pref_security_summary), Icons.Outlined.Security),
    EHentai("E-Hentai", localized(DMR.strings.e_hentai_settings), Icons.Outlined.PhotoLibrary),
    MangaDex("MangaDex", localized(DMR.strings.mangadex_settings), Icons.AutoMirrored.Outlined.MenuBook),
    Advanced(localized(MR.strings.pref_category_advanced), localized(DMR.strings.tray_keyboard_shortcuts_crash_logs), Icons.Outlined.Code),
    About(localized(MR.strings.pref_category_about), localized(DMR.strings.zoukan, AppVersion.name), Icons.Outlined.Info),
}

/** Opens Settings on [category] the next time the Settings tab shows, or switches the open tab. */
fun openSettingsCategory(category: SettingsCategory) {
    SettingsScreenModel.openCategory(category)
}

/** How wide the list of pages is when [available] width is shared with the open page. */
internal fun settingsListWidth(available: Dp): Dp =
    if (available < Spacing.TwoPaneBreakpoint) {
        Spacing.SettingsListNarrowWidth.coerceAtMost(available / 2)
    } else {
        (available / 2).coerceAtMost(Spacing.SettingsListMaxWidth)
    }

/**
 * Settings, laid out as the Android app's tablet settings: the list of pages on the left (half the
 * width, at most 450dp; a fixed narrower width in small windows) and the open page on the right.
 */
@Composable
fun SettingsTab() {
    val model = remember { SettingsScreenModel() }
    DisposableEffect(model) {
        onDispose { model.onDispose() }
    }
    SettingsTab(model)
}

@Composable
fun SettingsTab(model: SettingsScreenModel) {
    DisposableEffect(model) {
        SettingsScreenModel.register(model)
        onDispose { SettingsScreenModel.unregister(model) }
    }
    val state by model.state.collectAsState()
    val preferences = remember { Injekt.get<DesktopPreferences>() }
    val getCategories = remember { Injekt.get<GetCategories>() }
    val categories by getCategories.subscribe().collectAsState(emptyList())
    val navigator = LocalNavigator.currentOrThrow
    val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
    val securityPreferences = remember { Injekt.get<SecurityPreferences>() }
    val cbzPassword = remember { Injekt.get<CbzPassword>() }
    var passwordDialog by remember { mutableStateOf(false) }
    var hasPassword by remember { mutableStateOf(cbzPassword.isSet()) }
    val pages = state.pages
    val selected = state.selectedCategory.takeIf { it in pages } ?: SettingsCategory.Appearance

    fun LazyListScope.settingsPage(page: SettingsCategory) {
        when (page) {
            SettingsCategory.Appearance -> appearanceSettings()
            SettingsCategory.Library -> {
                settingsGroup(localized(MR.strings.label_library))
                // Android's "Edit categories": the number of categories, and the screen that edits them
                item(key = "library-edit-categories") {
                    val count = categories.count { !it.isSystemCategory }
                    SettingRow(
                        localized(MR.strings.action_edit_categories),
                        localizedPlural(DMR.plurals.categories, count, count),
                        onClick = { navigator.push(CategoryScreen) },
                    )
                }
                librarySettings(categories)
            }
            SettingsCategory.Reader -> readerSettings(preferences)
            SettingsCategory.Downloads -> {
                downloadSettings(downloadPreferences, securityPreferences, hasPassword) { passwordDialog = true }
                downloadBehaviorSettings(categories)
            }
            SettingsCategory.Tracking -> trackingSettings()
            SettingsCategory.Browse -> browseSettings()
            SettingsCategory.Data -> {
                dataSettings()
                syncSettings()
                dataSaverSettings()
            }
            SettingsCategory.Security -> securitySettings()
            SettingsCategory.EHentai -> ehSettings()
            SettingsCategory.MangaDex -> mangaDexSettings()
            SettingsCategory.Advanced -> {
                settingsGroup(localized(MR.strings.pref_category_general))
                item(key = "advanced-tray") {
                    SwitchPreference(
                        localized(DMR.strings.keep_running_in_the_tray),
                        preferences.closeToTray(),
                        localized(DMR.strings.closing_the_window_keeps_zoukan_in),
                    )
                }
                shortcutSettings()
                advancedSettings()
            }
            SettingsCategory.About -> aboutSettings()
        }
    }

    val query = state.searchQuery
    val searching = query.isNotBlank()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listWidth = settingsListWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            Surface(color = palerSurface(), modifier = Modifier.width(listWidth).fillMaxHeight()) {
                SettingsCategoryList(
                    pages = pages,
                    selected = if (searching) null else selected,
                    searchQuery = query,
                    onSearchQueryChange = model::setSearchQuery,
                    onSelect = model::selectCategory,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (searching) {
                    ScreenHeader(localized(DMR.strings.search_results), showBack = false)
                    SearchResults(
                        results = state.searchResults,
                        query = query,
                        onOpen = model::openSearchResult,
                    )
                } else {
                    ScreenHeader(selected.title, showBack = false)
                    // A new list for each page, so each page opens at its top
                    key(selected) {
                        val listState = rememberLazyListState()
                        val ids = remember { mutableListOf<Any>() }
                        val highlight = state.highlightedItem?.takeIf { it.first == selected }?.second
                        LazyColumn(Modifier.fillMaxSize().widthIn(max = Spacing.SettingsPageMaxWidth), state = listState) {
                            ids.clear()
                            HighlightScope(this, highlight, ids).settingsPage(selected)
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                        // Opened from search: scroll to the setting, which flashes
                        LaunchedEffect(highlight) {
                            if (highlight != null) {
                                // The list's items are known once it has been laid out
                                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                                ids.indexOf(highlight).takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
                                delay(2000)
                                model.clearHighlight()
                            }
                        }
                    }
                }
            }
        }
    }

    if (passwordDialog) {
        DownloadPasswordDialog(cbzPassword, securityPreferences.passwordProtectDownloads(), hasPassword, { hasPassword = it }) {
            passwordDialog = false
        }
    }
}

@Composable
private fun SearchResults(
    results: List<SettingsSearchEntry>,
    query: String,
    onOpen: (SettingsSearchEntry) -> Unit,
) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            Text(
                localized(DMR.strings.no_settings_match, query.trim()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().widthIn(max = Spacing.SettingsPageMaxWidth), state = state) {
                itemsIndexed(
                    results,
                    key = { index, entry -> "${entry.category.name}/$index/${entry.title}" },
                ) { _, entry ->
                    SearchResultRow(entry) { onOpen(entry) }
                }
                item(key = "search-end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** The settings list on the left: one row per page, the open page highlighted, as on Android. */
@Composable
private fun SettingsCategoryList(
    pages: List<SettingsCategory>,
    selected: SettingsCategory?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelect: (SettingsCategory) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // A back button when More opened Settings as its own screen
        ScreenHeader(localized(MR.strings.action_settings), color = Color.Transparent)
        SearchField(searchQuery, onSearchQueryChange)
        LazyColumn(Modifier.fillMaxSize()) {
            items(pages, key = { it.name }) { page ->
                val isSelected = page == selected
                val contentColor = if (isSelected) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(Corners.ExtraLarge)
                            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                            // Selectable, so a screen reader says which page is open
                            .selectable(selected = isSelected) { onSelect(page) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, end = 24.dp).size(24.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(page.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), maxLines = 2)
                            Text(
                                page.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The background of the settings list: the surface made a little darker in dark themes and a
 * little lighter in light ones, as the Android app's tablet settings list.
 */
@Composable
private fun palerSurface(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return remember(surface) { palerSurface(surface) }
}

internal fun palerSurface(surface: Color): Color {
    val hsl = FloatArray(3)
    val r = surface.red
    val g = surface.green
    val b = surface.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val d = max - min
    val saturation = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d).mod(6f))
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    hsl[0] = hue
    hsl[1] = saturation.coerceIn(0f, 1f)
    // Android tells dark from light by the system setting; the theme's own surface is the better guide here
    hsl[2] = (if (lightness < 0.5f) lightness - 0.05f else lightness + 0.02f).coerceIn(0f, 1f)
    return Color.hsl(hsl[0], hsl[1], hsl[2], surface.alpha)
}

/** Settings > Reader. */
internal fun LazyListScope.readerSettings(preferences: DesktopPreferences) {
    settingsGroup(localized(MR.strings.pref_category_reader))
    item(key = "reader-default-mode") {
        ChoicePreference(
            localized(MR.strings.pref_viewer_type),
            preferences.defaultReadingMode(),
            ReadingModes.all.map { it to ReadingModes.label(it) },
        )
    }
    item(key = "reader-image-scale-type") { ChoicePreference(localized(DMR.strings.page_scaling_paged_modes), preferences.imageScaleType(), FitModes.all.map { it to FitModes.label(it) }) }
    item(key = "reader-theme") {
        ChoicePreference(localized(DMR.strings.background), preferences.readerTheme(), ReaderSettingChoices.themes)
    }
    item(key = "reader-webtoon-side-padding") { SliderPreference(localized(DMR.strings.webtoon_side_padding), preferences.webtoonSidePadding(), 0..25) { "$it%" } }
    item(key = "reader-show-page-number") { SwitchPreference(localized(MR.strings.pref_show_page_number), preferences.showPageNumber()) }
    // ZOUKAN -->
    readerBehaviourSettings(preferences)
    // ZOUKAN <--
}

/** Settings > Downloads: the format, concurrency and download password. */
private fun LazyListScope.downloadSettings(
    downloadPreferences: DownloadPreferences,
    securityPreferences: SecurityPreferences,
    hasPassword: Boolean,
    onEditPassword: () -> Unit,
) {
    settingsGroup(localized(MR.strings.pref_category_downloads))
    // ZOUKAN: the storage location and its folders are in Data and storage, as on Android
    item(key = "downloads-cbz") { SwitchPreference(localized(MR.strings.save_chapter_as_cbz), downloadPreferences.saveChaptersAsCBZ()) }
    item(key = "downloads-split-tall") { SwitchPreference(localized(MR.strings.split_tall_images), downloadPreferences.splitTallImages(), localized(MR.strings.split_tall_images_summary)) }
    item(key = "downloads-concurrent-sources") { SliderPreference(localized(MR.strings.pref_download_concurrent_sources), downloadPreferences.parallelSourceLimit(), 1..10) { "$it" } }
    item(key = "downloads-concurrent-pages") { SliderPreference(localized(MR.strings.pref_download_concurrent_pages), downloadPreferences.parallelPageLimit(), 1..15) { "$it" } }
    // ZOUKAN: "Include chapter URL hash" is in Advanced, as on Android
    item(key = "downloads-password-protect") {
        SwitchPreference(
            localized(DMR.strings.password_protect_downloads),
            securityPreferences.passwordProtectDownloads(),
            if (hasPassword) localized(DMR.strings.new_cbz_downloads_are_encrypted_with) else localized(DMR.strings.set_a_download_password_first_only_cbz),
            // As Android: nothing to encrypt with until a password is set
            enabled = hasPassword,
        )
    }
    item(key = "downloads-set-password") {
        SettingRow(
            if (hasPassword) localized(DMR.strings.change_the_download_password) else localized(DMR.strings.set_the_download_password),
            localized(DMR.strings.kept_in_use_the_same_password, Injekt.get<zoukan.desktop.secrets.SecretStore>().name) +
                if (hasPassword) " " + localized(DMR.strings.existing_downloads_keep_their_password) else "",
            onClick = onEditPassword,
        )
    }
    item(key = "downloads-encryption") {
        ChoicePreference(
            localized(DMR.strings.encryption),
            securityPreferences.encryptionType(),
            listOf(
                SecurityPreferences.EncryptionType.AES_256 to "AES-256",
                SecurityPreferences.EncryptionType.AES_128 to "AES-128",
                SecurityPreferences.EncryptionType.ZIP_STANDARD to localized(DMR.strings.standard_zip_encryption),
            ),
        )
    }
}

/** Writes a backup of what [options] picks (Create backup's choices) to [file]. */
internal fun createBackup(file: File, options: BackupOptions) = backupJobs.create(file, options)

/**
 * Restores what [options] picks (Restore backup's choices) from [file]. Returns null, and does
 * nothing, while another backup or restore runs; otherwise how the restore ends.
 */
internal fun restoreBackup(file: File, options: RestoreOptions): Deferred<RestoreOutcome>? = backupJobs.restore(file, options)

/** How a restore decides about security settings and logins; tests replace the native dialog. */
internal var sensitiveSettingsChoice: (count: Int) -> Boolean? = ::confirmSensitive

/**
 * Asks whether a restore may also write the [count] security settings and logins a backup holds.
 * True writes them, false restores without them, null cancels the restore.
 */
private fun confirmSensitive(count: Int): Boolean? {
    val message = localized(DMR.strings.restore_sensitive_settings, count)
    var choice = javax.swing.JOptionPane.CANCEL_OPTION
    val ask = Runnable {
        choice = javax.swing.JOptionPane.showOptionDialog(
            null,
            message,
            localized(MR.strings.pref_restore_backup),
            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf(localized(MR.strings.action_restore), localized(DMR.strings.restore_without), localized(MR.strings.action_cancel)),
            localized(DMR.strings.restore_without),
        )
    }
    if (javax.swing.SwingUtilities.isEventDispatchThread()) ask.run() else javax.swing.SwingUtilities.invokeAndWait(ask)
    return when (choice) {
        0 -> true
        1 -> false
        else -> null
    }
}

/** A folder picker: the native one on macOS, Swing's on Windows (AWT's FileDialog picks only files there). */
internal fun chooseFolder(window: java.awt.Window?, start: File): File? =
    SystemEffects.current.pickFolder(window, localized(DMR.strings.choose_the_storage_folder), start)

fun openFolder(folder: File) {
    AppScope.launch {
        withContext(Dispatchers.IO) {
            // A folder under a missing storage folder is not made on the system disk
            val storage = runCatching { Injekt.get<zoukan.desktop.storage.ZoukanStorage>() }.getOrNull()
            if (storage != null && storage.isMissing(folder)) {
                Toasts.show(localized(DMR.strings.storage_folder_unavailable, storage.root.path))
            } else {
                folder.mkdirs()
                runCatching { SystemEffects.current.openFolder(folder) }.onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_folder), it) }
            }
        }
    }
}

/** The search field over the settings list, as the search button of the Android app's settings. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    // Ctrl/Cmd + F puts the cursor here, as in the other screens' search boxes
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    zoukan.desktop.ui.OnShortcut { action ->
        action == zoukan.desktop.ui.Shortcuts.Action.Find && runCatching { focus.requestFocus() }.isSuccess
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(localized(MR.strings.action_search_settings)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, contentDescription = localized(DMR.strings.clear_search)) }
            }
        },
        shape = Corners.ExtraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("settings-search")
            .focusRequester(focus)
            // Escape clears the search before it goes back
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                    onQueryChange("")
                    true
                } else {
                    false
                }
            },
    )
}
