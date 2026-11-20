package zoukan.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_display
import tachiyomi.i18n.action_menu
import tachiyomi.i18n.chapters
import tachiyomi.i18n.color_filter_a_value
import tachiyomi.i18n.color_filter_b_value
import tachiyomi.i18n.color_filter_g_value
import tachiyomi.i18n.color_filter_r_value
import tachiyomi.i18n.filter_mode_multiply
import tachiyomi.i18n.filter_mode_screen
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kmk.pref_paged_disable_zoom_in
import tachiyomi.i18n.kmk.pref_pinch_to_zoom
import tachiyomi.i18n.kmk.pref_smart_scale_long_strip_gap
import tachiyomi.i18n.kmk.pref_viewer_nav_smaller_tap_zone
import tachiyomi.i18n.kmk.pref_webtoon_scale_type
import tachiyomi.i18n.label_default
import tachiyomi.i18n.overlay_header
import tachiyomi.i18n.pager_viewer
import tachiyomi.i18n.pref_always_show_chapter_transition
import tachiyomi.i18n.pref_create_folder_per_manga
import tachiyomi.i18n.pref_create_folder_per_manga_summary
import tachiyomi.i18n.pref_crop_borders
import tachiyomi.i18n.pref_custom_brightness
import tachiyomi.i18n.pref_double_tap_zoom
import tachiyomi.i18n.pref_dual_page_invert
import tachiyomi.i18n.pref_dual_page_split
import tachiyomi.i18n.pref_flash_duration
import tachiyomi.i18n.pref_flash_duration_summary
import tachiyomi.i18n.pref_flash_page
import tachiyomi.i18n.pref_flash_page_interval
import tachiyomi.i18n.pref_flash_with
import tachiyomi.i18n.pref_grayscale
import tachiyomi.i18n.pref_image_scale_type
import tachiyomi.i18n.pref_keep_screen_on
import tachiyomi.i18n.pref_page_rotate
import tachiyomi.i18n.pref_page_rotate_invert
import tachiyomi.i18n.pref_page_transitions
import tachiyomi.i18n.pref_read_with_long_tap
import tachiyomi.i18n.pref_read_with_tapping_inverted
import tachiyomi.i18n.pref_reader_theme
import tachiyomi.i18n.pref_show_navigation_mode
import tachiyomi.i18n.pref_show_navigation_mode_summary
import tachiyomi.i18n.pref_show_page_number
import tachiyomi.i18n.pref_show_reading_mode
import tachiyomi.i18n.pref_skip_dupe_chapters
import tachiyomi.i18n.pref_skip_filtered_chapters
import tachiyomi.i18n.pref_skip_read_chapters
import tachiyomi.i18n.pref_viewer_nav
import tachiyomi.i18n.pref_webtoon_disable_zoom_out
import tachiyomi.i18n.pref_webtoon_side_padding
import tachiyomi.i18n.pref_zoom_start
import tachiyomi.i18n.reading
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.double_pages
import tachiyomi.i18n.sy.entry_type_webtoon
import tachiyomi.i18n.sy.invert_double_pages
import tachiyomi.i18n.sy.page_layout
import tachiyomi.i18n.sy.single_page
import tachiyomi.i18n.vertical_plus_viewer
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReaderBottomButtons
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.RowCheckbox
import zoukan.desktop.ui.common.RowRadioButton
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.checkRow
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.radioRow
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.reader.ReaderSettingChoices
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.aggressive_page_loading
import zoukan.i18n.desktop.auto_scroll
import zoukan.i18n.desktop.auto_webtoon_mode
import zoukan.i18n.desktop.automatic_double_in_a_wide_window
import zoukan.i18n.desktop.blend_mode
import zoukan.i18n.desktop.bottom_bar_buttons
import zoukan.i18n.desktop.briefly_shows_the_current_mode_when
import zoukan.i18n.desktop.brightness
import zoukan.i18n.desktop.center_margin
import zoukan.i18n.desktop.color_filter
import zoukan.i18n.desktop.color_laid_over_pages
import zoukan.i18n.desktop.darken
import zoukan.i18n.desktop.download_every_page_of_an_online
import zoukan.i18n.desktop.every_seconds_a_starts_and_stops
import zoukan.i18n.desktop.force_horizontal_page_slider
import zoukan.i18n.desktop.in_vertical_modes_also_when_the
import zoukan.i18n.desktop.instant_retry
import zoukan.i18n.desktop.inverted_colors
import zoukan.i18n.desktop.keep_one_chapter_per_number_the
import zoukan.i18n.desktop.left_handed_vertical_page_slider
import zoukan.i18n.desktop.lighten
import zoukan.i18n.desktop.open_a_read_chapter_at_its
import zoukan.i18n.desktop.page_1
import zoukan.i18n.desktop.page_loading
import zoukan.i18n.desktop.pages
import zoukan.i18n.desktop.pages_to_preload
import zoukan.i18n.desktop.pan_wide_images_when_tapping
import zoukan.i18n.desktop.preserve_reading_position
import zoukan.i18n.desktop.read_entries_tagged_webtoon_manhwa_or
import zoukan.i18n.desktop.reader_threads
import zoukan.i18n.desktop.reduces_ghosting_on_e_ink_monitors
import zoukan.i18n.desktop.retry_a_failed_page_once_at
import zoukan.i18n.desktop.right_click_always_shows_them
import zoukan.i18n.desktop.smooth_auto_scroll
import zoukan.i18n.desktop.the_ones_the_entrys_chapter_filters
import zoukan.i18n.desktop.vertical_modes_keep_the_slider_at
import zoukan.i18n.desktop.vertical_page_slider_in_a_wide
import zoukan.i18n.desktop.webtoon_modes_glide_instead_of_jumping
import kotlin.math.roundToInt

