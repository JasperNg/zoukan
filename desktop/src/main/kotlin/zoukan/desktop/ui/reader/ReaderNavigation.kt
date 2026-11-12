package zoukan.desktop.ui.reader

import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * The reader's tap zones, as Android's ViewerNavigation and its layouts (L shaped, Kindle-ish,
 * Edge, Right and left, Disabled). Positions are fractions of the reader's size, 0 to 1.
 */
object ReaderNavigation {

    enum class Region { MENU, PREV, NEXT, LEFT, RIGHT }

    /** Android's TappingInvertMode */
    enum class Invert(val horizontal: Boolean, val vertical: Boolean) {
        NONE(false, false),
        HORIZONTAL(true, false),
        VERTICAL(false, true),
        BOTH(true, true),
        ;

        companion object {
            fun of(name: String) = entries.firstOrNull { it.name == name } ?: NONE
        }
    }

    const val DEFAULT = 0
    const val L_SHAPED = 1
    const val KINDLISH = 2
    const val EDGE = 3
    const val RIGHT_AND_LEFT = 4
    const val DISABLED = 5

    val layouts = listOf(
        DEFAULT to localized(MR.strings.label_default),
        L_SHAPED to localized(MR.strings.l_nav),
        KINDLISH to localized(MR.strings.kindlish_nav),
        EDGE to localized(MR.strings.edge_nav),
        RIGHT_AND_LEFT to localized(DMR.strings.right_and_left),
        DISABLED to localized(MR.strings.disabled),
    )

    val inverts = listOf(
        Invert.NONE to localized(MR.strings.none),
        Invert.HORIZONTAL to localized(MR.strings.tapping_inverted_horizontal),
        Invert.VERTICAL to localized(MR.strings.tapping_inverted_vertical),
        Invert.BOTH to localized(MR.strings.tapping_inverted_both),
    )

    data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float, val region: Region) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

        fun invert(mode: Invert): Zone = when {
            mode.horizontal && mode.vertical -> copy(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top)
            mode.vertical -> copy(top = 1f - bottom, bottom = 1f - top)
            mode.horizontal -> copy(left = 1f - right, right = 1f - left)
            else -> this
        }
    }

    /**
     * The zones of [layout]. [DEFAULT] is Right and left in the horizontal paged modes and
     * L shaped in the vertical ones (paged vertical, webtoon), as on Android.
     */
    fun zones(layout: Int, verticalViewer: Boolean, invert: Invert = Invert.NONE, smaller: Boolean = false): List<Zone> {
        val a = if (smaller) 0.25f else 0.33f
        val b = 1f - a
        val resolved = if (layout == DEFAULT || layout !in DEFAULT..DISABLED) {
            if (verticalViewer) L_SHAPED else RIGHT_AND_LEFT
        } else {
            layout
        }
        val zones = when (resolved) {
            L_SHAPED -> listOf(
                Zone(0f, a, a, b, Region.PREV),
                Zone(0f, 0f, 1f, a, Region.PREV),
                Zone(b, a, 1f, b, Region.NEXT),
                Zone(0f, b, 1f, 1f, Region.NEXT),
            )
            KINDLISH -> listOf(
                Zone(a, a, 1f, 1f, Region.NEXT),
                Zone(0f, a, a, 1f, Region.PREV),
            )
            EDGE -> listOf(
                Zone(0f, 0f, a, 1f, Region.NEXT),
                Zone(a, b, b, 1f, Region.PREV),
                Zone(b, 0f, 1f, 1f, Region.NEXT),
            )
            RIGHT_AND_LEFT -> listOf(
                Zone(0f, 0f, a, 1f, Region.LEFT),
                Zone(b, 0f, 1f, 1f, Region.RIGHT),
            )
            else -> emptyList()
        }
        return zones.map { it.invert(invert) }
    }

    /** What a tap at ([x], [y]) does; anywhere outside the zones opens the menu. */
    fun regionAt(x: Float, y: Float, zones: List<Zone>): Region =
        zones.firstOrNull { it.contains(x, y) }?.region ?: Region.MENU

    /**
     * Whether the horizontal directions are flipped: right-to-left reading, or the tap zones'
     * horizontal inversion. The screen's sides and the Left and Right keys follow the taps.
     */
    fun flipped(rtl: Boolean, invert: Invert = Invert.NONE): Boolean = rtl != invert.horizontal

    /**
     * Whether the vertical directions are flipped: the tap zones' vertical inversion.
     * The Up and Down keys follow the taps.
     */
    fun verticalFlipped(invert: Invert = Invert.NONE): Boolean = invert.vertical
}
