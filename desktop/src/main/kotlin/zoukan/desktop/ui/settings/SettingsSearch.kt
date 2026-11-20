package zoukan.desktop.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_edit_categories
import tachiyomi.i18n.auto_download_while_reading
import tachiyomi.i18n.browse
import tachiyomi.i18n.categories
import tachiyomi.i18n.categorized_display_settings
import tachiyomi.i18n.check_for_updates
import tachiyomi.i18n.default_category
import tachiyomi.i18n.default_category_summary
import tachiyomi.i18n.disabled
import tachiyomi.i18n.download_ahead
import tachiyomi.i18n.download_ahead_info
import tachiyomi.i18n.export
import tachiyomi.i18n.ext_revoke_trust
import tachiyomi.i18n.extensionStores
import tachiyomi.i18n.fifth_to_last
import tachiyomi.i18n.fourth_to_last
import tachiyomi.i18n.hide_notification_content
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.download_cache_renew_interval
import tachiyomi.i18n.kmk.download_cache_renew_interval_manual
import tachiyomi.i18n.kmk.pref_custom_theme_style
import tachiyomi.i18n.kmk.pref_fetch_manga_chapters_on_add
import tachiyomi.i18n.kmk.pref_fetch_manga_metadata_on_add
import tachiyomi.i18n.kmk.pref_panorama_cover
import tachiyomi.i18n.kmk.pref_panorama_cover_flow
import tachiyomi.i18n.kmk.pref_panorama_cover_flow_summary
import tachiyomi.i18n.kmk.pref_panorama_cover_summary
import tachiyomi.i18n.kmk.pref_show_empty_categories_search
import tachiyomi.i18n.kmk.pref_source_related_mangas
import tachiyomi.i18n.kmk.pref_source_related_mangas_summary
import tachiyomi.i18n.kmk.pref_theme_cover_based
import tachiyomi.i18n.kmk.pref_theme_cover_based_style
import tachiyomi.i18n.kmk.theme_cloudflare
import tachiyomi.i18n.kmk.web_dav
import tachiyomi.i18n.label_auto
import tachiyomi.i18n.label_backup
import tachiyomi.i18n.label_data
import tachiyomi.i18n.label_data_storage
import tachiyomi.i18n.label_extensions
import tachiyomi.i18n.label_library
import tachiyomi.i18n.label_network
import tachiyomi.i18n.label_sources
import tachiyomi.i18n.last_read_chapter
import tachiyomi.i18n.library_list
import tachiyomi.i18n.lock_always
import tachiyomi.i18n.lock_never
import tachiyomi.i18n.lock_when_idle
import tachiyomi.i18n.lock_with_biometrics
import tachiyomi.i18n.off
import tachiyomi.i18n.password
import tachiyomi.i18n.pref_app_theme
import tachiyomi.i18n.pref_auto_clear_chapter_cache
import tachiyomi.i18n.pref_auto_update_manga_sync
import tachiyomi.i18n.pref_backup_interval
import tachiyomi.i18n.pref_behavior
import tachiyomi.i18n.pref_category_about
import tachiyomi.i18n.pref_category_advanced
import tachiyomi.i18n.pref_category_appearance
import tachiyomi.i18n.pref_category_auto_download
import tachiyomi.i18n.pref_category_delete_chapters
import tachiyomi.i18n.pref_category_downloads
import tachiyomi.i18n.pref_category_general
import tachiyomi.i18n.pref_category_library_update
import tachiyomi.i18n.pref_category_nsfw_content
import tachiyomi.i18n.pref_category_reader
import tachiyomi.i18n.pref_category_security
import tachiyomi.i18n.pref_clear_chapter_cache
import tachiyomi.i18n.pref_clear_cookies
import tachiyomi.i18n.pref_clear_database
import tachiyomi.i18n.pref_clear_database_summary
import tachiyomi.i18n.pref_clear_webview_data
import tachiyomi.i18n.pref_create_backup
import tachiyomi.i18n.pref_dark_theme_pure_black
import tachiyomi.i18n.pref_date_format
import tachiyomi.i18n.pref_disallow_non_ascii_filenames
import tachiyomi.i18n.pref_disallow_non_ascii_filenames_details
import tachiyomi.i18n.pref_display_images_description
import tachiyomi.i18n.pref_dns_over_https
import tachiyomi.i18n.pref_download_concurrent_pages
import tachiyomi.i18n.pref_download_concurrent_sources
import tachiyomi.i18n.pref_download_new
import tachiyomi.i18n.pref_download_new_categories_details
import tachiyomi.i18n.pref_download_new_unread_chapters_only
import tachiyomi.i18n.pref_dump_crash_logs
import tachiyomi.i18n.pref_dump_crash_logs_summary
import tachiyomi.i18n.pref_hide_in_library_items
import tachiyomi.i18n.pref_hide_missing_chapter_indicators
import tachiyomi.i18n.pref_incognito_mode
import tachiyomi.i18n.pref_invalidate_download_cache
import tachiyomi.i18n.pref_invalidate_download_cache_summary
import tachiyomi.i18n.pref_library_update_categories_details
import tachiyomi.i18n.pref_library_update_interval
import tachiyomi.i18n.pref_library_update_refresh_metadata
import tachiyomi.i18n.pref_library_update_refresh_metadata_summary
import tachiyomi.i18n.pref_library_update_restriction
import tachiyomi.i18n.pref_library_update_show_tab_badge
import tachiyomi.i18n.pref_library_update_smart_update
import tachiyomi.i18n.pref_mark_duplicate_read_chapter_read
import tachiyomi.i18n.pref_mark_duplicate_read_chapter_read_existing
import tachiyomi.i18n.pref_mark_duplicate_read_chapter_read_new
import tachiyomi.i18n.pref_onboarding_guide
import tachiyomi.i18n.pref_reader_navigation
import tachiyomi.i18n.pref_refresh_library_covers
import tachiyomi.i18n.pref_relative_format
import tachiyomi.i18n.pref_remove_after_marked_as_read
import tachiyomi.i18n.pref_remove_after_read
import tachiyomi.i18n.pref_remove_bookmarked_chapters
import tachiyomi.i18n.pref_remove_exclude_categories
import tachiyomi.i18n.pref_reset_user_agent_string
import tachiyomi.i18n.pref_reset_viewer_flags
import tachiyomi.i18n.pref_reset_viewer_flags_summary
import tachiyomi.i18n.pref_restore_backup
import tachiyomi.i18n.pref_show_nsfw_source
import tachiyomi.i18n.pref_show_page_number
import tachiyomi.i18n.pref_storage_location
import tachiyomi.i18n.pref_storage_usage
import tachiyomi.i18n.pref_update_library_manga_titles
import tachiyomi.i18n.pref_update_library_manga_titles_summary
import tachiyomi.i18n.pref_update_only_completely_read
import tachiyomi.i18n.pref_update_only_in_release_period
import tachiyomi.i18n.pref_update_only_non_completed
import tachiyomi.i18n.pref_update_only_started
import tachiyomi.i18n.pref_user_agent_string
import tachiyomi.i18n.pref_viewer_type
import tachiyomi.i18n.requires_app_restart
import tachiyomi.i18n.save_chapter_as_cbz
import tachiyomi.i18n.second_to_last
import tachiyomi.i18n.secure_screen
import tachiyomi.i18n.split_tall_images
import tachiyomi.i18n.split_tall_images_summary
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.auto_update_restrictions
import tachiyomi.i18n.sy.bandwidth_data_saver_server
import tachiyomi.i18n.sy.bandwidth_hero
import tachiyomi.i18n.sy.clean_up_downloaded_chapters
import tachiyomi.i18n.sy.data_saver
import tachiyomi.i18n.sy.data_saver_color_bw
import tachiyomi.i18n.sy.data_saver_downloader
import tachiyomi.i18n.sy.data_saver_ignore_gif
import tachiyomi.i18n.sy.data_saver_ignore_jpeg
import tachiyomi.i18n.sy.data_saver_image_format
import tachiyomi.i18n.sy.data_saver_image_quality
import tachiyomi.i18n.sy.data_saver_image_quality_summary
import tachiyomi.i18n.sy.data_saver_summary
import tachiyomi.i18n.sy.delete_unused_chapters
import tachiyomi.i18n.sy.developer_tools
import tachiyomi.i18n.sy.disable_favorites_uploading
import tachiyomi.i18n.sy.eh_image_quality
import tachiyomi.i18n.sy.enable_source_blacklist
import tachiyomi.i18n.sy.feed
import tachiyomi.i18n.sy.force_sync_state_reset
import tachiyomi.i18n.sy.ignore_sync_errors
import tachiyomi.i18n.sy.library_group_updates
import tachiyomi.i18n.sy.library_group_updates_all
import tachiyomi.i18n.sy.library_group_updates_all_but_ungrouped
import tachiyomi.i18n.sy.library_group_updates_global
import tachiyomi.i18n.sy.log_level
import tachiyomi.i18n.sy.mangadex_preffered_source
import tachiyomi.i18n.sy.mangadex_preffered_source_summary
import tachiyomi.i18n.sy.mangadex_push_favorites_to_mangadex
import tachiyomi.i18n.sy.mangadex_push_favorites_to_mangadex_summary
import tachiyomi.i18n.sy.mangadex_sync_follows_to_library
import tachiyomi.i18n.sy.mangadex_sync_follows_to_library_summary
import tachiyomi.i18n.sy.pref_category_mangadex
import tachiyomi.i18n.sy.pref_feed_position
import tachiyomi.i18n.sy.pref_feed_position_summery
import tachiyomi.i18n.sy.pref_google_drive_sign_in
import tachiyomi.i18n.sy.pref_hide_feed
import tachiyomi.i18n.sy.pref_include_chapter_url_hash
import tachiyomi.i18n.sy.pref_include_chapter_url_hash_desc
import tachiyomi.i18n.sy.pref_local_source_hidden_folders
import tachiyomi.i18n.sy.pref_local_source_hidden_folders_summery
import tachiyomi.i18n.sy.pref_source_navigation
import tachiyomi.i18n.sy.pref_source_navigation_summery
import tachiyomi.i18n.sy.pref_source_source_filtering
import tachiyomi.i18n.sy.pref_source_source_filtering_summery
import tachiyomi.i18n.sy.pref_sync_api_key
import tachiyomi.i18n.sy.pref_sync_host
import tachiyomi.i18n.sy.pref_sync_now
import tachiyomi.i18n.sy.pref_tag_sorting
import tachiyomi.i18n.sy.syncyomi
import tachiyomi.i18n.sy.time_between_batches
import tachiyomi.i18n.sy.time_between_batches_12_hours
import tachiyomi.i18n.sy.time_between_batches_1_hour
import tachiyomi.i18n.sy.time_between_batches_24_hours
import tachiyomi.i18n.sy.time_between_batches_2_hours
import tachiyomi.i18n.sy.time_between_batches_6_hours
import tachiyomi.i18n.sy.toggle_delegated_sources
import tachiyomi.i18n.sy.toggle_expand_search_filters
import tachiyomi.i18n.sy.update_1hour
import tachiyomi.i18n.sy.use_hentai_at_home_option_2
import tachiyomi.i18n.sy.wsrv
import tachiyomi.i18n.theme_dark
import tachiyomi.i18n.theme_light
import tachiyomi.i18n.theme_system
import tachiyomi.i18n.third_to_last
import tachiyomi.i18n.track
import tachiyomi.i18n.update_12hour
import tachiyomi.i18n.update_24hour
import tachiyomi.i18n.update_3hour
import tachiyomi.i18n.update_48hour
import tachiyomi.i18n.update_6hour
import tachiyomi.i18n.update_72hour
import tachiyomi.i18n.update_weekly
import tachiyomi.i18n.username
import tachiyomi.i18n.whats_new
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.Shortcuts
import zoukan.desktop.ui.common.CHAPTER_SWIPE_ACTIONS
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.pickableThemes
import zoukan.desktop.ui.common.reducedMotion
import zoukan.desktop.ui.reader.ReaderSettingChoices
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.about_sign_ins
import zoukan.i18n.desktop.adds_the_built_in_e_hentai
import zoukan.i18n.desktop.after_10_minutes
import zoukan.i18n.desktop.after_1_minute
import zoukan.i18n.desktop.after_2_minutes
import zoukan.i18n.desktop.after_5_minutes
import zoukan.i18n.desktop.after_a_library_update
import zoukan.i18n.desktop.always_read_these_sources_in_incognito
import zoukan.i18n.desktop.always_show_navigation_labels
import zoukan.i18n.desktop.any_client_recommended
import zoukan.i18n.desktop.apply_komikku_enhancements_to_the_following
import zoukan.i18n.desktop.apply_to_my_account
import zoukan.i18n.desktop.ask
import zoukan.i18n.desktop.ask_for_a_password_when_zoukan
import zoukan.i18n.desktop.automatic_sync
import zoukan.i18n.desktop.automatically_fetches_the_chapter_list_of
import zoukan.i18n.desktop.automatically_fetches_the_metadata_of_a
import zoukan.i18n.desktop.background
import zoukan.i18n.desktop.block_ads_and_trackers
import zoukan.i18n.desktop.block_ads_and_trackers_summary
import zoukan.i18n.desktop.browse_opens_on_the_galleries_of
import zoukan.i18n.desktop.change_password
import zoukan.i18n.desktop.change_the_download_password
import zoukan.i18n.desktop.chapter_swipe
import zoukan.i18n.desktop.charging_on_mains_power
import zoukan.i18n.desktop.check_for_updates_automatically
import zoukan.i18n.desktop.clear_cover_cache
import zoukan.i18n.desktop.closing_the_window_keeps_zoukan_in
import zoukan.i18n.desktop.covers_download_again_when_shown
import zoukan.i18n.desktop.crash_logs
import zoukan.i18n.desktop.custom_color
import zoukan.i18n.desktop.data_folder
import zoukan.i18n.desktop.data_sync
import zoukan.i18n.desktop.disk_space
import zoukan.i18n.desktop.do_not_add_read_galleries_to
import zoukan.i18n.desktop.download_the_original_files_which_cost
import zoukan.i18n.desktop.encryption
import zoukan.i18n.desktop.enhanced_e_hentai_view
import zoukan.i18n.desktop.entry_page
import zoukan.i18n.desktop.excluded_sources
import zoukan.i18n.desktop.expand_related_titles
import zoukan.i18n.desktop.extensions_trusted_on_this_computer_must
import zoukan.i18n.desktop.extra_block_lists
import zoukan.i18n.desktop.extra_block_lists_summary
import zoukan.i18n.desktop.extra_log_everything
import zoukan.i18n.desktop.extreme_network_inspection_mode
import zoukan.i18n.desktop.favorites_are_only_downloaded_from_exhentai
import zoukan.i18n.desktop.find_the_tracker_entry_from_the
import zoukan.i18n.desktop.folder
import zoukan.i18n.desktop.forget_what_was_synced_the_next
import zoukan.i18n.desktop.front_page_categories
import zoukan.i18n.desktop.github_token
import zoukan.i18n.desktop.github_token_set
import zoukan.i18n.desktop.hide_extensions_sources_that_are_incompatible
import zoukan.i18n.desktop.hide_galleries_in_these_languages_from
import zoukan.i18n.desktop.in_the_reader
import zoukan.i18n.desktop.include_test_releases
import zoukan.i18n.desktop.incognito
import zoukan.i18n.desktop.incognito_extensions
import zoukan.i18n.desktop.install_missing_extensions
import zoukan.i18n.desktop.install_missing_extensions_summary
import zoukan.i18n.desktop.keep_running_in_the_tray
import zoukan.i18n.desktop.keyboard_shortcuts_2
import zoukan.i18n.desktop.language_filtering
import zoukan.i18n.desktop.leave_storage_folder_out_of_time_machine
import zoukan.i18n.desktop.leave_storage_folder_out_of_time_machine_summary
import zoukan.i18n.desktop.lock_at_these_times
import zoukan.i18n.desktop.lock_on_these_days
import zoukan.i18n.desktop.mark_chapters_read_when_a_tracker
import zoukan.i18n.desktop.minimal_critical_errors_only
import zoukan.i18n.desktop.myanimelist_anilist_shikimori_and_bangumi_open
import zoukan.i18n.desktop.no_donators_only
import zoukan.i18n.desktop.notifications_say_only_that_there_is
import zoukan.i18n.desktop.on_start_when_the_window_comes
import zoukan.i18n.desktop.once_a_day_while_zoukan_runs
import zoukan.i18n.desktop.only_show_related_titles_from_the
import zoukan.i18n.desktop.open_the_automatic_backup_folder
import zoukan.i18n.desktop.open_the_backup_folder
import zoukan.i18n.desktop.open_the_downloads_folder
import zoukan.i18n.desktop.open_the_local_source_folder
import zoukan.i18n.desktop.open_the_log_folder
import zoukan.i18n.desktop.original_images
import zoukan.i18n.desktop.otherwise_only_the_open_tabs
import zoukan.i18n.desktop.page_preview_rows
import zoukan.i18n.desktop.page_scaling_paged_modes
import zoukan.i18n.desktop.password_protect_downloads
import zoukan.i18n.desktop.press_for_the_readers_shortcuts
import zoukan.i18n.desktop.put_a_folder_per_entry_here
import zoukan.i18n.desktop.reading_keeps_no_history_progress_or
import zoukan.i18n.desktop.related_titles_in_overflow_menu
import zoukan.i18n.desktop.release_notes_for_zoukan
import zoukan.i18n.desktop.releases_marked_as_pre_release_on
import zoukan.i18n.desktop.replace_server_library
import zoukan.i18n.desktop.replace_server_library_summary
import zoukan.i18n.desktop.reset_the_last_sync_time
import zoukan.i18n.desktop.resolve_with_source_metadata
import zoukan.i18n.desktop.server_url
import zoukan.i18n.desktop.set_the_download_password
import zoukan.i18n.desktop.show_gallery_details_rating_tags_uploader
import zoukan.i18n.desktop.show_history_button
import zoukan.i18n.desktop.show_the_japanese_title_of_a
import zoukan.i18n.desktop.show_the_related_titles_row_open
import zoukan.i18n.desktop.show_updates_button
import zoukan.i18n.desktop.sign_in_with_an_e_hentai
import zoukan.i18n.desktop.signs_you_out_of_sites_and
import zoukan.i18n.desktop.sorting
import zoukan.i18n.desktop.standard_zip_encryption
import zoukan.i18n.desktop.swipe_to_left_action
import zoukan.i18n.desktop.swipe_to_right_action
import zoukan.i18n.desktop.sync_favorites
import zoukan.i18n.desktop.sync_progress_from_trackers
import zoukan.i18n.desktop.sync_service
import zoukan.i18n.desktop.tag_filtering_threshold
import zoukan.i18n.desktop.tag_watching_threshold
import zoukan.i18n.desktop.the_built_in_browsers_cookies_and
import zoukan.i18n.desktop.the_categories_searches_show_by_default
import zoukan.i18n.desktop.the_color_the_custom_theme_is
import zoukan.i18n.desktop.the_next_sync_uploads_this_library
import zoukan.i18n.desktop.track_when_adding_to_the_library
import zoukan.i18n.desktop.update_galleries_now
import zoukan.i18n.desktop.update_progress_when_marking_as_read
import zoukan.i18n.desktop.update_the_library_now
import zoukan.i18n.desktop.uploads_these_settings_to_your_e
import zoukan.i18n.desktop.use_japanese_titles
import zoukan.i18n.desktop.watched_list_by_default
import zoukan.i18n.desktop.webtoon_side_padding
import zoukan.i18n.desktop.what_to_sync
import zoukan.i18n.desktop.when_to_sync
import zoukan.i18n.desktop.zoukan
import zoukan.i18n.desktop.zoukan_log_has_what_zoukan_logged