/**
 * One row of the reader's settings, described once so that Settings > Reader and the reader's own
 * settings sheet list the same setting the same way. [ReaderSettingRows] says which rows each of
 * the two shows.
 */
sealed interface ReaderSettingRow {
    /** A heading; it has no preference. */
    data class Group(val id: String, val title: String) : ReaderSettingRow

    /** A row for one preference, shown only while [shownWhen] (when given) is on. */
    sealed interface Keyed : ReaderSettingRow {
        val preference: Preference<*>
        val shownWhen: Preference<Boolean>?
    }

    data class Switch(
        val title: String,
        override val preference: Preference<Boolean>,
        val subtitle: String? = null,
        override val shownWhen: Preference<Boolean>? = null,
    ) : Keyed

    data class Choice<T>(
        val title: String,
        override val preference: Preference<T>,
        val choices: List<Pair<T, String>>,
        override val shownWhen: Preference<Boolean>? = null,
    ) : Keyed

    data class Slider(
        val title: String,
        override val preference: Preference<Int>,
        val range: IntRange,
        val label: (Int) -> String = { "$it" },
        override val shownWhen: Preference<Boolean>? = null,
    ) : Keyed

    /** A row that needs its own controls, such as the color filter's channels. */
    class Custom(
        val title: StringResource,
        override val preference: Preference<*>,
        override val shownWhen: Preference<Boolean>? = null,
        val content: @Composable () -> Unit,
    ) : Keyed
}

/** What Settings > Reader and the reader's settings sheet show, built from the same rows. */
object ReaderSettingRows {

