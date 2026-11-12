package zoukan.desktop.ui.common

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * The settings the desktop UI reads. Keys are the Android app's where the setting exists there
 * (UiPreferences, ReaderPreferences, SourcePreferences), so they travel in backups both ways.
 */
class DesktopPreferences(private val store: PreferenceStore) {
    // ZOUKAN -->
    init {
        // The paged scale type was a desktop key with its own numbering; it is Android's
        // imageScaleType now, so it moves with backups
        val old = store.getInt(OLD_FIT_MODE_KEY, -1)
        if (old.isSet()) {
            if (!imageScaleType().isSet()) imageScaleType().set(FitModes.fromOldDesktopValue(old.get()))
            old.delete()
        }
    }
    // ZOUKAN <--

    /** ReadingMode flag values: 1 left to right, 2 right to left, 3 vertical, 4 webtoon, 5 continuous vertical */
    fun defaultReadingMode() = store.getInt("pref_default_reading_mode_key", ReadingModes.RIGHT_TO_LEFT)

    fun showPageNumber() = store.getBoolean("pref_show_page_number_key", true)

    /** 0 to 25, percent of the window width on each side in webtoon mode */
    fun webtoonSidePadding() = store.getInt("webtoon_side_padding", 0)

    /** 0 white, 1 black, 2 gray, 3 automatic, as Android's reader theme */
    fun readerTheme() = store.getInt("pref_reader_theme_key", 1)

    fun alwaysShowChapterTransition() = store.getBoolean("always_show_chapter_transition", true)

    fun showNsfwSource() = store.getBoolean("show_nsfw_source", true)

    fun enabledLanguages() = store.getStringSet("source_languages", setOf("all", "en"))

    fun pinnedSources() = store.getStringSet("pinned_catalogues", emptySet())

