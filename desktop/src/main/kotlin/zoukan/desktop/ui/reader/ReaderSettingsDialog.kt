package zoukan.desktop.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.FitModes
import zoukan.desktop.app.ReaderBottomButtons
import zoukan.desktop.app.ReadingModes
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.settings.RadioChoices
import zoukan.desktop.ui.settings.ReaderSettingHeading
import zoukan.desktop.ui.settings.ReaderSettingRow
import zoukan.desktop.ui.settings.ReaderSettingRowView
import zoukan.desktop.ui.settings.ReaderSettingRows
import zoukan.desktop.ui.settings.SwitchSetting
import zoukan.i18n.desktop.DMR

/**
 * The reader's settings sheet, as Android's: Reading mode, General and Filter. Its rows are
 * [ReaderSettingRows.sheet], drawn with the same widgets as Settings > Reader.
 */
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
                Column(Modifier.heightIn(max = 520.dp).verticalScrollWithBar().padding(top = Spacing.Small)) {
                    when (tab) {
                        0 -> ReadingPage(model, state.readingMode, prefs, state.shiftDoublePages)
                        1 -> ReaderSettingRows.sheetGeneral(prefs).forEach { ReaderSettingRowView(it, inlineChoices = true) }
                        else -> ReaderSettingRows.sheetFilter(prefs).forEach { ReaderSettingRowView(it, inlineChoices = true) }
                    }
                }
            }
        },
    )
}

@Composable
private fun ReadingPage(model: ReaderScreenModel, mode: Int, prefs: DesktopPreferences, shifted: Boolean) {
    ReaderSettingHeading(localized(DMR.strings.reading_mode_for_this_entry))
    // The reading mode is the entry's choice, not a preference
    RadioChoices(mode, ReadingModes.all.map { it to ReadingModes.label(it) }) { model.setReadingMode(it) }
    val rows = ReaderSettingRows.sheetViewer(prefs, mode)
    // "Shift double pages" is the entry's toggle, not a preference: it sits after "Invert double pages"
    val invertKey = prefs.invertDoublePages().key()
    rows.forEach { row ->
        ReaderSettingRowView(row, inlineChoices = true)
        if (row is ReaderSettingRow.Keyed && row.preference.key() == invertKey) {
            SwitchSetting(
                localized(DMR.strings.shift_double_pages),
                localized(DMR.strings.pairs_from_the_first_page_for),
                shifted,
            ) { model.toggleShiftDoublePages() }
        }
    }
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
