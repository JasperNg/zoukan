package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.reader.ReaderSettingChoices
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

/**
 * Settings > Reader beyond the basics: Android's SettingsReaderScreen (display, reading, paged,
 * webtoon) and SY's page loading options. The keys are Android's, so they travel in backups.
 */
fun LazyListScope.readerBehaviourSettings(preferences: DesktopPreferences) {
    settingsGroup(localized(MR.strings.action_display))
    item { SwitchPreference(localized(MR.strings.pref_show_reading_mode), preferences.showReadingMode(), localized(DMR.strings.briefly_shows_the_current_mode_when)) }
    item { SwitchPreference(localized(MR.strings.pref_always_show_chapter_transition), preferences.alwaysShowChapterTransition()) }
    item { SwitchPreference(localized(MR.strings.pref_keep_screen_on), preferences.keepScreenOn()) }
    item { SwitchPreference(localized(MR.strings.pref_flash_page), preferences.flashOnPageChange(), localized(DMR.strings.reduces_ghosting_on_e_ink_monitors)) }
    item {
        val flash by preferences.flashOnPageChange().changes().collectAsState(preferences.flashOnPageChange().get())
        if (flash) {
            ChoicePreference(localized(MR.strings.pref_flash_with), preferences.flashColor(), ReaderSettingChoices.flashColors)
            FlashDurationPreference(preferences)
            SliderPreference(localized(MR.strings.pref_flash_page_interval), preferences.flashPageInterval(), 1..10) { if (it == 1) localized(DMR.strings.page_1) else localized(DMR.strings.pages, it) }
        }
    }

    settingsGroup(localized(MR.strings.reading))
    item { SwitchPreference(localized(MR.strings.pref_read_with_long_tap), preferences.readWithLongTap(), localized(DMR.strings.right_click_always_shows_them)) }
    item { SwitchPreference(localized(KMR.strings.pref_viewer_nav_smaller_tap_zone), preferences.smallerTapZone()) }
    item { SwitchPreference(localized(DMR.strings.smooth_auto_scroll), preferences.smoothAutoScroll(), localized(DMR.strings.webtoon_modes_glide_instead_of_jumping)) }
    item { SwitchPreference(localized(DMR.strings.auto_webtoon_mode), preferences.useAutoWebtoon(), localized(DMR.strings.read_entries_tagged_webtoon_manhwa_or)) }
    item { SwitchPreference(localized(DMR.strings.preserve_reading_position), preferences.preserveReadingPosition(), localized(DMR.strings.open_a_read_chapter_at_its)) }

    settingsGroup(localized(MR.strings.pager_viewer))
    item { ChoicePreference(localized(MR.strings.pref_viewer_nav), preferences.navigationModePager(), ReaderNavigation.layouts) }
    item { ChoicePreference(localized(MR.strings.pref_read_with_tapping_inverted), preferences.pagerNavInverted(), invertChoices) }
    item { ChoicePreference(localized(MR.strings.pref_zoom_start), preferences.zoomStart(), ReaderSettingChoices.zoomStarts) }
    item { ChoicePreference(localized(DMR.strings.center_margin), preferences.centerMarginType(), ReaderSettingChoices.centerMargins) }
    item { SwitchPreference(localized(MR.strings.pref_dual_page_split), preferences.dualPageSplitPaged()) }
    item { SwitchPreference(localized(MR.strings.pref_dual_page_invert), preferences.dualPageInvertPaged()) }
    item { SwitchPreference(localized(MR.strings.pref_page_rotate), preferences.dualPageRotateToFit()) }
    item { SwitchPreference(localized(MR.strings.pref_page_rotate_invert), preferences.dualPageRotateToFitInvert()) }
    item { SwitchPreference(localized(DMR.strings.pan_wide_images_when_tapping), preferences.navigateToPan()) }
    item { SwitchPreference(localized(KMR.strings.pref_paged_disable_zoom_in), preferences.pagedDisableZoomIn()) }
    item { SwitchPreference(localized(MR.strings.pref_double_tap_zoom), preferences.pagedDoubleTapZoomEnabled()) }
    item { SwitchPreference(localized(MR.strings.pref_page_transitions), preferences.pageTransitionsPager()) }

    settingsGroup(localized(SYMR.strings.entry_type_webtoon))
    item { ChoicePreference(localized(MR.strings.pref_viewer_nav), preferences.navigationModeWebtoon(), ReaderNavigation.layouts) }
    item { ChoicePreference(localized(MR.strings.pref_read_with_tapping_inverted), preferences.webtoonNavInverted(), invertChoices) }
    item { SwitchPreference(localized(MR.strings.pref_dual_page_split), preferences.dualPageSplitWebtoon()) }
    item { SwitchPreference(localized(MR.strings.pref_dual_page_invert), preferences.dualPageInvertWebtoon()) }
    item { SwitchPreference(localized(MR.strings.pref_page_rotate), preferences.dualPageRotateToFitWebtoon()) }
    item { SwitchPreference(localized(MR.strings.pref_page_rotate_invert), preferences.dualPageRotateToFitInvertWebtoon()) }
    item { SwitchPreference(localized(MR.strings.pref_double_tap_zoom), preferences.webtoonDoubleTapZoomEnabled()) }
    item { SwitchPreference(localized(MR.strings.pref_webtoon_disable_zoom_out), preferences.webtoonDisableZoomOut()) }
    item { SwitchPreference(localized(MR.strings.pref_page_transitions), preferences.pageTransitionsWebtoon()) }

    settingsGroup(localized(DMR.strings.page_loading))
    item { SliderPreference(localized(DMR.strings.pages_to_preload), preferences.preloadSize(), 1..20) { "$it" } }
    item { SliderPreference(localized(DMR.strings.reader_threads), preferences.readerThreads(), 1..5) { "$it" } }
    item { SwitchPreference(localized(DMR.strings.aggressive_page_loading), preferences.aggressivePageLoading(), localized(DMR.strings.download_every_page_of_an_online)) }
    item { SwitchPreference(localized(DMR.strings.instant_retry), preferences.readerInstantRetry(), localized(DMR.strings.retry_a_failed_page_once_at)) }
}

private val invertChoices = ReaderNavigation.inverts.map { it.first.name to it.second }

/** Android's flash duration slider: 100 to 1500 ms in steps of 100, kept in milliseconds */
@Composable
private fun FlashDurationPreference(preferences: DesktopPreferences) {
    val pref = preferences.flashDurationMillis()
    val saved by pref.changes().collectAsState(pref.get())
    var steps by remember(saved) { mutableFloatStateOf((saved / 100).coerceIn(1, 15).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
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
        )
    }
}
