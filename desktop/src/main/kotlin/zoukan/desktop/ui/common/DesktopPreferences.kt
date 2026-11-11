package zoukan.desktop.ui.common

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The settings the desktop UI reads. Keys are the Android app's where the setting exists there
 * (UiPreferences, ReaderPreferences, SourcePreferences), so they travel in backups both ways.
 */
class DesktopPreferences(private val store: PreferenceStore) {
    /** ReadingMode flag values: 1 left to right, 2 right to left, 3 vertical, 4 webtoon, 5 continuous vertical */
    fun defaultReadingMode() = store.getInt("pref_default_reading_mode_key", ReadingModes.RIGHT_TO_LEFT)

    fun showPageNumber() = store.getBoolean("pref_show_page_number_key", true)

    /** 0 to 25, percent of the window width on each side in webtoon mode */
    fun webtoonSidePadding() = store.getInt("webtoon_side_padding", 0)

    /** 0 white, 1 black, 2 gray, as Android's reader theme */
    fun readerTheme() = store.getInt("pref_reader_theme_key", 1)

    fun alwaysShowChapterTransition() = store.getBoolean("always_show_chapter_transition", true)

    fun showNsfwSource() = store.getBoolean("show_nsfw_source", true)

    fun enabledLanguages() = store.getStringSet("source_languages", setOf("all", "en"))

    fun pinnedSources() = store.getStringSet("pinned_catalogues", emptySet())

    fun lastUsedSource() = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)

    fun libraryColumns() = store.getInt("pref_library_columns_landscape_key", 0)

    fun libraryShowUnreadBadge() = store.getBoolean("display_unread_badge", true)

    /** Desktop only: closing the window keeps Zoukan running in the tray, so background work goes on */
    fun closeToTray() = store.getBoolean("zoukan_close_to_tray", true)

    /** Desktop only: set once the user has been told that closing keeps Zoukan in the tray */
    fun toldAboutTray() = store.getBoolean(Preference.appStateKey("zoukan_told_about_tray"), false)

    // Reader filters, as Android's ReaderPreferences
    fun customBrightness() = store.getBoolean("pref_custom_brightness_key", false)

    /** -75 to 100; below zero dims the page */
    fun customBrightnessValue() = store.getInt("custom_brightness_value", 0)

    fun colorFilter() = store.getBoolean("pref_color_filter_key", false)

    /** ARGB */
    fun colorFilterValue() = store.getInt("color_filter_value", 0)

    /** 0 default, 1 multiply, 2 screen, 3 overlay, 4 lighten, 5 darken */
    fun colorFilterMode() = store.getInt("color_filter_mode", 0)

    fun grayscale() = store.getBoolean("pref_grayscale", false)

    fun invertedColors() = store.getBoolean("pref_inverted_colors", false)

    // Chapter navigation, as Android's ReaderPreferences
    fun skipRead() = store.getBoolean("skip_read", false)

    fun skipFiltered() = store.getBoolean("skip_filtered", true)

    fun skipDupe() = store.getBoolean("skip_dupe", false)

    /** 0 single page, 1 double pages, 2 automatic (double when the window is wide), as Android's PageLayout */
    fun pageLayout() = store.getInt("page_layout", 2)

    fun invertDoublePages() = store.getBoolean("invert_double_pages", false)

    fun cropBorders() = store.getBoolean("crop_borders", false)

    fun cropBordersWebtoon() = store.getBoolean("crop_borders_webtoon", false)

    /** Seconds between page turns (or screens in webtoon modes) when auto scroll is on, as SY's */
    fun autoscrollInterval() = store.getFloat("eh_util_autoscroll_interval", 3f)

    // ZOUKAN -->
    /** Saved pages go into a folder per entry, as Android's "Save pages into separate folders" */
    fun folderPerManga() = store.getBoolean("create_folder_per_manga", false)
    // ZOUKAN <--

    /** Desktop only: the reader's zoom in paged modes is kept per window, not per manga */
    fun readerFitMode() = store.getInt("zoukan_reader_fit_mode", FitModes.FIT_SCREEN)

    // ZOUKAN -->
    /** The reader menu's page slider stays horizontal, as Android's "Force horizontal seekbar" */
    fun forceHorizontalSeekbar() = store.getBoolean("pref_force_horz_seekbar", false)

    /** A vertical page slider in vertical modes even when the window is wider than tall */
    fun landscapeVerticalSeekbar() = store.getBoolean("pref_show_vert_seekbar_landscape", false)

    /** The vertical page slider on the left */
    fun leftVerticalSeekbar() = store.getBoolean("pref_left_handed_vertical_seekbar", false)

    /** The buttons of the reader menu's bottom row, as Android's reader_bottom_buttons */
    fun readerBottomButtons() = store.getStringSet("reader_bottom_buttons", ReaderBottomButtons.DEFAULTS)
    // ZOUKAN <--
}

// ZOUKAN -->
/** The reader menu's bottom row buttons, by Android's ReaderBottomButton values */
object ReaderBottomButtons {
    const val VIEW_CHAPTERS = "vc"
    const val WEB_VIEW = "wb"
    const val BROWSER = "br"
    const val READING_MODE = "rm"
    const val CROP_PAGER = "cbp"
    const val CROP_CONTINUOUS_VERTICAL = "cbc"
    const val CROP_WEBTOON = "cbw"
    const val PAGE_LAYOUT = "pl"

    /** Desktop only, in place of Android's rotation button */
    const val SCALE_TYPE = "zst"

    val DEFAULTS = setOf(VIEW_CHAPTERS, WEB_VIEW, CROP_PAGER, CROP_CONTINUOUS_VERTICAL, PAGE_LAYOUT, READING_MODE, SCALE_TYPE)

    val all = listOf(
        VIEW_CHAPTERS to "Chapters",
        WEB_VIEW to "Open in WebView",
        BROWSER to "Open in browser",
        READING_MODE to "Reading mode",
        SCALE_TYPE to "Scale type",
        CROP_PAGER to "Crop borders (paged)",
        CROP_CONTINUOUS_VERTICAL to "Crop borders (continuous vertical)",
        CROP_WEBTOON to "Crop borders (webtoon)",
        PAGE_LAYOUT to "Page layout",
    )
}
// ZOUKAN <--

object ReadingModes {
    const val DEFAULT = 0
    const val LEFT_TO_RIGHT = 1
    const val RIGHT_TO_LEFT = 2
    const val VERTICAL = 3
    const val WEBTOON = 4
    const val CONTINUOUS_VERTICAL = 5
    const val MASK = 0x7

    val all = listOf(LEFT_TO_RIGHT, RIGHT_TO_LEFT, VERTICAL, WEBTOON, CONTINUOUS_VERTICAL)

    fun label(mode: Int) = when (mode) {
        LEFT_TO_RIGHT -> "Paged (left to right)"
        RIGHT_TO_LEFT -> "Paged (right to left)"
        VERTICAL -> "Paged (vertical)"
        WEBTOON -> "Webtoon"
        CONTINUOUS_VERTICAL -> "Continuous vertical"
        else -> "Default"
    }
}

object FitModes {
    const val FIT_SCREEN = 0
    const val FIT_WIDTH = 1
    const val FIT_HEIGHT = 2
    const val ORIGINAL = 3

    val all = listOf(FIT_SCREEN, FIT_WIDTH, FIT_HEIGHT, ORIGINAL)

    fun label(mode: Int) = when (mode) {
        FIT_WIDTH -> "Fit width"
        FIT_HEIGHT -> "Fit height"
        ORIGINAL -> "Original size"
        else -> "Fit screen"
    }
}
