package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * The Android app's download groups (SettingsDownloadScreen): delete chapters, auto-download and
 * download ahead. [categories] includes the system "Default" category, as Android's lists do.
 */
fun LazyListScope.downloadBehaviorSettings(categories: List<Category>) {
    settingsGroup(localized(MR.strings.pref_category_delete_chapters))
    item(key = "downloads-remove-marked") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference(localized(MR.strings.pref_remove_after_marked_as_read), preferences.removeAfterMarkedAsRead())
    }
    item(key = "downloads-remove-read") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            localized(MR.strings.pref_remove_after_read),
            preferences.removeAfterReadSlots(),
            listOf(
                -1 to localized(MR.strings.disabled),
                0 to localized(MR.strings.last_read_chapter),
                1 to localized(MR.strings.second_to_last),
                2 to localized(MR.strings.third_to_last),
                3 to localized(MR.strings.fourth_to_last),
                4 to localized(MR.strings.fifth_to_last),
            ),
        )
    }
    item(key = "downloads-remove-bookmarked") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference(localized(MR.strings.pref_remove_bookmarked_chapters), preferences.removeBookmarkedChapters())
    }
    item(key = "downloads-remove-exclude") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        MultiSelectPreference(
            localized(MR.strings.pref_remove_exclude_categories),
            preferences.removeExcludeCategories(),
            categories.map { it.id.toString() to categoryName(it) },
        )
    }

    settingsGroup(localized(MR.strings.pref_category_auto_download))
    item(key = "downloads-new") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference(localized(MR.strings.pref_download_new), preferences.downloadNewChapters(), localized(DMR.strings.after_a_library_update))
    }
    item(key = "downloads-new-unread") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        val on by preferences.downloadNewChapters().changes().collectAsState(preferences.downloadNewChapters().get())
        val value by preferences.downloadNewUnreadChaptersOnly().changes().collectAsState(preferences.downloadNewUnreadChaptersOnly().get())
        SwitchSetting(localized(MR.strings.pref_download_new_unread_chapters_only), checked = value, enabled = on) {
            preferences.downloadNewUnreadChaptersOnly().set(it)
        }
    }
    item(key = "downloads-new-categories") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        val on by preferences.downloadNewChapters().changes().collectAsState(preferences.downloadNewChapters().get())
        CategoriesIncludeExcludePreference(
            localized(MR.strings.categories),
            localized(MR.strings.pref_download_new_categories_details),
            categories,
            preferences.downloadNewChapterCategories(),
            preferences.downloadNewChapterCategoriesExclude(),
            enabled = on,
        )
    }

    settingsGroup(localized(MR.strings.download_ahead))
    item(key = "downloads-ahead") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            localized(MR.strings.auto_download_while_reading),
            preferences.autoDownloadWhileReading(),
            listOf(0, 2, 3, 5, 10).map { it to if (it == 0) localized(MR.strings.disabled) else localized(DMR.strings.next_unread_chapters, it) },
        )
    }
    item(key = "downloads-ahead-info") {
        SettingRow("", localized(MR.strings.download_ahead_info))
    }

    // KMK's download cache renew interval
    settingsGroup(localized(KMR.strings.download_cache_renew_interval))
    item(key = "downloads-cache-renew") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            localized(KMR.strings.download_cache_renew_interval),
            preferences.downloadCacheRenewInterval(),
            listOf(-1 to localized(KMR.strings.download_cache_renew_interval_manual), 1 to localized(SYMR.strings.time_between_batches_1_hour), 2 to localized(SYMR.strings.time_between_batches_2_hours), 6 to localized(SYMR.strings.time_between_batches_6_hours), 12 to localized(SYMR.strings.time_between_batches_12_hours), 24 to localized(SYMR.strings.time_between_batches_24_hours)),
        )
    }
    item(key = "downloads-cache-renew-info") {
        SettingRow(
            "",
            localized(DMR.strings.how_often_the_app_reads_the),
        )
    }
}
