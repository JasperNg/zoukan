package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR

/**
 * The rest of Settings > Library, as the Android app's SettingsLibraryScreen: the default
 * category, the global update (interval, restrictions, categories, smart update) and behavior.
 * [categories] includes the system "Default" category, as Android's lists do.
 */
fun LazyListScope.librarySettings(categories: List<Category>) {
    item(key = "library-default-category") { DefaultCategoryPreference(categories) }
    item(key = "library-categorized") { CategorizedDisplayPreference() }

    settingsGroup(localized(MR.strings.pref_category_library_update))
    item(key = "library-update-interval") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        ChoicePreference(
            localized(MR.strings.pref_library_update_interval),
            preferences.autoUpdateInterval(),
            listOf(0 to localized(MR.strings.off), 12 to localized(MR.strings.update_12hour), 24 to localized(MR.strings.update_24hour), 48 to localized(MR.strings.update_48hour), 72 to localized(MR.strings.update_72hour), 168 to localized(MR.strings.update_weekly)),
        )
    }
    item(key = "library-update-restrictions") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val interval by preferences.autoUpdateInterval().changes().collectAsState(preferences.autoUpdateInterval().get())
        // Wi-Fi and unmetered network are Android's; they stay in the setting and are not shown
        MultiSelectPreference(
            localized(MR.strings.pref_library_update_restriction),
            preferences.autoUpdateDeviceRestrictions(),
            listOf(LibraryPreferences.DEVICE_CHARGING to localized(DMR.strings.charging_on_mains_power)),
            enabled = interval > 0,
        )
    }
    item(key = "library-update-categories") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        CategoriesIncludeExcludePreference(
            localized(MR.strings.categories),
            localized(MR.strings.pref_library_update_categories_details),
            categories,
            preferences.updateCategories(),
            preferences.updateCategoriesExclude(),
        )
    }
    // SY: what updating a source, status or tracking tab updates when the library is grouped
    item(key = "library-group-updates") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        ChoicePreference(
            localized(SYMR.strings.library_group_updates),
            preferences.groupLibraryUpdateType(),
            listOf(
                GroupLibraryMode.GLOBAL to localized(SYMR.strings.library_group_updates_global),
                GroupLibraryMode.ALL_BUT_UNGROUPED to localized(SYMR.strings.library_group_updates_all_but_ungrouped),
                GroupLibraryMode.ALL to localized(SYMR.strings.library_group_updates_all),
            ),
        )
    }
    item(key = "library-update-metadata") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            localized(MR.strings.pref_library_update_refresh_metadata),
            preferences.autoUpdateMetadata(),
            localized(MR.strings.pref_library_update_refresh_metadata_summary),
        )
    }
    // "Update library manga titles to match source" is in Advanced, as on Android
    item(key = "library-update-smart") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        MultiSelectPreference(
            localized(MR.strings.pref_library_update_smart_update),
            preferences.autoUpdateMangaRestrictions(),
            listOf(
                LibraryPreferences.MANGA_HAS_UNREAD to localized(MR.strings.pref_update_only_completely_read),
                LibraryPreferences.MANGA_NON_READ to localized(MR.strings.pref_update_only_started),
                LibraryPreferences.MANGA_NON_COMPLETED to localized(MR.strings.pref_update_only_non_completed),
                LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD to localized(MR.strings.pref_update_only_in_release_period),
            ),
        )
    }
    item(key = "library-update-badge") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(localized(MR.strings.pref_library_update_show_tab_badge), preferences.newShowUpdatesCount())
    }
    item(key = "library-update-now") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val last by preferences.lastUpdatedTimestamp().changes().collectAsState(preferences.lastUpdatedTimestamp().get())
        SettingRow(
            localized(DMR.strings.update_the_library_now),
            if (last > 0) localized(DMR.strings.last_update, zoukan.desktop.ui.manga.formatDateTime(last)) else localized(DMR.strings.never_updated),
            onClick = {
                if (!Injekt.get<LibraryUpdater>().start()) Toasts.show(localized(DMR.strings.a_library_update_is_already_running))
            },
        )
    }

    settingsGroup(localized(MR.strings.pref_behavior))
    item(key = "library-duplicates") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        MultiSelectPreference(
            localized(MR.strings.pref_mark_duplicate_read_chapter_read),
            preferences.markDuplicateReadChapterAsRead(),
            listOf(
                LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING to localized(MR.strings.pref_mark_duplicate_read_chapter_read_existing),
                LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW to localized(MR.strings.pref_mark_duplicate_read_chapter_read_new),
            ),
        )
    }
    item(key = "library-missing-chapters") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(localized(MR.strings.pref_hide_missing_chapter_indicators), preferences.hideMissingChapters())
    }
    item(key = "library-show-empty-categories-search") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(localized(KMR.strings.pref_show_empty_categories_search), preferences.showEmptyCategoriesSearch())
    }
    item(key = "library-fetch-metadata-on-add") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            localized(KMR.strings.pref_fetch_manga_metadata_on_add),
            preferences.fetchMetadataOnAdd(),
            localized(DMR.strings.automatically_fetches_the_metadata_of_a),
        )
    }
    item(key = "library-fetch-chapters-on-add") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            localized(KMR.strings.pref_fetch_manga_chapters_on_add),
            preferences.fetchChaptersOnAdd(),
            localized(DMR.strings.automatically_fetches_the_chapter_list_of),
        )
    }

    // SY's sorting settings
    settingsGroup(localized(DMR.strings.sorting))
    item(key = "library-sort-tags") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val tags by preferences.sortTagsForLibrary().changes().collectAsState(preferences.sortTagsForLibrary().get())
        val navigator = LocalNavigator.currentOrThrow
        SettingRow(
            localized(SYMR.strings.pref_tag_sorting),
            if (tags.size == 1) localized(DMR.strings.n_1_tag) else localized(DMR.strings.tags, tags.size),
            onClick = { navigator.push(zoukan.desktop.ui.library.SortTagScreen) },
        )
    }

    // ZOUKAN -->
    settingsGroup(localized(DMR.strings.chapter_swipe))
    item(key = "library-swipe-start") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        ChoicePreference(localized(DMR.strings.swipe_to_left_action), preferences.swipeToStartAction(), zoukan.desktop.ui.common.CHAPTER_SWIPE_ACTIONS)
    }
    item(key = "library-swipe-end") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        ChoicePreference(localized(DMR.strings.swipe_to_right_action), preferences.swipeToEndAction(), zoukan.desktop.ui.common.CHAPTER_SWIPE_ACTIONS)
    }
    // ZOUKAN <--
}

/** Android's "Default category": a category new entries go to, or ask every time. */
@Composable
private fun DefaultCategoryPreference(categories: List<Category>) {
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val choices = listOf(-1 to localized(MR.strings.default_category_summary)) + categories.map { it.id.toInt() to categoryName(it) }
    ChoicePreference(localized(MR.strings.default_category), preferences.defaultCategory(), choices)
}

/** Android's "Per-category settings for sort": turning it off resets each category's own sort. */
@Composable
private fun CategorizedDisplayPreference() {
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val value by preferences.categorizedDisplaySettings().changes().collectAsState(preferences.categorizedDisplaySettings().get())
    val scope = rememberCoroutineScope()
    SwitchSetting(localized(MR.strings.categorized_display_settings), checked = value) { on ->
        preferences.categorizedDisplaySettings().set(on)
        if (!on) scope.launch { runCatching { Injekt.get<tachiyomi.domain.category.interactor.ResetCategoryFlags>().await() } }
    }
}
