package zoukan.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReaderBottomButtons
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.WebtoonScaleTypes
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

/** The reader's settings sheet, as Android's: Reading mode, General and Filter. */
@Composable
fun ReaderSettingsDialog(model: ReaderScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    val prefs = model.preferences
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_close)) } },
        text = {
            Column(Modifier.width(460.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(localized(MR.strings.viewer), localized(MR.strings.pref_category_general), localized(MR.strings.action_filter)).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
                Column(Modifier.heightIn(max = 520.dp).verticalScrollWithBar().padding(top = 8.dp)) {
                    when (tab) {
                        0 -> ReadingPage(model, state.readingMode, prefs, state.shiftDoublePages)
                        1 -> GeneralPage(prefs)
                        else -> FilterPage(prefs)
                    }
                }
            }
        },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun <T> Choices(selected: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    choices.forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(value) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == value, onClick = { onSelect(value) })
            Text(label)
        }
    }
}

@Composable
private fun <T> PrefChoices(preference: Preference<T>, choices: List<Pair<T, String>>) {
    val value by preference.changes().collectAsState(preference.get())
    Choices(value, choices) { preference.set(it) }
}

@Composable
private fun SwitchRow(label: String, preference: Preference<Boolean>, subtitle: String? = null) {
    val value by preference.changes().collectAsState(preference.get())
    Row(
        Modifier.fillMaxWidth().clickable { preference.set(!value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onCheckedChange = { preference.set(it) })
    }
}

@Composable
private fun IntSlider(label: String, preference: Preference<Int>, range: IntRange, format: (Int) -> String = { "$it" }) {
    val value by preference.changes().collectAsState(preference.get())
    Text("$label: ${format(value)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Slider(
        value = value.toFloat(),
        onValueChange = { preference.set(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun ReadingPage(model: ReaderScreenModel, mode: Int, prefs: DesktopPreferences, shifted: Boolean) {
    Heading(localized(DMR.strings.reading_mode_for_this_entry))
    Choices(mode, ReadingModes.all.map { it to ReadingModes.label(it) }) { model.setReadingMode(it) }
    val webtoon = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL
    if (webtoon) {
        Heading(localized(SYMR.strings.entry_type_webtoon))
        IntSlider(localized(MR.strings.pref_webtoon_side_padding), prefs.webtoonSidePadding(), 0..25) { "$it%" }
        SwitchRow(localized(MR.strings.pref_crop_borders), prefs.cropBordersWebtoon())
        // ZOUKAN -->
        // KMK's smart scale; Long strip with gaps only when "Apply Smart scale to Long strip with gaps" is on
        val gapScale by prefs.longStripGapSmartScale().changes().collectAsState(prefs.longStripGapSmartScale().get())
        if (mode != ReadingModes.CONTINUOUS_VERTICAL || gapScale) {
            Heading(localized(KMR.strings.pref_webtoon_scale_type))
            PrefChoices(prefs.webtoonScaleType(), WebtoonScaleTypes.all)
        }
        Heading(localized(MR.strings.pref_viewer_nav))
        PrefChoices(prefs.navigationModeWebtoon(), ReaderNavigation.layouts)
        Heading(localized(MR.strings.pref_read_with_tapping_inverted))
        PrefChoices(prefs.webtoonNavInverted(), ReaderNavigation.inverts.map { it.first.name to it.second })
        SwitchRow(localized(MR.strings.pref_dual_page_split), prefs.dualPageSplitWebtoon())
        SwitchRow(localized(MR.strings.pref_dual_page_invert), prefs.dualPageInvertWebtoon())
        SwitchRow(localized(MR.strings.pref_page_rotate), prefs.dualPageRotateToFitWebtoon())
        SwitchRow(localized(MR.strings.pref_page_rotate_invert), prefs.dualPageRotateToFitInvertWebtoon())
        SwitchRow(localized(MR.strings.pref_double_tap_zoom), prefs.webtoonDoubleTapZoomEnabled())
        SwitchRow(localized(MR.strings.pref_webtoon_disable_zoom_out), prefs.webtoonDisableZoomOut())
        SwitchRow(localized(MR.strings.pref_page_transitions), prefs.pageTransitionsWebtoon())
        // ZOUKAN <--
    } else {
        Heading(localized(MR.strings.pref_image_scale_type))
        PrefChoices(prefs.imageScaleType(), FitModes.all.map { it to FitModes.label(it) })
        Heading(localized(SYMR.strings.page_layout))
        PrefChoices(prefs.pageLayout(), listOf(0 to localized(SYMR.strings.single_page), 1 to localized(SYMR.strings.double_pages), 2 to localized(DMR.strings.automatic_double_in_a_wide_window)))
        SwitchRow(localized(SYMR.strings.invert_double_pages), prefs.invertDoublePages())
        // ZOUKAN -->
        Row(Modifier.fillMaxWidth().clickable { model.toggleShiftDoublePages() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(localized(DMR.strings.shift_double_pages), style = MaterialTheme.typography.bodyMedium)
                Text(localized(DMR.strings.pairs_from_the_first_page_for), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(shifted, onCheckedChange = { model.toggleShiftDoublePages() })
        }
        Heading(localized(DMR.strings.center_margin))
        PrefChoices(prefs.centerMarginType(), ReaderSettingChoices.centerMargins)
        // ZOUKAN <--
        SwitchRow(localized(MR.strings.pref_crop_borders), prefs.cropBorders())
        // ZOUKAN -->
        Heading(localized(MR.strings.pref_viewer_nav))
        PrefChoices(prefs.navigationModePager(), ReaderNavigation.layouts)
        Heading(localized(MR.strings.pref_read_with_tapping_inverted))
        PrefChoices(prefs.pagerNavInverted(), ReaderNavigation.inverts.map { it.first.name to it.second })
        Heading(localized(MR.strings.pref_zoom_start))
        PrefChoices(prefs.zoomStart(), ReaderSettingChoices.zoomStarts)
        SwitchRow(localized(MR.strings.pref_dual_page_split), prefs.dualPageSplitPaged())
        SwitchRow(localized(MR.strings.pref_dual_page_invert), prefs.dualPageInvertPaged())
        SwitchRow(localized(MR.strings.pref_page_rotate), prefs.dualPageRotateToFit())
        SwitchRow(localized(MR.strings.pref_page_rotate_invert), prefs.dualPageRotateToFitInvert())
        SwitchRow(localized(DMR.strings.pan_wide_images_when_tapping), prefs.navigateToPan())
        SwitchRow(localized(KMR.strings.pref_paged_disable_zoom_in), prefs.pagedDisableZoomIn())
        SwitchRow(localized(MR.strings.pref_double_tap_zoom), prefs.pagedDoubleTapZoomEnabled())
        SwitchRow(localized(MR.strings.pref_page_transitions), prefs.pageTransitionsPager())
        // ZOUKAN <--
    }
}

@Composable
private fun GeneralPage(prefs: DesktopPreferences) {
    Heading(localized(MR.strings.pref_reader_theme))
    PrefChoices(prefs.readerTheme(), ReaderSettingChoices.themes)
    SwitchRow(localized(MR.strings.pref_show_page_number), prefs.showPageNumber())
    SwitchRow(localized(MR.strings.pref_always_show_chapter_transition), prefs.alwaysShowChapterTransition())
    Heading(localized(MR.strings.chapters))
    SwitchRow(localized(MR.strings.pref_skip_read_chapters), prefs.skipRead())
    SwitchRow(localized(MR.strings.pref_skip_filtered_chapters), prefs.skipFiltered(), localized(DMR.strings.the_ones_the_entrys_chapter_filters))
    SwitchRow(localized(MR.strings.pref_skip_dupe_chapters), prefs.skipDupe(), localized(DMR.strings.keep_one_chapter_per_number_the))
    // ZOUKAN -->
    SwitchRow(localized(MR.strings.pref_create_folder_per_manga), prefs.folderPerManga(), localized(MR.strings.pref_create_folder_per_manga_summary))
    // ZOUKAN <--
    // ZOUKAN -->
    Heading(localized(MR.strings.action_menu))
    SwitchRow(localized(DMR.strings.force_horizontal_page_slider), prefs.forceHorizontalSeekbar(), localized(DMR.strings.vertical_modes_keep_the_slider_at))
    SwitchRow(localized(DMR.strings.vertical_page_slider_in_a_wide), prefs.landscapeVerticalSeekbar(), localized(DMR.strings.in_vertical_modes_also_when_the))
    SwitchRow(localized(DMR.strings.left_handed_vertical_page_slider), prefs.leftVerticalSeekbar())
    Text(localized(DMR.strings.bottom_bar_buttons), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    val buttons by prefs.readerBottomButtons().changes().collectAsState(prefs.readerBottomButtons().get())
    ReaderBottomButtons.all.forEach { (value, label) ->
        val on = value in buttons
        val toggle = { prefs.readerBottomButtons().set(if (on) buttons - value else buttons + value) }
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = { toggle() })
            Text(label)
        }
    }
    SwitchRow(localized(MR.strings.pref_show_reading_mode), prefs.showReadingMode(), localized(DMR.strings.briefly_shows_the_current_mode_when))
    SwitchRow(localized(MR.strings.pref_keep_screen_on), prefs.keepScreenOn())
    SwitchRow(localized(MR.strings.pref_read_with_long_tap), prefs.readWithLongTap())
    SwitchRow(localized(KMR.strings.pref_viewer_nav_smaller_tap_zone), prefs.smallerTapZone())
    // ZOUKAN <--
    Heading(localized(DMR.strings.auto_scroll))
    val interval by prefs.autoscrollInterval().changes().collectAsState(prefs.autoscrollInterval().get())
    Text(localized(DMR.strings.every_seconds_a_starts_and_stops, "%.1f".format(interval)), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = interval,
        onValueChange = { prefs.autoscrollInterval().set((it * 2).roundToInt() / 2f) },
        valueRange = 0.5f..30f,
    )
    // ZOUKAN -->
    SwitchRow(localized(DMR.strings.smooth_auto_scroll), prefs.smoothAutoScroll(), localized(DMR.strings.webtoon_modes_glide_instead_of_jumping))
    // ZOUKAN <--
}

// ZOUKAN -->
/** Choice labels shared by the reader's settings sheet and Settings > Reader */
object ReaderSettingChoices {
    /** Android's reader themes, in its order */
    val themes = listOf(1 to localized(MR.strings.black_background), 2 to localized(MR.strings.gray_background), 0 to localized(MR.strings.white_background), 3 to localized(MR.strings.automatic_background))

    val zoomStarts = listOf(1 to localized(MR.strings.zoom_start_automatic), 2 to localized(MR.strings.nav_zone_left), 3 to localized(MR.strings.nav_zone_right), 4 to localized(MR.strings.zoom_start_center))
    val centerMargins = listOf(0 to localized(MR.strings.none), 1 to localized(SYMR.strings.double_pages), 2 to localized(DMR.strings.wide_pages), 3 to localized(DMR.strings.double_and_wide_pages))
    val flashColors = listOf("BLACK" to localized(MR.strings.black_background), "WHITE" to localized(MR.strings.white_background), "WHITE_BLACK" to localized(DMR.strings.white_and_black))
}
// ZOUKAN <--

@Composable
private fun FilterPage(prefs: DesktopPreferences) {
    SwitchRow(localized(MR.strings.pref_custom_brightness), prefs.customBrightness())
    val brightnessOn by prefs.customBrightness().changes().collectAsState(prefs.customBrightness().get())
    if (brightnessOn) IntSlider(localized(DMR.strings.brightness), prefs.customBrightnessValue(), -75..100)
    SwitchRow(localized(DMR.strings.color_filter), prefs.colorFilter())
    val colorOn by prefs.colorFilter().changes().collectAsState(prefs.colorFilter().get())
    if (colorOn) {
        val argb by prefs.colorFilterValue().changes().collectAsState(prefs.colorFilterValue().get())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Color(argb), Corners.Medium))
            Text("  " + localized(DMR.strings.color_laid_over_pages), style = MaterialTheme.typography.bodySmall)
        }
        listOf(localized(MR.strings.color_filter_r_value) to 16, localized(MR.strings.color_filter_g_value) to 8, localized(MR.strings.color_filter_b_value) to 0, localized(MR.strings.color_filter_a_value) to 24).forEach { (name, shift) ->
            val channel = (argb ushr shift) and 0xFF
            Text("$name: $channel", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = channel.toFloat(),
                onValueChange = { v ->
                    val c = v.roundToInt().coerceIn(0, 255)
                    prefs.colorFilterValue().set((argb and (0xFF shl shift).inv()) or (c shl shift))
                },
                valueRange = 0f..255f,
            )
        }
        Heading(localized(DMR.strings.blend_mode))
        PrefChoices(
            prefs.colorFilterMode(),
            listOf(0 to localized(MR.strings.label_default), 1 to localized(MR.strings.filter_mode_multiply), 2 to localized(MR.strings.filter_mode_screen), 3 to localized(MR.strings.overlay_header), 4 to localized(DMR.strings.lighten), 5 to localized(DMR.strings.darken)),
        )
    }
    SwitchRow(localized(MR.strings.pref_grayscale), prefs.grayscale())
    SwitchRow(localized(DMR.strings.inverted_colors), prefs.invertedColors())
}
