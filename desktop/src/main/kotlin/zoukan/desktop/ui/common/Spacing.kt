package zoukan.desktop.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's gaps and paddings, so the same kind of space is the same size on every screen:
 * [ExtraSmall] between a label and its detail, [Small] between neighbouring controls, [Medium]
 * between groups inside a panel, [Large] for a panel's inner margin, [ExtraLarge] for the margin
 * of a whole bar or page. The settings screens' own sizes follow, so a change is made once.
 */
object Spacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp

    /** Left and right inset of a settings row, header and slider. */
    val SettingsRowInset = ExtraLarge

    /** Space above a settings group header. */
    val SettingsGroupGap = ExtraLarge

    /** Widest a settings page grows; wider windows leave the rest empty. */
    val SettingsPageMaxWidth = 900.dp

    /** Below this width two equal panes are too narrow (Material's expanded width class starts at 840 dp). */
    val TwoPaneBreakpoint = 840.dp

    /** Width of the settings list below [TwoPaneBreakpoint], so the page keeps most of the room. */
    val SettingsListNarrowWidth = 280.dp

    /** Widest the settings list grows in wide windows. */
    val SettingsListMaxWidth = 450.dp
}

/** The colors of what the reader draws over a page: the page number, the reading mode toast. */
object OverlayColors {
    val Scrim = Color.Black.copy(alpha = 0.5f)
    val ToastScrim = Color.Black.copy(alpha = 0.6f)
    val OnScrim = Color.White
}
