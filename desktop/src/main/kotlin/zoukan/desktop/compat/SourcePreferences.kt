// ZOUKAN -->
package eu.kanade.domain.source.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray

/**
 * The part of the Android app's SourcePreferences the shared code reads, with the same keys (the
 * desktop's other source settings are in DesktopPreferences, also under the Android keys).
 */
class SourcePreferences(private val preferenceStore: PreferenceStore) {
    fun enabledLanguages() = preferenceStore.getStringSet("source_languages", setOf("all", "en"))

    fun incognitoExtensions() = preferenceStore.getStringSet("incognito_extensions", emptySet())

    fun disabledSources() = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    fun relatedMangas() = preferenceStore.getBoolean("related_mangas", true)

    fun preferredMangaDexId() = preferenceStore.getString("preferred_mangaDex_id", "0")

    fun mangadexSyncToLibraryIndexes() = preferenceStore.getStringSet("pref_mangadex_sync_to_library_indexes", emptySet())

    // The SY data saver (exh.util.DataSaver), Settings > Data saver
    fun dataSaver() = preferenceStore.getEnum("data_saver", DataSaver.NONE)

    fun dataSaverIgnoreJpeg() = preferenceStore.getBoolean("ignore_jpeg", false)

    fun dataSaverIgnoreGif() = preferenceStore.getBoolean("ignore_gif", true)

    fun dataSaverImageQuality() = preferenceStore.getInt("data_saver_image_quality", 80)

    fun dataSaverImageFormatJpeg() = preferenceStore.getBoolean("data_saver_image_format_jpeg", false)

    fun dataSaverServer() = preferenceStore.getString("data_saver_server", "")

    fun dataSaverColorBW() = preferenceStore.getBoolean("data_saver_color_bw", false)

    fun dataSaverExcludedSources() = preferenceStore.getStringSet("data_saver_excluded", emptySet())

    fun dataSaverDownloader() = preferenceStore.getBoolean("data_saver_downloader", true)

    enum class DataSaver {
        NONE,
        BANDWIDTH_HERO,
        WSRV_NL,
    }

    fun pinnedSources() = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    // Settings > Browse
    fun showNsfwSource() = preferenceStore.getBoolean("show_nsfw_source", true)

    fun hideInLibraryItems() = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    fun hideInLibraryFeedItems() = preferenceStore.getBoolean("feed_hide_in_library_items", false)

    fun sourcesTabCategories() = preferenceStore.getStringSet("sources_tab_categories", mutableSetOf())

    fun sourcesTabCategoriesFilter() = preferenceStore.getBoolean("sources_tab_categories_filter", false)

    /** "sourceId|category" for each source in a source category */
    fun sourcesTabSourcesInCategories() = preferenceStore.getStringSet("sources_tab_source_categories", mutableSetOf())

    fun allowLocalSourceHiddenFolders() = preferenceStore.getBoolean("allow_local_source_hidden_folders", false)

    // Bulk migration
    fun migrationSources() = preferenceStore.getLongArray("migration_sources", emptyList())

    fun migrationDeepSearchMode() = preferenceStore.getBoolean("migration_deep_search", false)

    fun migrationPrioritizeByChapters() = preferenceStore.getBoolean("migration_prioritize_by_chapters", false)

    fun migrationHideUnmatched() = preferenceStore.getBoolean("migration_hide_unmatched", false)

    fun migrationHideWithoutUpdates() = preferenceStore.getBoolean("migration_hide_without_updates", false)

    fun migrationSmartSearchSingleEntry() = preferenceStore.getBoolean("migration_smart_search_single_entry", false)
}
// ZOUKAN <--
