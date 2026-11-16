package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.app.WebtoonScaleTypes
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.reader.ReaderNavigation
import zoukan.desktop.ui.reader.ReaderSettingChoices
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

/**
 * Settings > Reader beyond the basics: Android's SettingsReaderScreen (display, reading, paged,
 * webtoon) and SY's page loading options. The keys are Android's, so they travel in backups.
 * The rows are [ReaderSettingRows.settings], the same ones the reader's settings sheet draws from.
 */
fun LazyListScope.readerBehaviourSettings(preferences: DesktopPreferences) {
    ReaderSettingRows.settings(preferences).forEach { row ->
        if (row is ReaderSettingRow.Group) {
            settingsGroup(row.title)
        } else {
            item { ReaderSettingRowView(row) }
        }
    }
}