/**
 * One searchable setting in the declarative catalog, matching Android's jump-to-page search model:
 * picking a result opens its [category] and scrolls to + highlights [itemKey].
 */
data class SettingsSearchEntry(
    val category: SettingsCategory,
    val group: String? = null,
    val title: String,
    val summary: String? = null,
    val keywords: List<String> = emptyList(),
    val itemKey: Any? = null,
) {
    /** Breadcrumb shown under [title] in search results, e.g. "Data and storage  ›  Data sync". */
    val breadcrumb: String
        get() = listOfNotNull(category.title, group?.takeIf { it != category.title }).joinToString("  ›  ")

    /** All strings matched against a query for this setting. */
    val searchableTexts: List<String>
        get() = buildList {
            add(title)
            if (!summary.isNullOrBlank()) add(summary)
            if (!group.isNullOrBlank()) add(group)
            keywords.forEach { if (it.isNotBlank()) add(it) }
        }
}

/**
 * Settings search, matching the Android app's jump-to-page `SettingsSearchScreen`: searches an
 * explicit declarative catalog of every setting's title, summary and choices without offscreen
 * Compose rendering.
 */
object SettingsSearch {
    /** Whether [texts] match [query]: every word of the query is in one of them, ignoring case. */
    fun matches(query: String, texts: Collection<String>): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = texts.joinToString("\n").lowercase()
        return words.all { it in haystack }
    }

    /** A page's item: its key, or its position for an item without one, so search and the page agree. */
    internal fun itemId(key: Any?, index: Int): Any = key ?: "#$index"

    /** Builds the declarative search index across all settings pages. */
    fun index(
        includeMangaDex: Boolean = true,
        trackers: List<Pair<Long, String>> = emptyList(),
        includeMacOsOnly: Boolean = Platform.current == Platform.MACOS,
    ): List<SettingsSearchEntry> = buildSettingsSearchIndex(
        includeMangaDex = includeMangaDex,
        trackers = trackers,
        includeMacOsOnly = includeMacOsOnly,
    )

    /** Returns every entry in [entries] matching [query], optionally filtered to [enabledCategories]. */
    fun search(
        query: String,
        entries: List<SettingsSearchEntry> = index(),
        enabledCategories: Set<SettingsCategory>? = null,
    ): List<SettingsSearchEntry> {
        if (query.isBlank()) return emptyList()
        return entries.filter { entry ->
            (enabledCategories == null || entry.category in enabledCategories) &&
                matches(query, entry.searchableTexts)
        }
    }
}

