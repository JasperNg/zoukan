package zoukan.desktop.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's gaps and paddings, so the same kind of space is the same size on every screen:
 * [ExtraSmall] between a label and its detail, [Small] between neighbouring controls, [Medium]
 * between groups inside a panel, [Large] for a panel's inner margin, [ExtraLarge] for the margin
 * of a whole bar or page.
 */
object Spacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
}

/** The colors of what the reader draws over a page: the page number, the reading mode toast. */
object OverlayColors {
    val Scrim = Color.Black.copy(alpha = 0.5f)
    val ToastScrim = Color.Black.copy(alpha = 0.6f)
    val OnScrim = Color.White
}