    /** Settings > Reader: display, reading, the pager and webtoon viewers and page loading. */
    fun settings(prefs: DesktopPreferences): List<ReaderSettingRow> = with(Defs(prefs)) {
        listOf(
            ReaderSettingRow.Group("display", localized(MR.strings.action_display)),
            showReadingMode,
            showNavigationOverlay,
            alwaysShowChapterTransition,
            keepScreenOn,
            flashOnPageChange,
            flashColor,
            flashDuration,
            flashInterval,
            ReaderSettingRow.Group("reading", localized(MR.strings.reading)),
            readWithLongTap,
            smallerTapZone,
            smoothAutoScroll,
            useAutoWebtoon,
            preserveReadingPosition,
            ReaderSettingRow.Group("pager", localized(MR.strings.pager_viewer)),
            navigationModePager,
            pagerNavInverted,
            zoomStart,
            // Where Android's Settings page has them: crop borders after zoom start, and page
            // layout (SY's fork group there) before the center margin
            cropBorders,
            pageLayout,
            centerMargin,
            dualPageSplitPaged,
            dualPageInvertPaged,
            dualPageRotateToFit,
            dualPageRotateToFitInvert,
            navigateToPan,
            pagedDisableZoomIn,
            pagedDoubleTapZoom,
            pageTransitionsPager,
            ReaderSettingRow.Group("webtoon", localized(SYMR.strings.entry_type_webtoon)),
            navigationModeWebtoon,
            webtoonNavInverted,
            webtoonScaleType,
            longStripGapSmartScale,
            cropBordersWebtoon,
            dualPageSplitWebtoon,
            dualPageInvertWebtoon,
            dualPageRotateToFitWebtoon,
            dualPageRotateToFitInvertWebtoon,
            webtoonDoubleTapZoom,
            webtoonPinchToZoom,
            webtoonDisableZoomOut,
            pageTransitionsWebtoon,
            // SY's Vertical+ group: continuous vertical's own crop borders
            ReaderSettingRow.Group("vertical_plus", localized(MR.strings.vertical_plus_viewer)),
            cropBordersContinuousVertical,
            ReaderSettingRow.Group("loading", localized(DMR.strings.page_loading)),
            preloadSize,
            readerThreads,
            aggressivePageLoading,
            instantRetry,
        )
    }

    /** Everything the reader's settings sheet shows when the reading mode is [readingMode]. */
    fun sheet(prefs: DesktopPreferences, readingMode: Int): List<ReaderSettingRow> =
        sheetViewer(prefs, readingMode) + sheetGeneral(prefs) + sheetFilter(prefs)

    /** The sheet's Viewer tab, below the reading mode picker. */
    fun sheetViewer(prefs: DesktopPreferences, readingMode: Int): List<ReaderSettingRow> = with(Defs(prefs)) {
        if (readingMode == ReadingModes.WEBTOON || readingMode == ReadingModes.CONTINUOUS_VERTICAL) {
            listOf(
                ReaderSettingRow.Group("webtoon", localized(SYMR.strings.entry_type_webtoon)),
                webtoonSidePadding,
                cropBordersWebtoon,
                // Long strip with gaps only follows the scale type when its smart scale is on
                if (readingMode == ReadingModes.CONTINUOUS_VERTICAL) webtoonScaleType.copy(shownWhen = prefs.longStripGapSmartScale()) else webtoonScaleType,
                navigationModeWebtoon,
                webtoonNavInverted,
                dualPageSplitWebtoon,
                dualPageInvertWebtoon,
                dualPageRotateToFitWebtoon,
                dualPageRotateToFitInvertWebtoon,
                webtoonDoubleTapZoom,
                webtoonPinchToZoom,
                webtoonDisableZoomOut,
                pageTransitionsWebtoon,
                // As Android's sheet, under both webtoon modes: SY's Vertical+ crop borders
                ReaderSettingRow.Group("vertical_plus", localized(MR.strings.vertical_plus_viewer)),
                cropBordersContinuousVertical,
            )
        } else {
            listOf(
                imageScaleType,
                pageLayout,
                invertDoublePages,
                centerMargin,
                cropBorders,
                navigationModePager,
                pagerNavInverted,
                zoomStart,
                dualPageSplitPaged,
                dualPageInvertPaged,
                dualPageRotateToFit,
                dualPageRotateToFitInvert,
                navigateToPan,
                pagedDisableZoomIn,
                pagedDoubleTapZoom,
                pageTransitionsPager,
            )
        }
    }

    /** The sheet's General tab. */
    fun sheetGeneral(prefs: DesktopPreferences): List<ReaderSettingRow> = with(Defs(prefs)) {
        listOf(
            readerTheme,
            showPageNumber,
            alwaysShowChapterTransition,
            ReaderSettingRow.Group("chapters", localized(MR.strings.chapters)),
            skipRead,
            skipFiltered,
            skipDupe,
            folderPerManga,
            ReaderSettingRow.Group("menu", localized(MR.strings.action_menu)),
            forceHorizontalSeekbar,
            landscapeVerticalSeekbar,
            leftVerticalSeekbar,
            bottomButtons,
            showReadingMode,
            keepScreenOn,
            readWithLongTap,
            smallerTapZone,
            ReaderSettingRow.Group("autoscroll", localized(DMR.strings.auto_scroll)),
            autoscrollInterval,
            smoothAutoScroll,
        )
    }

