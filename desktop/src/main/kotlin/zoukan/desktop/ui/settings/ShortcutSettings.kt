package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.Shortcuts
import zoukan.i18n.desktop.DMR

/** Settings > Keyboard shortcuts: the app-wide shortcuts (the reader lists its own with `/`). */
fun LazyListScope.shortcutSettings() {
    settingsGroup(localized(DMR.strings.keyboard_shortcuts_2))
    Shortcuts.list.forEach { (keys, action) ->
        item(key = "shortcut-$keys") { SettingRow(keys, action) }
    }
    item(key = "shortcut-reader") { SettingRow(localized(DMR.strings.in_the_reader), localized(DMR.strings.press_for_the_readers_shortcuts)) }
}
