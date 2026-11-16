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

    /** Below this width two equal panes are too narrow (Material's expanded width class starts at 840 dp). */
    val TwoPaneBreakpoint = 840.dp

    /** Width of the settings list below [TwoPaneBreakpoint], so the page keeps most of the room. */
    val SettingsListNarrowWidth = 280.dp

    /** Widest the settings list grows in wide windows. */
    val SettingsListMaxWidth = 450.dp
}
