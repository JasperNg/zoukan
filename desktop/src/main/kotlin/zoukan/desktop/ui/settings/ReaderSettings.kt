package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import zoukan.desktop.ui.common.DesktopPreferences

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
