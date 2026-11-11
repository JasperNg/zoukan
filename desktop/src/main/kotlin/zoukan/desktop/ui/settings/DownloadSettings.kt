package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The Android app's download groups (SettingsDownloadScreen): delete chapters, auto-download and
 * download ahead. [categories] includes the system "Default" category, as Android's lists do.
 */
fun LazyListScope.downloadBehaviorSettings(categories: List<Category>) {
    settingsGroup("Delete chapters")
    item(key = "downloads-remove-marked") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference("After manually marked as read", preferences.removeAfterMarkedAsRead())
    }
    item(key = "downloads-remove-read") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            "After reading automatically delete",
            preferences.removeAfterReadSlots(),
            listOf(
                -1 to "Disabled",
                0 to "Last read chapter",
                1 to "Second to last read chapter",
                2 to "Third to last read chapter",
                3 to "Fourth to last read chapter",
                4 to "Fifth to last read chapter",
            ),
        )
    }
    item(key = "downloads-remove-bookmarked") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference("Allow deleting bookmarked chapters", preferences.removeBookmarkedChapters())
    }
    item(key = "downloads-remove-exclude") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        MultiSelectPreference(
            "Excluded categories",
            preferences.removeExcludeCategories(),
            categories.map { it.id.toString() to categoryName(it) },
        )
    }

    settingsGroup("Auto-download")
    item(key = "downloads-new") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        SwitchPreference("Download new chapters", preferences.downloadNewChapters(), "After a library update")
    }
    item(key = "downloads-new-unread") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        val on by preferences.downloadNewChapters().changes().collectAsState(preferences.downloadNewChapters().get())
        val value by preferences.downloadNewUnreadChaptersOnly().changes().collectAsState(preferences.downloadNewUnreadChaptersOnly().get())
        SwitchSetting("Skip downloading duplicate read chapters", checked = value, enabled = on) {
            preferences.downloadNewUnreadChaptersOnly().set(it)
        }
    }
    item(key = "downloads-new-categories") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        val on by preferences.downloadNewChapters().changes().collectAsState(preferences.downloadNewChapters().get())
        CategoriesIncludeExcludePreference(
            "Categories",
            "Entries in excluded categories will not be downloaded even if they are also in included categories.",
            categories,
            preferences.downloadNewChapterCategories(),
            preferences.downloadNewChapterCategoriesExclude(),
            enabled = on,
        )
    }

    settingsGroup("Download ahead")
    item(key = "downloads-ahead") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            "Auto download while reading",
            preferences.autoDownloadWhileReading(),
            listOf(0, 2, 3, 5, 10).map { it to if (it == 0) "Disabled" else "Next $it unread chapters" },
        )
    }
    item(key = "downloads-ahead-info") {
        SettingRow("", "Only works if the current chapter + the next one are already downloaded.")
    }

    // KMK's download cache renew interval
    settingsGroup("Download cache renew interval")
    item(key = "downloads-cache-renew") {
        val preferences = remember { Injekt.get<DownloadPreferences>() }
        ChoicePreference(
            "Download cache renew interval",
            preferences.downloadCacheRenewInterval(),
            listOf(-1 to "Manual", 1 to "1 hour", 2 to "2 hours", 6 to "6 hours", 12 to "12 hours", 24 to "24 hours"),
        )
    }
    item(key = "downloads-cache-renew-info") {
        SettingRow(
            "",
            "How often the app reads the download folders again to notice chapters added or removed outside the app. " +
                "With Manual, use Settings > Advanced > Reindex downloads.",
        )
    }
}