/**
 * A page's list as it is, with the item [highlight] names flashed, as Android highlights the
 * setting a search opened. [ids] gets each item's id in order, to scroll to it.
 */
internal class HighlightScope(
    private val delegate: LazyListScope,
    private val highlight: Any?,
    private val ids: MutableList<Any>,
) : LazyListScope by delegate {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val id = SettingsSearch.itemId(key, ids.size)
        ids += id
        if (id != highlight) {
            delegate.item(key, contentType, content)
        } else {
            delegate.item(key, contentType) { Highlighted { content() } }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val first = ids.size
        repeat(count) { ids += SettingsSearch.itemId(key?.invoke(it), first + it) }
        val ids = ids
        val highlight = highlight
        delegate.items(count, key, contentType) { i ->
            if (ids.getOrNull(first + i) == highlight) Highlighted { itemContent(i) } else itemContent(i)
        }
    }
}

/** The key prefix of [settingsGroup] headers. */
internal const val GROUP_KEY_PREFIX = "group-"

/** The content type of a [settingsGroup] header: it carries the title, which the key (an id) may not. */
internal data class SettingsGroupHeader(val title: String)

/** Fades a highlight behind [content] in and out, twice; a static tint for as long when motion is reduced. */
@Composable
private fun Highlighted(content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val reduced by reducedMotion()
    LaunchedEffect(reduced) {
        if (reduced) {
            alpha.snapTo(0.35f)
            delay(1600)
            alpha.snapTo(0f)
        } else {
            repeat(2) {
                alpha.animateTo(0.35f, tween(300))
                alpha.animateTo(0f, tween(500))
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().background(color.copy(alpha = alpha.value))) { content() }
}

/** One jump-to-page search result row: title over its page › group breadcrumb. */
@Composable
internal fun SearchResultRow(
    entry: SettingsSearchEntry,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.SettingsRowInset, vertical = 14.dp),
    ) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.breadcrumb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildSettingsSearchIndex(
    includeMangaDex: Boolean,
    trackers: List<Pair<Long, String>>,
    includeMacOsOnly: Boolean,
): List<SettingsSearchEntry> = buildList {
    fun entry(
        category: SettingsCategory,
        group: String?,
        title: String,
        summary: String? = null,
        keywords: List<String> = emptyList(),
        itemKey: Any? = null,
    ) {
        add(SettingsSearchEntry(category, group, title, summary, keywords, itemKey))
    }

    val paletteStyles = PaletteStyle.entries.map(::paletteStyleLabel)

    // Appearance
    val appearanceGroup = localized(MR.strings.pref_category_appearance)
    entry(
        SettingsCategory.Appearance,
        appearanceGroup,
        localized(MR.strings.pref_app_theme),
        keywords = pickableThemes.map(::themeLabel) + listOf(
            localized(MR.strings.theme_system),
            localized(MR.strings.theme_light),
            localized(MR.strings.theme_dark),
        ),
        itemKey = "appearance-theme",
    )
    entry(
        SettingsCategory.Appearance,
        appearanceGroup,
        localized(DMR.strings.custom_color),
        localized(DMR.strings.the_color_the_custom_theme_is),
        itemKey = "appearance-theme",
    )
    entry(
        SettingsCategory.Appearance,
        appearanceGroup,
        localized(KMR.strings.pref_custom_theme_style),
        keywords = paletteStyles,
        itemKey = "appearance-theme",
    )
    entry(
        SettingsCategory.Appearance,
        appearanceGroup,
        localized(MR.strings.pref_dark_theme_pure_black),
        itemKey = "appearance-theme",
    )
    entry(SettingsCategory.Appearance, appearanceGroup, localized(MR.strings.pref_date_format), itemKey = "appearance-display")
    entry(SettingsCategory.Appearance, appearanceGroup, localized(MR.strings.pref_relative_format), itemKey = "appearance-display")
    entry(SettingsCategory.Appearance, appearanceGroup, localized(MR.strings.pref_display_images_description), itemKey = "appearance-display")

    val entryPageGroup = localized(DMR.strings.entry_page)
    entry(SettingsCategory.Appearance, entryPageGroup, localized(KMR.strings.pref_theme_cover_based), itemKey = "appearance-manga-info")
    entry(
        SettingsCategory.Appearance,
        entryPageGroup,
        localized(KMR.strings.pref_theme_cover_based_style),
        keywords = paletteStyles,
        itemKey = "appearance-manga-info",
    )
    entry(
        SettingsCategory.Appearance,
        entryPageGroup,
        localized(KMR.strings.pref_panorama_cover),
        localized(KMR.strings.pref_panorama_cover_summary),
        itemKey = "appearance-manga-info",
    )
    entry(SettingsCategory.Appearance, entryPageGroup, localized(DMR.strings.page_preview_rows), itemKey = "appearance-previews")

    val navGroup = localized(MR.strings.pref_reader_navigation)
    entry(SettingsCategory.Appearance, navGroup, localized(DMR.strings.show_updates_button), itemKey = "appearance-navigation")
    entry(SettingsCategory.Appearance, navGroup, localized(DMR.strings.show_history_button), itemKey = "appearance-navigation")
    entry(
        SettingsCategory.Appearance,
        navGroup,
        localized(DMR.strings.always_show_navigation_labels),
        localized(DMR.strings.otherwise_only_the_open_tabs),
        itemKey = "appearance-navigation",
    )

    val appearanceBrowseGroup = localized(MR.strings.browse)
    entry(
        SettingsCategory.Appearance,
        appearanceBrowseGroup,
        localized(KMR.strings.pref_panorama_cover_flow),
        localized(KMR.strings.pref_panorama_cover_flow_summary),
        itemKey = "appearance-panorama-cover-flow",
    )
    entry(
        SettingsCategory.Appearance,
        appearanceBrowseGroup,
        localized(SYMR.strings.toggle_expand_search_filters),
        itemKey = "appearance-expand-filters",
    )

    // Library
    val libraryGroup = localized(MR.strings.label_library)
    entry(SettingsCategory.Library, libraryGroup, localized(MR.strings.action_edit_categories), itemKey = "library-edit-categories")
    entry(
        SettingsCategory.Library,
        libraryGroup,
        localized(MR.strings.default_category),
        localized(MR.strings.default_category_summary),
        itemKey = "library-default-category",
    )
    entry(SettingsCategory.Library, libraryGroup, localized(MR.strings.categorized_display_settings), itemKey = "library-categorized")

    val libraryUpdateGroup = localized(MR.strings.pref_category_library_update)
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(MR.strings.pref_library_update_interval),
        keywords = listOf(
            MR.strings.off,
            MR.strings.update_12hour,
            MR.strings.update_24hour,
            MR.strings.update_48hour,
            MR.strings.update_72hour,
            MR.strings.update_weekly,
        ).map(::localized),
        itemKey = "library-update-interval",
    )
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(MR.strings.pref_library_update_restriction),
        keywords = listOf(localized(DMR.strings.charging_on_mains_power)),
        itemKey = "library-update-restrictions",
    )
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(MR.strings.categories),
        localized(MR.strings.pref_library_update_categories_details),
        itemKey = "library-update-categories",
    )
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(SYMR.strings.library_group_updates),
        keywords = listOf(
            SYMR.strings.library_group_updates_global,
            SYMR.strings.library_group_updates_all_but_ungrouped,
            SYMR.strings.library_group_updates_all,
        ).map(::localized),
        itemKey = "library-group-updates",
    )
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(MR.strings.pref_library_update_refresh_metadata),
        localized(MR.strings.pref_library_update_refresh_metadata_summary),
        itemKey = "library-update-metadata",
    )
    entry(
        SettingsCategory.Library,
        libraryUpdateGroup,
        localized(MR.strings.pref_library_update_smart_update),
        keywords = listOf(
            MR.strings.pref_update_only_completely_read,
            MR.strings.pref_update_only_started,
            MR.strings.pref_update_only_non_completed,
            MR.strings.pref_update_only_in_release_period,
        ).map(::localized),
        itemKey = "library-update-smart",
    )
    entry(SettingsCategory.Library, libraryUpdateGroup, localized(MR.strings.pref_library_update_show_tab_badge), itemKey = "library-update-badge")
    entry(SettingsCategory.Library, libraryUpdateGroup, localized(DMR.strings.update_the_library_now), itemKey = "library-update-now")

    val behaviorGroup = localized(MR.strings.pref_behavior)
    entry(
        SettingsCategory.Library,
        behaviorGroup,
        localized(MR.strings.pref_mark_duplicate_read_chapter_read),
        keywords = listOf(
            MR.strings.pref_mark_duplicate_read_chapter_read_existing,
            MR.strings.pref_mark_duplicate_read_chapter_read_new,
        ).map(::localized),
        itemKey = "library-duplicates",
    )
    entry(SettingsCategory.Library, behaviorGroup, localized(MR.strings.pref_hide_missing_chapter_indicators), itemKey = "library-missing-chapters")
    entry(SettingsCategory.Library, behaviorGroup, localized(KMR.strings.pref_show_empty_categories_search), itemKey = "library-show-empty-categories-search")
    entry(
        SettingsCategory.Library,
        behaviorGroup,
        localized(KMR.strings.pref_fetch_manga_metadata_on_add),
        localized(DMR.strings.automatically_fetches_the_metadata_of_a),
        itemKey = "library-fetch-metadata-on-add",
    )
    entry(
        SettingsCategory.Library,
        behaviorGroup,
        localized(KMR.strings.pref_fetch_manga_chapters_on_add),
        localized(DMR.strings.automatically_fetches_the_chapter_list_of),
        itemKey = "library-fetch-chapters-on-add",
    )

    val sortingGroup = localized(DMR.strings.sorting)
    entry(SettingsCategory.Library, sortingGroup, localized(SYMR.strings.pref_tag_sorting), itemKey = "library-sort-tags")

    val swipeGroup = localized(DMR.strings.chapter_swipe)
    val swipeKeywords = CHAPTER_SWIPE_ACTIONS.map { it.second }
    entry(SettingsCategory.Library, swipeGroup, localized(DMR.strings.swipe_to_left_action), keywords = swipeKeywords, itemKey = "library-swipe-start")
    entry(SettingsCategory.Library, swipeGroup, localized(DMR.strings.swipe_to_right_action), keywords = swipeKeywords, itemKey = "library-swipe-end")

    // Reader
    val readerGroup = localized(MR.strings.pref_category_reader)
    entry(
        SettingsCategory.Reader,
        readerGroup,
        localized(MR.strings.pref_viewer_type),
        keywords = ReadingModes.all.map(ReadingModes::label),
        itemKey = "reader-default-mode",
    )
    entry(
        SettingsCategory.Reader,
        readerGroup,
        localized(DMR.strings.page_scaling_paged_modes),
        keywords = FitModes.all.map(FitModes::label),
        itemKey = "reader-image-scale-type",
    )
    entry(
        SettingsCategory.Reader,
        readerGroup,
        localized(DMR.strings.background),
        keywords = ReaderSettingChoices.themes.map { it.second },
        itemKey = "reader-theme",
    )
    entry(SettingsCategory.Reader, readerGroup, localized(DMR.strings.webtoon_side_padding), itemKey = "reader-webtoon-side-padding")
    entry(SettingsCategory.Reader, readerGroup, localized(MR.strings.pref_show_page_number), itemKey = "reader-show-page-number")
    addAll(ReaderSettingRows.searchEntries())

    // Downloads
    val downloadsGroup = localized(MR.strings.pref_category_downloads)
    entry(SettingsCategory.Downloads, downloadsGroup, localized(MR.strings.save_chapter_as_cbz), itemKey = "downloads-cbz")
    entry(
        SettingsCategory.Downloads,
        downloadsGroup,
        localized(MR.strings.split_tall_images),
        localized(MR.strings.split_tall_images_summary),
        itemKey = "downloads-split-tall",
    )
    entry(SettingsCategory.Downloads, downloadsGroup, localized(MR.strings.pref_download_concurrent_sources), itemKey = "downloads-concurrent-sources")
    entry(SettingsCategory.Downloads, downloadsGroup, localized(MR.strings.pref_download_concurrent_pages), itemKey = "downloads-concurrent-pages")
    entry(SettingsCategory.Downloads, downloadsGroup, localized(DMR.strings.password_protect_downloads), itemKey = "downloads-password-protect")
    entry(
        SettingsCategory.Downloads,
        downloadsGroup,
        localized(DMR.strings.set_the_download_password),
        keywords = listOf(localized(DMR.strings.change_the_download_password)),
        itemKey = "downloads-set-password",
    )
    entry(
        SettingsCategory.Downloads,
        downloadsGroup,
        localized(DMR.strings.encryption),
        keywords = listOf("AES-256", "AES-128", localized(DMR.strings.standard_zip_encryption)),
        itemKey = "downloads-encryption",
    )

    val deleteGroup = localized(MR.strings.pref_category_delete_chapters)
    entry(SettingsCategory.Downloads, deleteGroup, localized(MR.strings.pref_remove_after_marked_as_read), itemKey = "downloads-remove-marked")
    entry(
        SettingsCategory.Downloads,
        deleteGroup,
        localized(MR.strings.pref_remove_after_read),
        keywords = listOf(
            MR.strings.disabled,
            MR.strings.last_read_chapter,
            MR.strings.second_to_last,
            MR.strings.third_to_last,
            MR.strings.fourth_to_last,
            MR.strings.fifth_to_last,
        ).map(::localized),
        itemKey = "downloads-remove-read",
    )
    entry(SettingsCategory.Downloads, deleteGroup, localized(MR.strings.pref_remove_bookmarked_chapters), itemKey = "downloads-remove-bookmarked")
    entry(SettingsCategory.Downloads, deleteGroup, localized(MR.strings.pref_remove_exclude_categories), itemKey = "downloads-remove-exclude")

    val autoDownloadGroup = localized(MR.strings.pref_category_auto_download)
    entry(
        SettingsCategory.Downloads,
        autoDownloadGroup,
        localized(MR.strings.pref_download_new),
        localized(DMR.strings.after_a_library_update),
        itemKey = "downloads-new",
    )
    entry(SettingsCategory.Downloads, autoDownloadGroup, localized(MR.strings.pref_download_new_unread_chapters_only), itemKey = "downloads-new-unread")
    entry(
        SettingsCategory.Downloads,
        autoDownloadGroup,
        localized(MR.strings.categories),
        localized(MR.strings.pref_download_new_categories_details),
        itemKey = "downloads-new-categories",
    )

    val downloadAheadGroup = localized(MR.strings.download_ahead)
    entry(
        SettingsCategory.Downloads,
        downloadAheadGroup,
        localized(MR.strings.auto_download_while_reading),
        localized(MR.strings.download_ahead_info),
        itemKey = "downloads-ahead",
    )

    val cacheRenewGroup = localized(KMR.strings.download_cache_renew_interval)
    entry(
        SettingsCategory.Downloads,
        cacheRenewGroup,
        localized(KMR.strings.download_cache_renew_interval),
        keywords = listOf(
            KMR.strings.download_cache_renew_interval_manual,
            SYMR.strings.time_between_batches_1_hour,
            SYMR.strings.time_between_batches_2_hours,
            SYMR.strings.time_between_batches_6_hours,
            SYMR.strings.time_between_batches_12_hours,
            SYMR.strings.time_between_batches_24_hours,
        ).map(::localized),
        itemKey = "downloads-cache-renew",
    )

    // Tracking
    val trackingGroup = localized(MR.strings.track)
    entry(SettingsCategory.Tracking, trackingGroup, localized(DMR.strings.track_when_adding_to_the_library), itemKey = "tracking-options")
    entry(SettingsCategory.Tracking, trackingGroup, localized(MR.strings.pref_auto_update_manga_sync), itemKey = "tracking-options")
    entry(
        SettingsCategory.Tracking,
        trackingGroup,
        localized(DMR.strings.update_progress_when_marking_as_read),
        keywords = listOf(localized(MR.strings.lock_always), localized(MR.strings.lock_never), localized(DMR.strings.ask)),
        itemKey = "tracking-options",
    )
    entry(
        SettingsCategory.Tracking,
        trackingGroup,
        localized(DMR.strings.sync_progress_from_trackers),
        localized(DMR.strings.mark_chapters_read_when_a_tracker),
        itemKey = "tracking-options",
    )
    entry(
        SettingsCategory.Tracking,
        trackingGroup,
        localized(DMR.strings.resolve_with_source_metadata),
        localized(DMR.strings.find_the_tracker_entry_from_the),
        itemKey = "tracking-options",
    )
    trackers.forEach { (id, name) ->
        entry(SettingsCategory.Tracking, trackingGroup, name, itemKey = "tracker-$id")
    }
    entry(
        SettingsCategory.Tracking,
        trackingGroup,
        localized(DMR.strings.about_sign_ins),
        localized(DMR.strings.myanimelist_anilist_shikimori_and_bangumi_open),
        itemKey = "tracking-info",
    )

    // Browse
    val browseSourcesGroup = localized(MR.strings.label_sources)
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(KMR.strings.pref_source_related_mangas),
        localized(KMR.strings.pref_source_related_mangas_summary),
        itemKey = "browse-suggestions",
    )
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(DMR.strings.expand_related_titles),
        localized(DMR.strings.show_the_related_titles_row_open),
        itemKey = "browse-related-expand",
    )
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(DMR.strings.related_titles_in_overflow_menu),
        localized(DMR.strings.only_show_related_titles_from_the),
        itemKey = "browse-related-overflow",
    )
    entry(SettingsCategory.Browse, browseSourcesGroup, localized(MR.strings.action_edit_categories), itemKey = "browse-categories")
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(SYMR.strings.pref_source_source_filtering),
        localized(SYMR.strings.pref_source_source_filtering_summery),
        itemKey = "browse-categories-filter",
    )
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(SYMR.strings.pref_source_navigation),
        localized(SYMR.strings.pref_source_navigation_summery),
        itemKey = "browse-navigation",
    )
    entry(
        SettingsCategory.Browse,
        browseSourcesGroup,
        localized(SYMR.strings.pref_local_source_hidden_folders),
        localized(SYMR.strings.pref_local_source_hidden_folders_summery),
        itemKey = "browse-local-hidden",
    )

    val feedGroup = localized(SYMR.strings.feed)
    entry(SettingsCategory.Browse, feedGroup, localized(SYMR.strings.pref_hide_feed), itemKey = "browse-hide-feed")
    entry(
        SettingsCategory.Browse,
        feedGroup,
        localized(SYMR.strings.pref_feed_position),
        localized(SYMR.strings.pref_feed_position_summery),
        itemKey = "browse-feed-position",
    )
    entry(SettingsCategory.Browse, feedGroup, localized(MR.strings.pref_hide_in_library_items), itemKey = "browse-feed-hide-library")

    entry(SettingsCategory.Browse, browseSourcesGroup, localized(MR.strings.pref_hide_in_library_items), itemKey = "browse-hide-library")
    entry(SettingsCategory.Browse, browseSourcesGroup, localized(MR.strings.extensionStores), itemKey = "browse-stores")

    val nsfwGroup = localized(MR.strings.pref_category_nsfw_content)
    entry(
        SettingsCategory.Browse,
        nsfwGroup,
        localized(MR.strings.pref_show_nsfw_source),
        localized(MR.strings.requires_app_restart),
        itemKey = "browse-nsfw",
    )

    // Data and storage
    val dataGroup = localized(MR.strings.label_data_storage)
    entry(SettingsCategory.Data, dataGroup, localized(MR.strings.pref_storage_location), itemKey = "data-storage-location")
    entry(SettingsCategory.Data, dataGroup, localized(DMR.strings.open_the_downloads_folder), itemKey = "data-open-downloads")
    entry(
        SettingsCategory.Data,
        dataGroup,
        localized(DMR.strings.open_the_local_source_folder),
        localized(DMR.strings.put_a_folder_per_entry_here),
        itemKey = "data-open-local",
    )
    if (includeMacOsOnly) {
        entry(
            SettingsCategory.Data,
            dataGroup,
            localized(DMR.strings.leave_storage_folder_out_of_time_machine),
            localized(DMR.strings.leave_storage_folder_out_of_time_machine_summary),
            itemKey = "data-storage-time-machine",
        )
    }

    val backupGroup = localized(MR.strings.label_backup)
    entry(SettingsCategory.Data, backupGroup, localized(MR.strings.pref_create_backup), itemKey = "data-backup-buttons")
    entry(SettingsCategory.Data, backupGroup, localized(MR.strings.pref_restore_backup), itemKey = "data-backup-buttons")
    entry(
        SettingsCategory.Data,
        backupGroup,
        localized(DMR.strings.install_missing_extensions),
        localized(DMR.strings.install_missing_extensions_summary),
        itemKey = "data-install-missing-extensions",
    )
    entry(
        SettingsCategory.Data,
        backupGroup,
        localized(MR.strings.pref_backup_interval),
        keywords = listOf(
            MR.strings.off,
            SYMR.strings.update_1hour,
            MR.strings.update_3hour,
            MR.strings.update_6hour,
            MR.strings.update_12hour,
            MR.strings.update_24hour,
            MR.strings.update_48hour,
            MR.strings.update_weekly,
        ).map(::localized),
        itemKey = "data-backup-interval",
    )
    entry(SettingsCategory.Data, backupGroup, localized(DMR.strings.open_the_backup_folder), itemKey = "data-backup-folder")
    entry(SettingsCategory.Data, backupGroup, localized(DMR.strings.open_the_automatic_backup_folder), itemKey = "data-auto-backup-folder")

    val storageUsageGroup = localized(MR.strings.pref_storage_usage)
    entry(
        SettingsCategory.Data,
        storageUsageGroup,
        localized(MR.strings.pref_storage_usage),
        keywords = listOf(localized(DMR.strings.disk_space)),
        itemKey = "data-storage-usage",
    )
    entry(SettingsCategory.Data, storageUsageGroup, localized(MR.strings.pref_clear_chapter_cache), itemKey = "data-chapter-cache")
    entry(SettingsCategory.Data, storageUsageGroup, localized(MR.strings.pref_auto_clear_chapter_cache), itemKey = "data-auto-clear-cache")
    entry(
        SettingsCategory.Data,
        storageUsageGroup,
        localized(DMR.strings.clear_cover_cache),
        localized(DMR.strings.covers_download_again_when_shown),
        itemKey = "data-cover-cache",
    )
    entry(SettingsCategory.Data, storageUsageGroup, localized(DMR.strings.data_folder), itemKey = "data-folder")

    val exportGroup = localized(MR.strings.export)
    entry(SettingsCategory.Data, exportGroup, localized(MR.strings.library_list), itemKey = "data-export")

    val syncGroup = localized(DMR.strings.data_sync)
    entry(
        SettingsCategory.Data,
        syncGroup,
        localized(DMR.strings.sync_service),
        keywords = listOf(localized(MR.strings.off), localized(SYMR.strings.syncyomi), localized(KMR.strings.web_dav)),
        itemKey = "sync",
    )
    entry(SettingsCategory.Data, syncGroup, localized(SYMR.strings.pref_sync_host), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(SYMR.strings.pref_sync_api_key), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(DMR.strings.server_url), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(MR.strings.username), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(MR.strings.password), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(DMR.strings.folder), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(DMR.strings.automatic_sync), itemKey = "sync")
    entry(SettingsCategory.Data, syncGroup, localized(DMR.strings.what_to_sync), itemKey = "sync")
    entry(
        SettingsCategory.Data,
        syncGroup,
        localized(DMR.strings.when_to_sync),
        localized(DMR.strings.on_start_when_the_window_comes),
        itemKey = "sync",
    )
    entry(SettingsCategory.Data, syncGroup, localized(SYMR.strings.pref_sync_now), itemKey = "sync")
    entry(
        SettingsCategory.Data,
        syncGroup,
        localized(DMR.strings.reset_the_last_sync_time),
        localized(DMR.strings.the_next_sync_uploads_this_library),
        itemKey = "sync",
    )
    entry(
        SettingsCategory.Data,
        syncGroup,
        localized(DMR.strings.replace_server_library),
        localized(DMR.strings.replace_server_library_summary),
        itemKey = "sync",
    )

    val dataSaverGroup = localized(SYMR.strings.data_saver)
    entry(
        SettingsCategory.Data,
        dataSaverGroup,
        localized(SYMR.strings.data_saver),
        localized(SYMR.strings.data_saver_summary),
        keywords = listOf(localized(MR.strings.disabled), localized(SYMR.strings.bandwidth_hero), localized(SYMR.strings.wsrv)),
        itemKey = "data-saver-settings",
    )
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.bandwidth_data_saver_server), itemKey = "data-saver-settings")
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.data_saver_downloader), itemKey = "data-saver-settings")
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.data_saver_ignore_jpeg), itemKey = "data-saver-settings")
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.data_saver_ignore_gif), itemKey = "data-saver-settings")
    entry(
        SettingsCategory.Data,
        dataSaverGroup,
        localized(SYMR.strings.data_saver_image_quality),
        localized(SYMR.strings.data_saver_image_quality_summary),
        itemKey = "data-saver-settings",
    )
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.data_saver_image_format), itemKey = "data-saver-settings")
    entry(SettingsCategory.Data, dataSaverGroup, localized(SYMR.strings.data_saver_color_bw), itemKey = "data-saver-settings")
    entry(SettingsCategory.Data, dataSaverGroup, localized(DMR.strings.excluded_sources), itemKey = "data-saver-settings")

    // Security and privacy
    val securityGroup = localized(MR.strings.pref_category_security)
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(MR.strings.lock_with_biometrics),
        localized(DMR.strings.ask_for_a_password_when_zoukan),
        itemKey = "security",
    )
    entry(SettingsCategory.Security, securityGroup, localized(DMR.strings.change_password), itemKey = "security")
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(MR.strings.lock_when_idle),
        keywords = listOf(
            localized(MR.strings.lock_always),
            localized(DMR.strings.after_1_minute),
            localized(DMR.strings.after_2_minutes),
            localized(DMR.strings.after_5_minutes),
            localized(DMR.strings.after_10_minutes),
            localized(MR.strings.lock_never),
        ),
        itemKey = "security",
    )
    entry(SettingsCategory.Security, securityGroup, localized(DMR.strings.lock_on_these_days), itemKey = "security")
    entry(SettingsCategory.Security, securityGroup, localized(DMR.strings.lock_at_these_times), itemKey = "security")
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(MR.strings.secure_screen),
        keywords = SecurityPreferences.SecureScreenMode.entries.map { localized(it.titleRes) },
        itemKey = "security",
    )
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(MR.strings.hide_notification_content),
        localized(DMR.strings.notifications_say_only_that_there_is),
        itemKey = "security",
    )
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(MR.strings.pref_incognito_mode),
        localized(DMR.strings.reading_keeps_no_history_progress_or),
        itemKey = "security",
    )
    entry(
        SettingsCategory.Security,
        securityGroup,
        localized(DMR.strings.incognito_extensions),
        localized(DMR.strings.always_read_these_sources_in_incognito),
        itemKey = "security",
    )

    // E-Hentai
    val ehGroup = "E-Hentai"
    entry(SettingsCategory.EHentai, ehGroup, "E-Hentai", localized(DMR.strings.adds_the_built_in_e_hentai), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, "ExHentai", localized(DMR.strings.sign_in_with_an_e_hentai), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.use_japanese_titles), localized(DMR.strings.show_the_japanese_title_of_a), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.original_images), localized(DMR.strings.download_the_original_files_which_cost), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.watched_list_by_default), localized(DMR.strings.browse_opens_on_the_galleries_of), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.enhanced_e_hentai_view), localized(DMR.strings.show_gallery_details_rating_tags_uploader), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.incognito), localized(DMR.strings.do_not_add_read_galleries_to), itemKey = "eh-settings")
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(SYMR.strings.eh_image_quality),
        keywords = listOf(localized(MR.strings.label_auto), "2400x", "1600x", "1280x", "980x", "780x"),
        itemKey = "eh-settings",
    )
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        "Hentai@Home",
        keywords = listOf(
            localized(DMR.strings.any_client_recommended),
            localized(SYMR.strings.use_hentai_at_home_option_2),
            localized(DMR.strings.no_donators_only),
        ),
        itemKey = "eh-settings",
    )
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.tag_filtering_threshold), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.tag_watching_threshold), itemKey = "eh-settings")
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(DMR.strings.language_filtering),
        localized(DMR.strings.hide_galleries_in_these_languages_from),
        itemKey = "eh-settings",
    )
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(DMR.strings.front_page_categories),
        localized(DMR.strings.the_categories_searches_show_by_default),
        itemKey = "eh-settings",
    )
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(DMR.strings.apply_to_my_account),
        localized(DMR.strings.uploads_these_settings_to_your_e),
        itemKey = "eh-settings",
    )
    entry(SettingsCategory.EHentai, ehGroup, localized(SYMR.strings.time_between_batches), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(SYMR.strings.auto_update_restrictions), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.update_galleries_now), itemKey = "eh-settings")
    entry(SettingsCategory.EHentai, ehGroup, localized(DMR.strings.sync_favorites), itemKey = "eh-settings")
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(SYMR.strings.disable_favorites_uploading),
        localized(DMR.strings.favorites_are_only_downloaded_from_exhentai),
        itemKey = "eh-settings",
    )
    entry(SettingsCategory.EHentai, ehGroup, localized(SYMR.strings.ignore_sync_errors), itemKey = "eh-settings")
    entry(
        SettingsCategory.EHentai,
        ehGroup,
        localized(SYMR.strings.force_sync_state_reset),
        localized(DMR.strings.forget_what_was_synced_the_next),
        itemKey = "eh-settings",
    )

    // MangaDex (shown only when a MangaDex source is enabled, as on Android)
    if (includeMangaDex) {
        val mdexGroup = localized(SYMR.strings.pref_category_mangadex)
        entry(
            SettingsCategory.MangaDex,
            mdexGroup,
            "MangaDex",
            keywords = listOf("MDList", localized(SYMR.strings.pref_google_drive_sign_in)),
            itemKey = "mangadex-settings",
        )
        entry(
            SettingsCategory.MangaDex,
            mdexGroup,
            localized(SYMR.strings.mangadex_preffered_source),
            localized(SYMR.strings.mangadex_preffered_source_summary),
            itemKey = "mangadex-settings",
        )
        entry(
            SettingsCategory.MangaDex,
            mdexGroup,
            localized(SYMR.strings.mangadex_sync_follows_to_library),
            localized(SYMR.strings.mangadex_sync_follows_to_library_summary),
            itemKey = "mangadex-settings",
        )
        entry(
            SettingsCategory.MangaDex,
            mdexGroup,
            localized(SYMR.strings.mangadex_push_favorites_to_mangadex),
            localized(SYMR.strings.mangadex_push_favorites_to_mangadex_summary),
            itemKey = "mangadex-settings",
        )
    }

    // Advanced
    val generalGroup = localized(MR.strings.pref_category_general)
    entry(
        SettingsCategory.Advanced,
        generalGroup,
        localized(DMR.strings.keep_running_in_the_tray),
        localized(DMR.strings.closing_the_window_keeps_zoukan_in),
        itemKey = "advanced-tray",
    )

    val shortcutsGroup = localized(DMR.strings.keyboard_shortcuts_2)
    Shortcuts.list.forEach { (keys, action) ->
        entry(SettingsCategory.Advanced, shortcutsGroup, keys, action, itemKey = "shortcut-$keys")
    }
    entry(
        SettingsCategory.Advanced,
        shortcutsGroup,
        localized(DMR.strings.in_the_reader),
        localized(DMR.strings.press_for_the_readers_shortcuts),
        itemKey = "shortcut-reader",
    )

    val advancedGroup = localized(MR.strings.pref_category_advanced)
    entry(
        SettingsCategory.Advanced,
        advancedGroup,
        localized(MR.strings.pref_dump_crash_logs),
        localized(MR.strings.pref_dump_crash_logs_summary),
        itemKey = "advanced-crash-logs",
    )
    entry(SettingsCategory.Advanced, advancedGroup, localized(MR.strings.pref_onboarding_guide), itemKey = "advanced-onboarding")

    val advancedDataGroup = localized(MR.strings.label_data)
    entry(
        SettingsCategory.Advanced,
        advancedDataGroup,
        localized(MR.strings.pref_invalidate_download_cache),
        localized(MR.strings.pref_invalidate_download_cache_summary),
        itemKey = "advanced-reindex",
    )
    entry(
        SettingsCategory.Advanced,
        advancedDataGroup,
        localized(MR.strings.pref_clear_database),
        localized(MR.strings.pref_clear_database_summary),
        itemKey = "advanced-clear-database",
    )

    val networkGroup = localized(MR.strings.label_network)
    entry(
        SettingsCategory.Advanced,
        networkGroup,
        localized(MR.strings.pref_clear_cookies),
        localized(DMR.strings.signs_you_out_of_sites_and),
        itemKey = "advanced-cookies",
    )
    entry(
        SettingsCategory.Advanced,
        networkGroup,
        localized(MR.strings.pref_clear_webview_data),
        localized(DMR.strings.the_built_in_browsers_cookies_and),
        itemKey = "advanced-webview",
    )
    entry(
        SettingsCategory.Advanced,
        networkGroup,
        localized(DMR.strings.block_ads_and_trackers),
        localized(DMR.strings.block_ads_and_trackers_summary),
        itemKey = "advanced-content-blocker",
    )
    entry(
        SettingsCategory.Advanced,
        networkGroup,
        localized(DMR.strings.extra_block_lists),
        localized(DMR.strings.extra_block_lists_summary),
        itemKey = "advanced-block-lists",
    )
    entry(
        SettingsCategory.Advanced,
        networkGroup,
        localized(MR.strings.pref_dns_over_https),
        keywords = listOf(
            localized(MR.strings.disabled),
            localized(KMR.strings.theme_cloudflare),
            "Google",
            "AdGuard",
            "Quad9",
            "AliDNS",
            "DNSPod",
            "360",
            "Mullvad",
            "Control D",
            "Njalla",
        ),
        itemKey = "advanced-doh",
    )
    entry(SettingsCategory.Advanced, networkGroup, localized(MR.strings.pref_user_agent_string), itemKey = "advanced-user-agent")
    entry(SettingsCategory.Advanced, networkGroup, localized(MR.strings.pref_reset_user_agent_string), itemKey = "advanced-reset-user-agent")

    val advLibraryGroup = localized(MR.strings.label_library)
    entry(SettingsCategory.Advanced, advLibraryGroup, localized(MR.strings.pref_refresh_library_covers), itemKey = "advanced-refresh-covers")
    entry(
        SettingsCategory.Advanced,
        advLibraryGroup,
        localized(MR.strings.pref_reset_viewer_flags),
        localized(MR.strings.pref_reset_viewer_flags_summary),
        itemKey = "advanced-reset-viewer",
    )
    entry(
        SettingsCategory.Advanced,
        advLibraryGroup,
        localized(MR.strings.pref_update_library_manga_titles),
        localized(MR.strings.pref_update_library_manga_titles_summary),
        itemKey = "advanced-update-titles",
    )
    entry(
        SettingsCategory.Advanced,
        advLibraryGroup,
        localized(MR.strings.pref_disallow_non_ascii_filenames),
        localized(MR.strings.pref_disallow_non_ascii_filenames_details),
        itemKey = "advanced-non-ascii",
    )

    val advDownloadsGroup = localized(MR.strings.pref_category_downloads)
    entry(
        SettingsCategory.Advanced,
        advDownloadsGroup,
        localized(SYMR.strings.pref_include_chapter_url_hash),
        localized(SYMR.strings.pref_include_chapter_url_hash_desc),
        itemKey = "advanced-url-hash",
    )
    entry(
        SettingsCategory.Advanced,
        advDownloadsGroup,
        localized(SYMR.strings.clean_up_downloaded_chapters),
        localized(SYMR.strings.delete_unused_chapters),
        itemKey = "advanced-cleanup-downloads",
    )

    val advExtensionsGroup = localized(MR.strings.label_extensions)
    entry(
        SettingsCategory.Advanced,
        advExtensionsGroup,
        localized(MR.strings.ext_revoke_trust),
        localized(DMR.strings.extensions_trusted_on_this_computer_must),
        itemKey = "advanced-revoke-trust",
    )

    val devToolsGroup = localized(SYMR.strings.developer_tools)
    entry(
        SettingsCategory.Advanced,
        devToolsGroup,
        localized(SYMR.strings.toggle_delegated_sources),
        localized(DMR.strings.apply_komikku_enhancements_to_the_following),
        itemKey = "advanced-delegated",
    )
    entry(
        SettingsCategory.Advanced,
        devToolsGroup,
        localized(SYMR.strings.enable_source_blacklist),
        localized(DMR.strings.hide_extensions_sources_that_are_incompatible),
        itemKey = "advanced-source-blacklist",
    )
    entry(
        SettingsCategory.Advanced,
        devToolsGroup,
        localized(SYMR.strings.log_level),
        keywords = listOf(
            localized(DMR.strings.minimal_critical_errors_only),
            localized(DMR.strings.extra_log_everything),
            localized(DMR.strings.extreme_network_inspection_mode),
        ),
        itemKey = "advanced-log-level",
    )

    // About
    val aboutGroup = localized(MR.strings.pref_category_about)
    entry(SettingsCategory.About, aboutGroup, localized(DMR.strings.zoukan, AppVersion.name), itemKey = "about-version")
    entry(SettingsCategory.About, aboutGroup, localized(MR.strings.check_for_updates), itemKey = "about-update")
    entry(
        SettingsCategory.About,
        aboutGroup,
        localized(MR.strings.whats_new),
        localized(DMR.strings.release_notes_for_zoukan, AppVersion.name),
        itemKey = "about-whats-new",
    )
    entry(
        SettingsCategory.About,
        aboutGroup,
        localized(DMR.strings.check_for_updates_automatically),
        localized(DMR.strings.once_a_day_while_zoukan_runs),
        itemKey = "about-auto-update",
    )
    entry(
        SettingsCategory.About,
        aboutGroup,
        localized(DMR.strings.include_test_releases),
        localized(DMR.strings.releases_marked_as_pre_release_on),
        itemKey = "about-prereleases",
    )
    entry(
        SettingsCategory.About,
        aboutGroup,
        localized(DMR.strings.github_token),
        keywords = listOf(localized(DMR.strings.github_token_set)),
        itemKey = "about-token",
    )
    entry(
        SettingsCategory.About,
        aboutGroup,
        localized(DMR.strings.open_the_log_folder),
        localized(DMR.strings.zoukan_log_has_what_zoukan_logged),
        itemKey = "about-logs",
    )
    entry(SettingsCategory.About, aboutGroup, localized(DMR.strings.crash_logs), itemKey = "about-crashes")
}