    fun lastUsedSource() = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)

    fun libraryColumns() = store.getInt("pref_library_columns_landscape_key", 0)

    fun libraryShowUnreadBadge() = store.getBoolean("display_unread_badge", true)

    /** BasePreferences.downloadedOnly() on Android: the library and Updates show only downloaded entries */
    fun downloadedOnly() = store.getBoolean(Preference.appStateKey("pref_downloaded_only"), false)

    /** Desktop only: closing the window keeps Zoukan running in the tray, so background work goes on */
    fun closeToTray() = store.getBoolean("zoukan_close_to_tray", true)

    /** Desktop only: where the window was when Zoukan closed (WindowBounds), to open there again */
    fun windowBounds() = store.getString(Preference.appStateKey("zoukan_window_bounds"), "")

    /** Desktop only: set once the user has been told that closing keeps Zoukan in the tray */
    fun toldAboutTray() = store.getBoolean(Preference.appStateKey("zoukan_told_about_tray"), false)

    /** Set when the first-run setup is finished or skipped; Android's BasePreferences.shownOnboardingFlow */
    fun shownOnboardingFlow() = store.getBoolean(Preference.appStateKey("onboarding_complete"), false)

    /** Desktop only: the version that last ran, so the first launch after an update can say what's new */
    fun lastVersion() = store.getString(Preference.appStateKey("zoukan_last_version"), "")

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

    // ZOUKAN -->
    /** The scale type of the paged modes, as Android's imageScaleType: a [FitModes] value */
    fun imageScaleType() = store.getInt("pref_image_scale_type_key", FitModes.FIT_SCREEN)

    /** A [WebtoonScaleTypes] name: KMK's "Smart scale on wide screen" in webtoon mode */
    fun webtoonScaleType() = store.getString("pref_webtoon_scale_type_key", WebtoonScaleTypes.FIT)

    /** KMK's "Apply Smart scale to Long strip with gaps" */
    fun longStripGapSmartScale() = store.getBoolean("pref_webtoon_smart_scale_long_strip_gap", false)

    /** Android's "Show tap zones overlay": briefly shows the tap zones when the reader opens */
    fun showNavigationOverlayOnStart() = store.getBoolean("reader_navigation_overlay_on_start", false)

    /** Set until the reader has shown its tap zones once, as Android shows them to a new user */
    fun showNavigationOverlayNewUser() = store.getBoolean("reader_navigation_overlay_new_user", true)
    // ZOUKAN <--

    // ZOUKAN -->
    /** The reader menu's page slider stays horizontal, as Android's "Force horizontal seekbar" */
    fun forceHorizontalSeekbar() = store.getBoolean("pref_force_horz_seekbar", false)

    /** A vertical page slider in vertical modes even when the window is wider than tall */
    fun landscapeVerticalSeekbar() = store.getBoolean("pref_show_vert_seekbar_landscape", false)

    /** The vertical page slider on the left */
    fun leftVerticalSeekbar() = store.getBoolean("pref_left_handed_vertical_seekbar", false)

    /** The buttons of the reader menu's bottom row, as Android's reader_bottom_buttons */
    fun readerBottomButtons() = store.getStringSet("reader_bottom_buttons", ReaderBottomButtons.DEFAULTS)

    // Settings > Reader: Android's ReaderPreferences keys and defaults

    /** Tap zones of the paged modes: 0 default, 1 L shaped, 2 Kindle-ish, 3 Edge, 4 Right and left, 5 Disabled */
    fun navigationModePager() = store.getInt("reader_navigation_mode_pager", 0)

    fun navigationModeWebtoon() = store.getInt("reader_navigation_mode_webtoon", 0)

    /** A TappingInvertMode name: NONE, HORIZONTAL, VERTICAL or BOTH */
    fun pagerNavInverted() = store.getString("reader_tapping_inverted", "NONE")

    fun webtoonNavInverted() = store.getString("reader_tapping_inverted_webtoon", "NONE")

    fun smallerTapZone() = store.getBoolean("reader_navigation_smaller_tap_zone", false)

    fun navigateToPan() = store.getBoolean("navigate_pan", true)

    fun readWithLongTap() = store.getBoolean("reader_long_tap", true)

    fun pageTransitionsPager() = store.getBoolean("pref_enable_transitions_pager_key", true)

    fun pageTransitionsWebtoon() = store.getBoolean("pref_enable_transitions_webtoon_key", true)

    fun pagedDoubleTapZoomEnabled() = store.getBoolean("pref_enable_double_tap_zoom_paged", true)

    fun webtoonDoubleTapZoomEnabled() = store.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    fun webtoonPinchToZoomEnabled() = store.getBoolean("pref_enable_pinch_to_zoom_webtoon", true)

    fun pagedDisableZoomIn() = store.getBoolean("paged_disable_zoom_in", false)

    fun webtoonDisableZoomOut() = store.getBoolean("webtoon_disable_zoom_out", false)

    /** Where a page wider than the window starts: 1 automatic, 2 left, 3 right, 4 center */
    fun zoomStart() = store.getInt("pref_zoom_start_key", 1)

    fun dualPageSplitPaged() = store.getBoolean("pref_dual_page_split", false)

    fun dualPageInvertPaged() = store.getBoolean("pref_dual_page_invert", false)

    fun dualPageSplitWebtoon() = store.getBoolean("pref_dual_page_split_webtoon", false)

    fun dualPageInvertWebtoon() = store.getBoolean("pref_dual_page_invert_webtoon", false)

    fun dualPageRotateToFit() = store.getBoolean("pref_dual_page_rotate", false)

    fun dualPageRotateToFitInvert() = store.getBoolean("pref_dual_page_rotate_invert", false)

    fun dualPageRotateToFitWebtoon() = store.getBoolean("pref_dual_page_rotate_webtoon", false)

    fun dualPageRotateToFitInvertWebtoon() = store.getBoolean("pref_dual_page_rotate_invert_webtoon", false)

    /** 0 none, 1 between double pages, 2 in wide pages, 3 both */
    fun centerMarginType() = store.getInt("center_margin_type", 0)

    fun useAutoWebtoon() = store.getBoolean("eh_use_auto_webtoon", true)

    fun preloadSize() = store.getInt("eh_preload_size", 10)

    fun readerThreads() = store.getInt("eh_reader_threads", 2)

    fun aggressivePageLoading() = store.getBoolean("eh_aggressive_page_loading", false)

    fun readerInstantRetry() = store.getBoolean("eh_reader_instant_retry", true)

    fun preserveReadingPosition() = store.getBoolean("eh_preserve_reading_position", false)

    fun keepScreenOn() = store.getBoolean("pref_keep_screen_on_key", false)

    fun showReadingMode() = store.getBoolean("pref_show_reading_mode", true)

    fun smoothAutoScroll() = store.getBoolean("smooth_auto_scroll", true)

    fun flashOnPageChange() = store.getBoolean("pref_reader_flash", false)

    fun flashDurationMillis() = store.getInt("pref_reader_flash_duration", 100)

    fun flashPageInterval() = store.getInt("pref_reader_flash_interval", 1)

    /** A FlashColor name: BLACK, WHITE or WHITE_BLACK */
    fun flashColor() = store.getString("pref_reader_flash_mode", "BLACK")
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

    /** Desktop only: SY's "Retry all" and "Boost page", from its E-Hentai utilities bar */
    const val RETRY_ALL = "zra"
    const val BOOST_PAGE = "zbp"

    val DEFAULTS = setOf(VIEW_CHAPTERS, WEB_VIEW, CROP_PAGER, CROP_CONTINUOUS_VERTICAL, PAGE_LAYOUT, READING_MODE, SCALE_TYPE)

    val all = listOf(
        VIEW_CHAPTERS to localized(MR.strings.chapters),
        WEB_VIEW to localized(MR.strings.action_open_in_web_view),
        BROWSER to localized(MR.strings.action_open_in_browser),
        READING_MODE to localized(MR.strings.viewer),
        SCALE_TYPE to localized(MR.strings.pref_image_scale_type),
        CROP_PAGER to localized(DMR.strings.crop_borders_paged),
        CROP_CONTINUOUS_VERTICAL to localized(DMR.strings.crop_borders_continuous_vertical),
        CROP_WEBTOON to localized(DMR.strings.crop_borders_webtoon),
        PAGE_LAYOUT to localized(SYMR.strings.page_layout),
        RETRY_ALL to localized(DMR.strings.retry_failed_pages),
        BOOST_PAGE to localized(DMR.strings.load_this_page_first),
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
        LEFT_TO_RIGHT -> localized(MR.strings.left_to_right_viewer)
        RIGHT_TO_LEFT -> localized(MR.strings.right_to_left_viewer)
        VERTICAL -> localized(MR.strings.vertical_viewer)
        WEBTOON -> localized(SYMR.strings.entry_type_webtoon)
        CONTINUOUS_VERTICAL -> localized(DMR.strings.continuous_vertical)
        else -> localized(MR.strings.label_default)
    }
}