    /** The sheet's Filter tab. */
    fun sheetFilter(prefs: DesktopPreferences): List<ReaderSettingRow> = with(Defs(prefs)) {
        listOf(
            customBrightness,
            brightnessValue,
            colorFilter,
            colorFilterValue,
            colorFilterMode,
            grayscale,
            invertedColors,
        )
    }

    /** Every row, defined once; the lists above pick from these. */
    private class Defs(val p: DesktopPreferences) {
        val showReadingMode = ReaderSettingRow.Switch(localized(MR.strings.pref_show_reading_mode), p.showReadingMode(), localized(DMR.strings.briefly_shows_the_current_mode_when))
        val showNavigationOverlay = ReaderSettingRow.Switch(localized(MR.strings.pref_show_navigation_mode), p.showNavigationOverlayOnStart(), localized(MR.strings.pref_show_navigation_mode_summary))
        val alwaysShowChapterTransition = ReaderSettingRow.Switch(localized(MR.strings.pref_always_show_chapter_transition), p.alwaysShowChapterTransition())
        val keepScreenOn = ReaderSettingRow.Switch(localized(MR.strings.pref_keep_screen_on), p.keepScreenOn())
        val flashOnPageChange = ReaderSettingRow.Switch(localized(MR.strings.pref_flash_page), p.flashOnPageChange(), localized(DMR.strings.reduces_ghosting_on_e_ink_monitors))
        val flashColor = ReaderSettingRow.Choice(localized(MR.strings.pref_flash_with), p.flashColor(), ReaderSettingChoices.flashColors, shownWhen = p.flashOnPageChange())
        val flashDuration = ReaderSettingRow.Custom(MR.strings.pref_flash_duration, p.flashDurationMillis(), shownWhen = p.flashOnPageChange()) { FlashDurationPreference(p) }
        val flashInterval = ReaderSettingRow.Slider(
            localized(MR.strings.pref_flash_page_interval),
            p.flashPageInterval(),
            1..10,
            { if (it == 1) localized(DMR.strings.page_1) else localized(DMR.strings.pages, it) },
            shownWhen = p.flashOnPageChange(),
        )

        val readWithLongTap = ReaderSettingRow.Switch(localized(MR.strings.pref_read_with_long_tap), p.readWithLongTap(), localized(DMR.strings.right_click_always_shows_them))
        val smallerTapZone = ReaderSettingRow.Switch(localized(KMR.strings.pref_viewer_nav_smaller_tap_zone), p.smallerTapZone())
        val smoothAutoScroll = ReaderSettingRow.Switch(localized(DMR.strings.smooth_auto_scroll), p.smoothAutoScroll(), localized(DMR.strings.webtoon_modes_glide_instead_of_jumping))
        val useAutoWebtoon = ReaderSettingRow.Switch(localized(DMR.strings.auto_webtoon_mode), p.useAutoWebtoon(), localized(DMR.strings.read_entries_tagged_webtoon_manhwa_or))
        val preserveReadingPosition = ReaderSettingRow.Switch(localized(DMR.strings.preserve_reading_position), p.preserveReadingPosition(), localized(DMR.strings.open_a_read_chapter_at_its))

