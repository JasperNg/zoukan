package zoukan.desktop.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * What one screen of the paged viewer shows: one page, two pages side by side, or one half of a
 * wide page that "Split wide pages" cuts in two, as Android's InsertPage.
 */
data class Spread(val pages: List<Int>, val part: PagePart = PagePart.WHOLE) {
    val first: Int get() = pages.first()
    val last: Int get() = pages.last()
}

/** Which part of a page shows: all of it, or the half read first or second. */
enum class PagePart { WHOLE, FIRST_HALF, SECOND_HALF }

object ReaderSpreads {
    /**
     * The spreads of [count] pages. With [double], the first page (the cover) stands alone and the
     * rest go in pairs, as Android's page layout; [shift] (SY's "Shift double pages") pairs from the
     * first page instead. Wide pages always stand alone, and with [split] each becomes two halves.
     */
    fun build(count: Int, double: Boolean, wide: Set<Int> = emptySet(), split: Boolean = false, shift: Boolean = false): List<Spread> {
        val spreads = ArrayList<Spread>(count)
        var pending: Int? = null
        fun flush() {
            pending?.let { spreads += Spread(listOf(it)) }
            pending = null
        }
        val pairing = double && count >= 3
        for (i in 0 until count) {
            when {
                i in wide -> {
                    flush()
                    if (split) {
                        spreads += Spread(listOf(i), PagePart.FIRST_HALF)
                        spreads += Spread(listOf(i), PagePart.SECOND_HALF)
                    } else {
                        spreads += Spread(listOf(i))
                    }
                }
                !pairing -> spreads += Spread(listOf(i))
                i == 0 && !shift -> spreads += Spread(listOf(0))
                pending == null -> pending = i
                else -> {
                    spreads += Spread(listOf(pending!!, i))
                    pending = null
                }
            }
        }
        flush()
        return spreads
    }

    /** True when the left half of a split page is read first: left to right, unless inverted */
    fun leftHalfFirst(rtl: Boolean, invert: Boolean): Boolean = !rtl != invert

    /** The size [image] takes when [part] of it shows, turned by [rotation] degrees (0, 90 or -90), with [gap] pixels in its middle. */
    fun shownSize(image: PageImage, part: PagePart, rotation: Int, gap: Int = 0): IntSize {
        val width = if (part == PagePart.WHOLE) image.width + gap else image.width / 2
        val height = image.height
        return if (rotation != 0) IntSize(height, width) else IntSize(width, height)
    }
}

/**
 * [image], or the half of it [part] and [leftFirst] say, turned by [rotation] degrees, drawn to
 * fill the composable's bounds. [gap] pixels of the page's own size split the halves of a whole
 * page (Android's wide page center margin).
 */
@Composable
fun PageContent(
    image: PageImage,
    part: PagePart,
    leftFirst: Boolean,
    rotation: Int,
    modifier: Modifier = Modifier,
    gap: Int = 0,
) {
    Canvas(modifier.testTag("page-image")) {
        val shown = ReaderSpreads.shownSize(image, part, rotation, gap)
        val scale = size.width / shown.width.coerceAtLeast(1)
        // The page before turning, centered on the canvas
        val drawWidth = (if (part == PagePart.WHOLE) image.width + gap else image.width / 2) * scale
        val drawHeight = image.height * scale
        val origin = Offset((size.width - drawWidth) / 2f, (size.height - drawHeight) / 2f)
        val half = image.width / 2
        val srcX = when (part) {
            PagePart.WHOLE -> 0
            PagePart.FIRST_HALF -> if (leftFirst) 0 else image.width - half
            PagePart.SECOND_HALF -> if (leftFirst) image.width - half else 0
        }
        val srcWidth = if (part == PagePart.WHOLE) image.width else half
        rotate(rotation.toFloat(), pivot = center) {
            translate(origin.x, origin.y) {
                var y = 0
                image.slices.forEach { slice ->
                    val h = slice.height
                    val top = (y * scale).roundToInt()
                    val bottom = ((y + h) * scale).roundToInt()
                    if (gap > 0 && part == PagePart.WHOLE) {
                        // Left half, the gap, then the right half
                        val leftW = half
                        val rightW = image.width - half
                        drawImage(
                            slice,
                            srcOffset = IntOffset(0, 0),
                            srcSize = IntSize(leftW, h),
                            dstOffset = IntOffset(0, top),
                            dstSize = IntSize((leftW * scale).roundToInt(), bottom - top),
                            filterQuality = FilterQuality.High,
                        )
                        drawImage(
                            slice,
                            srcOffset = IntOffset(half, 0),
                            srcSize = IntSize(rightW, h),
                            dstOffset = IntOffset(((half + gap) * scale).roundToInt(), top),
                            dstSize = IntSize((rightW * scale).roundToInt(), bottom - top),
                            filterQuality = FilterQuality.High,
                        )
                    } else {
                        drawImage(
                            slice,
                            srcOffset = IntOffset(srcX, 0),
                            srcSize = IntSize(srcWidth, h),
                            dstOffset = IntOffset(0, top),
                            dstSize = IntSize(drawWidth.roundToInt(), bottom - top),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    y += h
                }
            }
        }
    }
}