object FitModes {
    // ZOUKAN -->
    // Android's imageScaleType values (the SubsamplingScaleImageView scale types)
    const val FIT_SCREEN = 1
    const val STRETCH = 2
    const val FIT_WIDTH = 3
    const val FIT_HEIGHT = 4
    const val ORIGINAL = 5
    const val SMART_FIT = 6

    val all = listOf(FIT_SCREEN, STRETCH, FIT_WIDTH, FIT_HEIGHT, ORIGINAL, SMART_FIT)

    fun label(mode: Int) = when (mode) {
        STRETCH -> localized(MR.strings.scale_type_stretch)
        FIT_WIDTH -> localized(MR.strings.scale_type_fit_width)
        FIT_HEIGHT -> localized(MR.strings.scale_type_fit_height)
        ORIGINAL -> localized(MR.strings.scale_type_original_size)
        SMART_FIT -> localized(MR.strings.scale_type_smart_fit)
        else -> localized(MR.strings.scale_type_fit_screen)
    }

    /** The old desktop-only fit mode (0 fit screen, 1 width, 2 height, 3 original) as an imageScaleType */
    fun fromOldDesktopValue(value: Int) = when (value) {
        1 -> FIT_WIDTH
        2 -> FIT_HEIGHT
        3 -> ORIGINAL
        else -> FIT_SCREEN
    }

    /**
     * The scale of a page of [page] pixels in a [viewWidth] by [viewHeight] window, as
     * SubsamplingScaleImageView's minimum scale for each type. [original] is the scale of
     * Original size (the screen density).
     */
    fun scale(mode: Int, pageWidth: Int, pageHeight: Int, viewWidth: Int, viewHeight: Int, original: Float): Float {
        val w = pageWidth.coerceAtLeast(1).toFloat()
        val h = pageHeight.coerceAtLeast(1).toFloat()
        val toWidth = viewWidth / w
        val toHeight = viewHeight / h
        return when (mode) {
            // Center crop: fills the window, the overflow pans
            STRETCH -> maxOf(toWidth, toHeight)
            FIT_WIDTH -> toWidth
            FIT_HEIGHT -> toHeight
            ORIGINAL -> original
            // A tall page fits the width, a wide one the height
            SMART_FIT -> if (h > w) toWidth else toHeight
            else -> minOf(toWidth, toHeight)
        }
    }
    // ZOUKAN <--
}

// ZOUKAN -->
/** KMK's WebtoonScaleType: the strip narrows to this width-to-height ratio of the window when the window is wider */
object WebtoonScaleTypes {
    const val FIT = "FIT"

    val all = listOf(
        FIT to localized(MR.strings.scale_type_fit_screen),
        "R4_3" to localized(KMR.strings.scale_type_4_3),
        "R3_2" to localized(KMR.strings.scale_type_3_2),
        "R16_9" to localized(KMR.strings.scale_type_16_9),
        "R20_9" to localized(KMR.strings.scale_type_20_9),
    )

    fun ratio(name: String): Float = when (name) {
        "R4_3" -> 3f / 4f
        "R3_2" -> 2f / 3f
        "R16_9" -> 9f / 16f
        "R20_9" -> 9f / 20f
        else -> 0f
    }

    /**
     * The strip's scale in a [width] by [height] window, as KMK's webtoon viewer: 1 unless the
     * window is wider than the ratio, then the scale that makes the strip that ratio of the height.
     */
    fun scale(name: String, width: Float, height: Float): Float {
        val ratio = ratio(name)
        if (ratio <= 0f || width <= 0f || height <= 0f) return 1f
        return if (width / height > ratio) height * ratio / width else 1f
    }
}

private const val OLD_FIT_MODE_KEY = "zoukan_reader_fit_mode"
// ZOUKAN <--