        val navigationModePager = ReaderSettingRow.Choice(localized(MR.strings.pref_viewer_nav), p.navigationModePager(), ReaderNavigation.layouts)
        val pagerNavInverted = ReaderSettingRow.Choice(localized(MR.strings.pref_read_with_tapping_inverted), p.pagerNavInverted(), invertChoices)
        val zoomStart = ReaderSettingRow.Choice(localized(MR.strings.pref_zoom_start), p.zoomStart(), ReaderSettingChoices.zoomStarts)
        val centerMargin = ReaderSettingRow.Choice(localized(DMR.strings.center_margin), p.centerMarginType(), ReaderSettingChoices.centerMargins)
        val dualPageSplitPaged = ReaderSettingRow.Switch(localized(MR.strings.pref_dual_page_split), p.dualPageSplitPaged())
        val dualPageInvertPaged = ReaderSettingRow.Switch(localized(MR.strings.pref_dual_page_invert), p.dualPageInvertPaged())
        val dualPageRotateToFit = ReaderSettingRow.Switch(localized(MR.strings.pref_page_rotate), p.dualPageRotateToFit())
        val dualPageRotateToFitInvert = ReaderSettingRow.Switch(localized(MR.strings.pref_page_rotate_invert), p.dualPageRotateToFitInvert())
        val navigateToPan = ReaderSettingRow.Switch(localized(DMR.strings.pan_wide_images_when_tapping), p.navigateToPan())
        val pagedDisableZoomIn = ReaderSettingRow.Switch(localized(KMR.strings.pref_paged_disable_zoom_in), p.pagedDisableZoomIn())
        val pagedDoubleTapZoom = ReaderSettingRow.Switch(localized(MR.strings.pref_double_tap_zoom), p.pagedDoubleTapZoomEnabled())
        val pageTransitionsPager = ReaderSettingRow.Switch(localized(MR.strings.pref_page_transitions), p.pageTransitionsPager())

