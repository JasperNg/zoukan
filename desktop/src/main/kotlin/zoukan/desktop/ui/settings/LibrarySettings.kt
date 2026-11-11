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
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.library.LibraryUpdater
import zoukan.desktop.ui.common.Toasts

/**
 * The rest of Settings > Library, as the Android app's SettingsLibraryScreen: the default
 * category, the global update (interval, restrictions, categories, smart update) and behavior.
 * [categories] includes the system "Default" category, as Android's lists do.
 */
fun LazyListScope.librarySettings(categories: List<Category>) {
    item(key = "library-default-category") { DefaultCategoryPreference(categories) }
    item(key = "library-categorized") { CategorizedDisplayPreference() }

    settingsGroup("Global update")
    item(key = "library-update-interval") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        ChoicePreference(
            "Automatic updates",
            preferences.autoUpdateInterval(),
            listOf(0 to "Off", 12 to "Every 12 hours", 24 to "Daily", 48 to "Every 2 days", 72 to "Every 3 days", 168 to "Weekly"),
        )
    }
    item(key = "library-update-restrictions") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val interval by preferences.autoUpdateInterval().changes().collectAsState(preferences.autoUpdateInterval().get())
        // Wi-Fi and unmetered network are Android's; they stay in the setting and are not shown
        MultiSelectPreference(
            "Automatic updates device restrictions",
            preferences.autoUpdateDeviceRestrictions(),
            listOf(LibraryPreferences.DEVICE_CHARGING to "Charging (on mains power)"),
            enabled = interval > 0,
        )
    }
    item(key = "library-update-categories") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        CategoriesIncludeExcludePreference(
            "Categories",
            "Entries in excluded categories will not be updated even if they are also in included categories.",
            categories,
            preferences.updateCategories(),
            preferences.updateCategoriesExclude(),
        )
    }
    item(key = "library-update-metadata") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference(
            "Automatically refresh metadata",
            preferences.autoUpdateMetadata(),
            "Check for new cover and details when updating library",
        )
    }
    // "Update library manga titles to match source" is in Advanced, as on Android
    item(key = "library-update-smart") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        MultiSelectPreference(
            "Smart update",
            preferences.autoUpdateMangaRestrictions(),
            listOf(
                LibraryPreferences.MANGA_HAS_UNREAD to "Skip entries with unread chapter(s)",
                LibraryPreferences.MANGA_NON_READ to "Skip unstarted entries",
                LibraryPreferences.MANGA_NON_COMPLETED to "Skip entries with \"Completed\" status",
                LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD to "Predict next release time",
            ),
        )
    }
    item(key = "library-update-badge") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference("Show unread count on Updates icon", preferences.newShowUpdatesCount())
    }
    item(key = "library-update-now") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val last by preferences.lastUpdatedTimestamp().changes().collectAsState(preferences.lastUpdatedTimestamp().get())
        SettingRow(
            "Update the library now",
            if (last > 0) "Last update: ${zoukan.desktop.ui.manga.formatDateTime(last)}" else "Never updated",
            onClick = {
                if (!Injekt.get<LibraryUpdater>().start()) Toasts.show("A library update is already running")
            },
        )
    }

    settingsGroup("Behavior")
    item(key = "library-duplicates") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        MultiSelectPreference(
            "Mark duplicate read chapter as read",
            preferences.markDuplicateReadChapterAsRead(),
            listOf(
                LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING to "After reading a chapter",
                LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW to "After fetching new chapter",
            ),
        )
    }
    item(key = "library-show-empty-categories-search") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        SwitchPreference("Show empty categories during search/filtering", preferences.showEmptyCategoriesSearch())
    }

    // SY's sorting settings
    settingsGroup("Sorting")
    item(key = "library-sort-tags") {
        val preferences = remember { Injekt.get<LibraryPreferences>() }
        val tags by preferences.sortTagsForLibrary().changes().collectAsState(preferences.sortTagsForLibrary().get())
        val navigator = LocalNavigator.currentOrThrow
        SettingRow(
            "Tag sorting tags",
            if (tags.size == 1) "1 tag" else "${tags.size} tags",
            onClick = { navigator.push(zoukan.desktop.ui.library.SortTagScreen) },
        )
    }
}

/** Android's "Default category": a category new entries go to, or ask every time. */
@Composable
private fun DefaultCategoryPreference(categories: List<Category>) {
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val choices = listOf(-1 to "Always ask") + categories.map { it.id.toInt() to categoryName(it) }
    ChoicePreference("Default category", preferences.defaultCategory(), choices)
}

/** Android's "Per-category settings for sort": turning it off resets each category's own sort. */
@Composable
private fun CategorizedDisplayPreference() {
    val preferences = remember { Injekt.get<LibraryPreferences>() }
    val value by preferences.categorizedDisplaySettings().changes().collectAsState(preferences.categorizedDisplaySettings().get())
    val scope = rememberCoroutineScope()
    SwitchSetting("Per-category settings for sort", checked = value) { on ->
        preferences.categorizedDisplaySettings().set(on)
        if (!on) scope.launch { runCatching { Injekt.get<tachiyomi.domain.category.interactor.ResetCategoryFlags>().await() } }
    }
}
