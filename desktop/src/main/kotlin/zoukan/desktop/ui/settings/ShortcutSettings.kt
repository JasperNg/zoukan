package zoukan.desktop.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import zoukan.desktop.ui.Shortcuts

/** Settings > Keyboard shortcuts: the app-wide shortcuts (the reader lists its own with `/`). */
fun LazyListScope.shortcutSettings() {
    settingsGroup("Keyboard shortcuts")
    Shortcuts.list.forEach { (keys, action) ->
        item(key = "shortcut-$keys") { SettingRow(keys, action) }
    }
    item(key = "shortcut-reader") { SettingRow("In the reader", "Press / for the reader's shortcuts") }
}
