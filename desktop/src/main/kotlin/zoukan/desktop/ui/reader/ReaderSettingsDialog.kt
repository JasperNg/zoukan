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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.FitModes
import zoukan.desktop.ui.common.ReaderBottomButtons
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.verticalScrollWithBar
import kotlin.math.roundToInt

/** The reader's settings sheet, as Android's: Reading mode, General and Filter. */
@Composable
fun ReaderSettingsDialog(model: ReaderScreenModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsState()
    val prefs = model.preferences
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(Modifier.width(460.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Reading mode", "General", "Filter").forEachIndexed { i, label ->
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
    Heading("Reading mode for this entry")
    Choices(mode, ReadingModes.all.map { it to ReadingModes.label(it) }) { model.setReadingMode(it) }
    val webtoon = mode == ReadingModes.WEBTOON || mode == ReadingModes.CONTINUOUS_VERTICAL
    if (webtoon) {
        Heading("Webtoon")
        IntSlider("Side padding", prefs.webtoonSidePadding(), 0..25) { "$it%" }
        SwitchRow("Crop borders", prefs.cropBordersWebtoon())
        // ZOUKAN -->
        Heading("Tap zones")
        PrefChoices(prefs.navigationModeWebtoon(), ReaderNavigation.layouts)
        Heading("Invert tap zones")
        PrefChoices(prefs.webtoonNavInverted(), ReaderNavigation.inverts.map { it.first.name to it.second })
        SwitchRow("Split wide pages", prefs.dualPageSplitWebtoon())
        SwitchRow("Invert split page placement", prefs.dualPageInvertWebtoon())
        SwitchRow("Rotate wide pages to fit", prefs.dualPageRotateToFitWebtoon())
        SwitchRow("Flip orientation of rotated wide pages", prefs.dualPageRotateToFitInvertWebtoon())
        SwitchRow("Double tap to zoom", prefs.webtoonDoubleTapZoomEnabled())
        SwitchRow("Disable zoom out", prefs.webtoonDisableZoomOut())
        SwitchRow("Animate page transitions", prefs.pageTransitionsWebtoon())
        // ZOUKAN <--
    } else {
        Heading("Scale type")
        PrefChoices(prefs.readerFitMode(), FitModes.all.map { it to FitModes.label(it) })
        Heading("Page layout")
        PrefChoices(prefs.pageLayout(), listOf(0 to "Single page", 1 to "Double pages", 2 to "Automatic (double in a wide window)"))
        SwitchRow("Invert double pages", prefs.invertDoublePages())
        // ZOUKAN -->
        Row(Modifier.fillMaxWidth().clickable { model.toggleShiftDoublePages() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shift double pages", style = MaterialTheme.typography.bodyMedium)
                Text("Pairs from the first page, for this chapter (D)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(shifted, onCheckedChange = { model.toggleShiftDoublePages() })
        }
        Heading("Center margin")
        PrefChoices(prefs.centerMarginType(), ReaderSettingChoices.centerMargins)
        // ZOUKAN <--
        SwitchRow("Crop borders", prefs.cropBorders())
        // ZOUKAN -->
        Heading("Tap zones")
        PrefChoices(prefs.navigationModePager(), ReaderNavigation.layouts)
        Heading("Invert tap zones")
        PrefChoices(prefs.pagerNavInverted(), ReaderNavigation.inverts.map { it.first.name to it.second })
        Heading("Zoom start position")
        PrefChoices(prefs.zoomStart(), ReaderSettingChoices.zoomStarts)
        SwitchRow("Split wide pages", prefs.dualPageSplitPaged())
        SwitchRow("Invert split page placement", prefs.dualPageInvertPaged())
        SwitchRow("Rotate wide pages to fit", prefs.dualPageRotateToFit())
        SwitchRow("Flip orientation of rotated wide pages", prefs.dualPageRotateToFitInvert())
        SwitchRow("Pan wide images when tapping", prefs.navigateToPan())
        SwitchRow("Disable zoom in", prefs.pagedDisableZoomIn())
        SwitchRow("Double tap to zoom", prefs.pagedDoubleTapZoomEnabled())
        SwitchRow("Animate page transitions", prefs.pageTransitionsPager())
        // ZOUKAN <--
    }
}

@Composable
private fun GeneralPage(prefs: DesktopPreferences) {
    Heading("Background color")
    PrefChoices(prefs.readerTheme(), listOf(0 to "White", 1 to "Black", 2 to "Gray"))
    SwitchRow("Show page number", prefs.showPageNumber())
    SwitchRow("Always show chapter transition", prefs.alwaysShowChapterTransition())
    Heading("Chapters")
    SwitchRow("Skip chapters marked read", prefs.skipRead())
    SwitchRow("Skip filtered chapters", prefs.skipFiltered(), "The ones the entry's chapter filters hide")
    SwitchRow("Skip duplicate chapters", prefs.skipDupe(), "Keep one chapter per number, the same scanlator's where it can")
    // ZOUKAN -->
    SwitchRow("Save pages into separate folders", prefs.folderPerManga(), "Creates folders according to entries' title")
    // ZOUKAN <--
    // ZOUKAN -->
    Heading("Menu")
    SwitchRow("Force horizontal page slider", prefs.forceHorizontalSeekbar(), "Vertical modes keep the slider at the bottom")
    SwitchRow("Vertical page slider in a wide window", prefs.landscapeVerticalSeekbar(), "In vertical modes, also when the window is wider than tall")
    SwitchRow("Left-handed vertical page slider", prefs.leftVerticalSeekbar())
    Text("Bottom bar buttons", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    val buttons by prefs.readerBottomButtons().changes().collectAsState(prefs.readerBottomButtons().get())
    ReaderBottomButtons.all.forEach { (value, label) ->
        val on = value in buttons
        val toggle = { prefs.readerBottomButtons().set(if (on) buttons - value else buttons + value) }
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, onCheckedChange = { toggle() })
            Text(label)
        }
    }
    SwitchRow("Show reading mode", prefs.showReadingMode(), "Briefly shows the current mode when the reader opens")
    SwitchRow("Keep screen on", prefs.keepScreenOn())
    SwitchRow("Show actions on long tap", prefs.readWithLongTap())
    SwitchRow("Smaller tap zones", prefs.smallerTapZone())
    // ZOUKAN <--
    Heading("Auto scroll")
    val interval by prefs.autoscrollInterval().changes().collectAsState(prefs.autoscrollInterval().get())
    Text("Every ${"%.1f".format(interval)} seconds (A starts and stops it)", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = interval,
        onValueChange = { prefs.autoscrollInterval().set((it * 2).roundToInt() / 2f) },
        valueRange = 0.5f..30f,
    )
    // ZOUKAN -->
    SwitchRow("Smooth auto scroll", prefs.smoothAutoScroll(), "Webtoon modes glide instead of jumping")
    // ZOUKAN <--
}

// ZOUKAN -->
/** Choice labels shared by the reader's settings sheet and Settings > Reader */
object ReaderSettingChoices {
    val zoomStarts = listOf(1 to "Automatic", 2 to "Left", 3 to "Right", 4 to "Center")
    val centerMargins = listOf(0 to "None", 1 to "Double pages", 2 to "Wide pages", 3 to "Double and wide pages")
    val flashColors = listOf("BLACK" to "Black", "WHITE" to "White", "WHITE_BLACK" to "White and black")
}
// ZOUKAN <--

@Composable
private fun FilterPage(prefs: DesktopPreferences) {
    SwitchRow("Custom brightness", prefs.customBrightness())
    val brightnessOn by prefs.customBrightness().changes().collectAsState(prefs.customBrightness().get())
    if (brightnessOn) IntSlider("Brightness", prefs.customBrightnessValue(), -75..100)
    SwitchRow("Color filter", prefs.colorFilter())
    val colorOn by prefs.colorFilter().changes().collectAsState(prefs.colorFilter().get())
    if (colorOn) {
        val argb by prefs.colorFilterValue().changes().collectAsState(prefs.colorFilterValue().get())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Color(argb), RoundedCornerShape(6.dp)))
            Text("  The color laid over pages", style = MaterialTheme.typography.bodySmall)
        }
        listOf("Red" to 16, "Green" to 8, "Blue" to 0, "Alpha" to 24).forEach { (name, shift) ->
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
        Heading("Blend mode")
        PrefChoices(
            prefs.colorFilterMode(),
            listOf(0 to "Default", 1 to "Multiply", 2 to "Screen", 3 to "Overlay", 4 to "Lighten", 5 to "Darken"),
        )
    }
    SwitchRow("Grayscale", prefs.grayscale())
    SwitchRow("Inverted colors", prefs.invertedColors())
}
