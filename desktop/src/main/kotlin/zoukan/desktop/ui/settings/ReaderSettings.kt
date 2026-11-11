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
import kotlin.math.roundToInt
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.reader.ReaderSettingChoices

/**
 * Settings > Reader beyond the basics: Android's SettingsReaderScreen (display, reading, paged,
 * webtoon) and SY's page loading options. The keys are Android's, so they travel in backups.
 */
fun LazyListScope.readerBehaviourSettings(preferences: DesktopPreferences) {
    settingsGroup("Display")
    item { SwitchPreference("Show reading mode", preferences.showReadingMode(), "Briefly shows the current mode when the reader opens") }
    item { SwitchPreference("Always show chapter transition", preferences.alwaysShowChapterTransition()) }
    item { SwitchPreference("Keep screen on", preferences.keepScreenOn()) }
    item { SwitchPreference("Flash on page change", preferences.flashOnPageChange(), "Reduces ghosting on e-ink monitors") }
    item {
        val flash by preferences.flashOnPageChange().changes().collectAsState(preferences.flashOnPageChange().get())
        if (flash) {
            ChoicePreference("Flash with", preferences.flashColor(), ReaderSettingChoices.flashColors)
            FlashDurationPreference(preferences)
            SliderPreference("Flash every", preferences.flashPageInterval(), 1..10) { if (it == 1) "page" else "$it pages" }
        }
    }

    settingsGroup("Reading")
    item { SwitchPreference("Show actions on long tap", preferences.readWithLongTap(), "Right-click always shows them") }
    item { SwitchPreference("Smaller tap zones", preferences.smallerTapZone()) }
    item { SwitchPreference("Smooth auto scroll", preferences.smoothAutoScroll(), "Webtoon modes glide instead of jumping") }
    item { SwitchPreference("Auto webtoon mode", preferences.useAutoWebtoon(), "Read entries tagged webtoon, manhwa or manhua as webtoons") }
    item { SwitchPreference("Preserve reading position", preferences.preserveReadingPosition(), "Open a read chapter at its last page read") }

    settingsGroup("Paged")
    item { ChoicePreference("Tap zones", preferences.navigationModePager(), ReaderNavigation.layouts) }
    item { ChoicePreference("Invert tap zones", preferences.pagerNavInverted(), invertChoices) }
    item { ChoicePreference("Zoom start position", preferences.zoomStart(), ReaderSettingChoices.zoomStarts) }
    item { ChoicePreference("Center margin", preferences.centerMarginType(), ReaderSettingChoices.centerMargins) }
    item { SwitchPreference("Split wide pages", preferences.dualPageSplitPaged()) }
    item { SwitchPreference("Invert split page placement", preferences.dualPageInvertPaged()) }
    item { SwitchPreference("Rotate wide pages to fit", preferences.dualPageRotateToFit()) }
    item { SwitchPreference("Flip orientation of rotated wide pages", preferences.dualPageRotateToFitInvert()) }
    item { SwitchPreference("Pan wide images when tapping", preferences.navigateToPan()) }
    item { SwitchPreference("Disable zoom in", preferences.pagedDisableZoomIn()) }
    item { SwitchPreference("Double tap to zoom", preferences.pagedDoubleTapZoomEnabled()) }
    item { SwitchPreference("Animate page transitions", preferences.pageTransitionsPager()) }

    settingsGroup("Webtoon")
    item { ChoicePreference("Tap zones", preferences.navigationModeWebtoon(), ReaderNavigation.layouts) }
    item { ChoicePreference("Invert tap zones", preferences.webtoonNavInverted(), invertChoices) }
    item { SwitchPreference("Split wide pages", preferences.dualPageSplitWebtoon()) }
    item { SwitchPreference("Invert split page placement", preferences.dualPageInvertWebtoon()) }
    item { SwitchPreference("Rotate wide pages to fit", preferences.dualPageRotateToFitWebtoon()) }
    item { SwitchPreference("Flip orientation of rotated wide pages", preferences.dualPageRotateToFitInvertWebtoon()) }
    item { SwitchPreference("Double tap to zoom", preferences.webtoonDoubleTapZoomEnabled()) }
    item { SwitchPreference("Disable zoom out", preferences.webtoonDisableZoomOut()) }
    item { SwitchPreference("Animate page transitions", preferences.pageTransitionsWebtoon()) }

    settingsGroup("Page loading")
    item { SliderPreference("Pages to preload", preferences.preloadSize(), 1..20) { "$it" } }
    item { SliderPreference("Reader threads", preferences.readerThreads(), 1..5) { "$it" } }
    item { SwitchPreference("Aggressive page loading", preferences.aggressivePageLoading(), "Download every page of an online chapter when it opens") }
    item { SwitchPreference("Instant retry", preferences.readerInstantRetry(), "Retry a failed page once at once") }
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
            Text("Flash duration", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${steps.roundToInt() * 100} ms", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