        val navigationModeWebtoon = ReaderSettingRow.Choice(localized(MR.strings.pref_viewer_nav), p.navigationModeWebtoon(), ReaderNavigation.layouts)
        val webtoonNavInverted = ReaderSettingRow.Choice(localized(MR.strings.pref_read_with_tapping_inverted), p.webtoonNavInverted(), invertChoices)
        val webtoonScaleType = ReaderSettingRow.Choice(localized(KMR.strings.pref_webtoon_scale_type), p.webtoonScaleType(), WebtoonScaleTypes.all)
        val longStripGapSmartScale = ReaderSettingRow.Switch(localized(KMR.strings.pref_smart_scale_long_strip_gap), p.longStripGapSmartScale())
        val dualPageSplitWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_dual_page_split), p.dualPageSplitWebtoon())
        val dualPageInvertWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_dual_page_invert), p.dualPageInvertWebtoon())
        val dualPageRotateToFitWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_page_rotate), p.dualPageRotateToFitWebtoon())
        val dualPageRotateToFitInvertWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_page_rotate_invert), p.dualPageRotateToFitInvertWebtoon())
        val webtoonDoubleTapZoom = ReaderSettingRow.Switch(localized(MR.strings.pref_double_tap_zoom), p.webtoonDoubleTapZoomEnabled())
        val webtoonPinchToZoom = ReaderSettingRow.Switch(localized(KMR.strings.pref_pinch_to_zoom), p.webtoonPinchToZoomEnabled())
        val webtoonDisableZoomOut = ReaderSettingRow.Switch(localized(MR.strings.pref_webtoon_disable_zoom_out), p.webtoonDisableZoomOut())
        val pageTransitionsWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_page_transitions), p.pageTransitionsWebtoon())

        val preloadSize = ReaderSettingRow.Slider(localized(DMR.strings.pages_to_preload), p.preloadSize(), 1..20)
        val readerThreads = ReaderSettingRow.Slider(localized(DMR.strings.reader_threads), p.readerThreads(), 1..5)
        val aggressivePageLoading = ReaderSettingRow.Switch(localized(DMR.strings.aggressive_page_loading), p.aggressivePageLoading(), localized(DMR.strings.download_every_page_of_an_online))
        val instantRetry = ReaderSettingRow.Switch(localized(DMR.strings.instant_retry), p.readerInstantRetry(), localized(DMR.strings.retry_a_failed_page_once_at))

        // The sheet's Viewer tab
        val webtoonSidePadding = ReaderSettingRow.Slider(localized(MR.strings.pref_webtoon_side_padding), p.webtoonSidePadding(), 0..25, { "$it%" })
        val cropBordersWebtoon = ReaderSettingRow.Switch(localized(MR.strings.pref_crop_borders), p.cropBordersWebtoon())
        val cropBordersContinuousVertical = ReaderSettingRow.Switch(localized(MR.strings.pref_crop_borders), p.cropBordersContinuousVertical())
        val cropBorders = ReaderSettingRow.Switch(localized(MR.strings.pref_crop_borders), p.cropBorders())
        val imageScaleType = ReaderSettingRow.Choice(localized(MR.strings.pref_image_scale_type), p.imageScaleType(), FitModes.all.map { it to FitModes.label(it) })
        val pageLayout = ReaderSettingRow.Choice(
            localized(SYMR.strings.page_layout),
            p.pageLayout(),
            listOf(0 to localized(SYMR.strings.single_page), 1 to localized(SYMR.strings.double_pages), 2 to localized(DMR.strings.automatic_double_in_a_wide_window)),
        )
        val invertDoublePages = ReaderSettingRow.Switch(localized(SYMR.strings.invert_double_pages), p.invertDoublePages())

        // The sheet's General tab
        val readerTheme = ReaderSettingRow.Choice(localized(MR.strings.pref_reader_theme), p.readerTheme(), ReaderSettingChoices.themes)
        val showPageNumber = ReaderSettingRow.Switch(localized(MR.strings.pref_show_page_number), p.showPageNumber())
        val skipRead = ReaderSettingRow.Switch(localized(MR.strings.pref_skip_read_chapters), p.skipRead())
        val skipFiltered = ReaderSettingRow.Switch(localized(MR.strings.pref_skip_filtered_chapters), p.skipFiltered(), localized(DMR.strings.the_ones_the_entrys_chapter_filters))
        val skipDupe = ReaderSettingRow.Switch(localized(MR.strings.pref_skip_dupe_chapters), p.skipDupe(), localized(DMR.strings.keep_one_chapter_per_number_the))
        val folderPerManga = ReaderSettingRow.Switch(localized(MR.strings.pref_create_folder_per_manga), p.folderPerManga(), localized(MR.strings.pref_create_folder_per_manga_summary))
        val forceHorizontalSeekbar = ReaderSettingRow.Switch(localized(DMR.strings.force_horizontal_page_slider), p.forceHorizontalSeekbar(), localized(DMR.strings.vertical_modes_keep_the_slider_at))
        val landscapeVerticalSeekbar = ReaderSettingRow.Switch(localized(DMR.strings.vertical_page_slider_in_a_wide), p.landscapeVerticalSeekbar(), localized(DMR.strings.in_vertical_modes_also_when_the))
        val leftVerticalSeekbar = ReaderSettingRow.Switch(localized(DMR.strings.left_handed_vertical_page_slider), p.leftVerticalSeekbar())
        val bottomButtons = ReaderSettingRow.Custom(DMR.strings.bottom_bar_buttons, p.readerBottomButtons()) { BottomButtonsRow(p) }
        val autoscrollInterval = ReaderSettingRow.Custom(DMR.strings.auto_scroll, p.autoscrollInterval()) { AutoScrollIntervalRow(p) }

        // The sheet's Filter tab
        val customBrightness = ReaderSettingRow.Switch(localized(MR.strings.pref_custom_brightness), p.customBrightness())
        val brightnessValue = ReaderSettingRow.Custom(DMR.strings.brightness, p.customBrightnessValue(), shownWhen = p.customBrightness()) { BrightnessRow(p) }
        val colorFilter = ReaderSettingRow.Switch(localized(DMR.strings.color_filter), p.colorFilter())
        val colorFilterValue = ReaderSettingRow.Custom(DMR.strings.color_filter, p.colorFilterValue(), shownWhen = p.colorFilter()) { ColorFilterRow(p) }
        val colorFilterMode = ReaderSettingRow.Choice(
            localized(DMR.strings.blend_mode),
            p.colorFilterMode(),
            listOf(
                0 to localized(MR.strings.label_default),
                1 to localized(MR.strings.filter_mode_multiply),
                2 to localized(MR.strings.filter_mode_screen),
                3 to localized(MR.strings.overlay_header),
                4 to localized(DMR.strings.lighten),
                5 to localized(DMR.strings.darken),
            ),
            shownWhen = p.colorFilter(),
        )
        val grayscale = ReaderSettingRow.Switch(localized(MR.strings.pref_grayscale), p.grayscale())
        val invertedColors = ReaderSettingRow.Switch(localized(DMR.strings.inverted_colors), p.invertedColors())
    }

    private val invertChoices = ReaderNavigation.inverts.map { it.first.name to it.second }

    /** Declarative search entries for all reader settings beyond the top five in [SettingsTab]. */
    internal fun searchEntries(): List<SettingsSearchEntry> {
        val rows = settings(DesktopPreferences(DefaultPreferenceStore))
        var group: String? = null
        return rows.mapNotNull { row ->
            when (row) {
                is ReaderSettingRow.Group -> {
                    group = row.title
                    null
                }
                is ReaderSettingRow.Switch -> SettingsSearchEntry(
                    category = SettingsCategory.Reader,
                    group = group,
                    title = row.title,
                    summary = row.subtitle,
                    itemKey = "reader-${row.preference.key()}",
                )
                is ReaderSettingRow.Choice<*> -> SettingsSearchEntry(
                    category = SettingsCategory.Reader,
                    group = group,
                    title = row.title,
                    keywords = row.choices.map { it.second },
                    itemKey = "reader-${row.preference.key()}",
                )
                is ReaderSettingRow.Slider -> SettingsSearchEntry(
                    category = SettingsCategory.Reader,
                    group = group,
                    title = row.title,
                    itemKey = "reader-${row.preference.key()}",
                )
                is ReaderSettingRow.Custom -> SettingsSearchEntry(
                    category = SettingsCategory.Reader,
                    group = group,
                    title = localized(row.title),
                    itemKey = "reader-${row.preference.key()}",
                )
            }
        }
    }

    /**
     * Read-only default [PreferenceStore] used only to enumerate [ReaderSettingRows.settings] keys
     * and labels for the search catalog without touching real preferences or `InMemoryPreferenceStore`'s
     * unimplemented `getStringSet`.
     */
    private object DefaultPreferenceStore : PreferenceStore {
        private class DefaultPreference<T>(
            private val key: String,
            private val default: T,
        ) : Preference<T> {
            private val state by lazy { MutableStateFlow(default) }
            override fun key(): String = key
            override fun get(): T = default
            override fun set(value: T) = Unit
            override fun isSet(): Boolean = false
            override fun delete() = Unit
            override fun defaultValue(): T = default
            override fun changes(): Flow<T> = state
            override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
        }

        private fun <T> pref(key: String, defaultValue: T): Preference<T> =
            DefaultPreference(key, defaultValue)

        override fun getString(key: String, defaultValue: String): Preference<String> = pref(key, defaultValue)
        override fun getLong(key: String, defaultValue: Long): Preference<Long> = pref(key, defaultValue)
        override fun getInt(key: String, defaultValue: Int): Preference<Int> = pref(key, defaultValue)
        override fun getFloat(key: String, defaultValue: Float): Preference<Float> = pref(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = pref(key, defaultValue)
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = pref(key, defaultValue)
        override fun <T> getObjectFromString(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> = pref(key, defaultValue)

        override fun <T> getObjectFromInt(
            key: String,
            defaultValue: T,
            serializer: (T) -> Int,
            deserializer: (Int) -> T,
        ): Preference<T> = pref(key, defaultValue)

        override fun getAll(): Map<String, *> = emptyMap<String, Any?>()
    }
}

/** A heading in the reader's settings sheet. */
@Composable
fun ReaderSettingHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.Medium, bottom = Spacing.ExtraSmall),
    )
}

