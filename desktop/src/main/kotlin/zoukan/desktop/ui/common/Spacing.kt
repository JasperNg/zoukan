package zoukan.desktop.ui.common

import androidx.compose.ui.unit.dp

/** Spacing shared by screens, so a change is made once. */
object Spacing {
    /** Left and right inset of a settings row, header and slider. */
    val SettingsRowInset = 24.dp

    /** Space above a settings group header. */
    val SettingsGroupGap = 24.dp

    /** Widest a settings page grows; wider windows leave the rest empty. */
    val SettingsPageMaxWidth = 900.dp
}
