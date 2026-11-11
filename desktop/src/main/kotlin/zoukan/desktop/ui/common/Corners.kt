package zoukan.desktop.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The app's corner sizes, so the same kind of surface has the same corner on every screen:
 * [Small] for badges, chips and small marks, [Medium] for covers, rows and cards, [Large] for
 * panels and large covers, [ExtraLarge] for pills and search boxes.
 */
object Corners {
    val Small = RoundedCornerShape(4.dp)
    val Medium = RoundedCornerShape(8.dp)
    val Large = RoundedCornerShape(12.dp)
    val ExtraLarge = RoundedCornerShape(24.dp)

    /** How far a badge sits from the edge of the cover it is drawn on. */
    val CoverInset = 6.dp

    /** A badge on a cover: the cover's corner less the inset, so the two corners stay parallel. */
    val OnCover = RoundedCornerShape(8.dp - CoverInset)
}