/**
 * Draws [row] with the same widgets Settings uses everywhere else. With [inlineChoices] a choice
 * lists its options under its title, as the reader's sheet does, instead of opening a dialog.
 */
@Composable
fun ReaderSettingRowView(row: ReaderSettingRow, inlineChoices: Boolean = false) {
    if (row is ReaderSettingRow.Keyed) {
        val shownWhen = row.shownWhen
        if (shownWhen != null) {
            val shown by shownWhen.collectAsState()
            if (!shown) return
        }
    }
    when (row) {
        is ReaderSettingRow.Group -> ReaderSettingHeading(row.title)
        is ReaderSettingRow.Switch -> SwitchPreference(row.title, row.preference, row.subtitle)
        is ReaderSettingRow.Choice<*> -> ChoiceRow(row, inlineChoices)
        is ReaderSettingRow.Slider -> SliderPreference(row.title, row.preference, row.range, row.label)
        is ReaderSettingRow.Custom -> row.content()
    }
}

@Composable
private fun <T> ChoiceRow(row: ReaderSettingRow.Choice<T>, inline: Boolean) {
    if (inline) {
        ReaderSettingHeading(row.title)
        val value by row.preference.collectAsState()
        RadioChoices(value, row.choices) { row.preference.set(it) }
    } else {
        ChoicePreference(row.title, row.preference, row.choices)
    }
}

/** Options as a list of radio buttons, for the reader's sheet and its reading mode picker. */
@Composable
fun <T> RadioChoices(selected: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    choices.forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().radioRow(selected == value) { onSelect(value) }, verticalAlignment = Alignment.CenterVertically) {
            RowRadioButton(selected == value, onClick = null)
            Text(label)
        }
    }
}

/** Android's flash duration slider: 100 to 1500 ms in steps of 100, kept in milliseconds */
@Composable
internal fun FlashDurationPreference(preferences: DesktopPreferences) {
    val pref = preferences.flashDurationMillis()
    val saved by pref.collectAsState()
    var steps by remember(saved) { mutableFloatStateOf((saved / 100).coerceIn(1, 15).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.ExtraLarge, vertical = Spacing.Small)) {
        Row {
            Text(localized(MR.strings.pref_flash_duration), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(localized(MR.strings.pref_flash_duration_summary, steps.roundToInt() * 100), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = steps,
            onValueChange = { steps = it },
            onValueChangeFinished = { pref.set(steps.roundToInt() * 100) },
            valueRange = 1f..15f,
            steps = 13,
            modifier = Modifier.semantics { contentDescription = localized(MR.strings.pref_flash_duration) },
        )
    }
}

@Composable
private fun BottomButtonsRow(prefs: DesktopPreferences) {
    Text(
        localized(DMR.strings.bottom_bar_buttons),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = Spacing.Small, start = Spacing.ExtraLarge),
    )
    val buttons by prefs.readerBottomButtons().collectAsState()
    ReaderBottomButtons.all.forEach { (value, label) ->
        val on = value in buttons
        val toggle = { prefs.readerBottomButtons().set(if (on) buttons - value else buttons + value) }
        Row(Modifier.fillMaxWidth().checkRow(on) { toggle() }.padding(horizontal = Spacing.Large), verticalAlignment = Alignment.CenterVertically) {
            RowCheckbox(on, onCheckedChange = null)
            Text(label)
        }
    }
}

@Composable
private fun AutoScrollIntervalRow(prefs: DesktopPreferences) {
    val interval by prefs.autoscrollInterval().collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.ExtraLarge)) {
        Text(localized(DMR.strings.every_seconds_a_starts_and_stops, "%.1f".format(interval)), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = interval,
            onValueChange = { prefs.autoscrollInterval().set((it * 2).roundToInt() / 2f) },
            valueRange = 0.5f..30f,
            modifier = Modifier.semantics { contentDescription = localized(DMR.strings.every_seconds_a_starts_and_stops, "%.1f".format(interval)) },
        )
    }
}

@Composable
private fun BrightnessRow(prefs: DesktopPreferences) {
    val value by prefs.customBrightnessValue().collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.ExtraLarge)) {
        Text("${localized(DMR.strings.brightness)}: $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { prefs.customBrightnessValue().set(it.roundToInt()) },
            valueRange = -75f..100f,
            modifier = Modifier.semantics { contentDescription = localized(DMR.strings.brightness) },
        )
    }
}

@Composable
private fun ColorFilterRow(prefs: DesktopPreferences) {
    val argb by prefs.colorFilterValue().collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.ExtraLarge)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Color(argb), Corners.Medium))
            Text("  " + localized(DMR.strings.color_laid_over_pages), style = MaterialTheme.typography.bodySmall)
        }
        listOf(
            localized(MR.strings.color_filter_r_value) to 16,
            localized(MR.strings.color_filter_g_value) to 8,
            localized(MR.strings.color_filter_b_value) to 0,
            localized(MR.strings.color_filter_a_value) to 24,
        ).forEach { (name, shift) ->
            val channel = (argb ushr shift) and 0xFF
            Text("$name: $channel", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = channel.toFloat(),
                onValueChange = { v ->
                    val c = v.roundToInt().coerceIn(0, 255)
                    prefs.colorFilterValue().set((argb and (0xFF shl shift).inv()) or (c shl shift))
                },
                valueRange = 0f..255f,
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}
