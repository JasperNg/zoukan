package zoukan.desktop.ui.reader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * A page's own background for the "Automatic" reader theme, as Android's ImageUtil.chooseBackground:
 * the color of the page's margins, or a top to bottom gradient when its top and bottom differ.
 * [landscape] is for a window wider than tall, which Android always fills with one color.
 * Colors are ARGB; one color is a plain fill, four a gradient.
 */
class PageBackground(val portrait: List<Int>, val landscape: List<Int>) {

    fun brush(wideWindow: Boolean): Brush {
        val colors = (if (wideWindow) landscape else portrait).map { Color(it) }
        return if (colors.size == 1) Brush.verticalGradient(listOf(colors[0], colors[0])) else Brush.verticalGradient(colors)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Reads the background of a [width] by [height] image whose ARGB pixels [pixel] returns. */
        fun of(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): PageBackground =
            PageBackground(choose(width, height, pixel, landscape = false), choose(width, height, pixel, landscape = true))

        /** A port of Android's ImageUtil.chooseBackground, kept close to the original. */
        internal fun choose(width: Int, height: Int, get: (x: Int, y: Int) -> Int, landscape: Boolean): List<Int> {
            val whiteColor = WHITE
            if (width < 50 || height < 50) return listOf(whiteColor)
            val image = { x: Int, y: Int -> get(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) }

            val top = 5
            val bot = height - 5
            val left = (width * 0.0275).toInt()
            val right = width - left
            val midX = width / 2
            val midY = height / 2
            val offsetX = (width * 0.01).toInt()
            val leftOffsetX = left - offsetX
            val rightOffsetX = right + offsetX

            val topLeftPixel = image(left, top)
            val topRightPixel = image(right, top)
            val midLeftPixel = image(left, midY)
            val midRightPixel = image(right, midY)
            val topCenterPixel = image(midX, top)
            val botLeftPixel = image(left, bot)
            val bottomCenterPixel = image(midX, bot)
            val botRightPixel = image(right, bot)

            val topLeftIsDark = topLeftPixel.isDark()
            val topRightIsDark = topRightPixel.isDark()
            val midLeftIsDark = midLeftPixel.isDark()
            val midRightIsDark = midRightPixel.isDark()
            val topMidIsDark = topCenterPixel.isDark()
            val botLeftIsDark = botLeftPixel.isDark()
            val botRightIsDark = botRightPixel.isDark()

            var darkBG =
                (topLeftIsDark && (botLeftIsDark || botRightIsDark || topRightIsDark || midLeftIsDark || topMidIsDark)) ||
                    (topRightIsDark && (botRightIsDark || botLeftIsDark || midRightIsDark || topMidIsDark))

            val topAndBotPixels = listOf(topLeftPixel, topCenterPixel, topRightPixel, botRightPixel, bottomCenterPixel, botLeftPixel)
            val isNotWhiteAndCloseTo = topAndBotPixels.mapIndexed { index, color ->
                val other = topAndBotPixels[(index + 1) % topAndBotPixels.size]
                !color.isWhite() && color.isCloseTo(other)
            }
            if (isNotWhiteAndCloseTo.all { it }) return listOf(topLeftPixel)

            val cornerPixels = listOf(topLeftPixel, topRightPixel, botLeftPixel, botRightPixel)
            if (cornerPixels.count { it.isWhite() } > 2) darkBG = false

            var blackColor = when {
                topLeftIsDark -> topLeftPixel
                topRightIsDark -> topRightPixel
                botLeftIsDark -> botLeftPixel
                botRightIsDark -> botRightPixel
                else -> whiteColor
            }

            var overallWhitePixels = 0
            var overallBlackPixels = 0
            var topBlackStreak = 0
            var topWhiteStreak = 0
            var botBlackStreak = 0
            var botWhiteStreak = 0
            outer@ for (x in intArrayOf(left, right, leftOffsetX, rightOffsetX)) {
                var whitePixelsStreak = 0
                var whitePixels = 0
                var blackPixelsStreak = 0
                var blackPixels = 0
                var blackStreak = false
                var whiteStreak = false
                val notOffset = x == left || x == right
                inner@ for ((index, y) in (0..<height step height / 25).withIndex()) {
                    val pixel = image(x, y)
                    val pixelOff = image(x + (if (x < width / 2) -offsetX else offsetX), y)
                    if (pixel.isWhite()) {
                        whitePixelsStreak++
                        whitePixels++
                        if (notOffset) overallWhitePixels++
                        if (whitePixelsStreak > 14) whiteStreak = true
                        if (whitePixelsStreak > 6 && whitePixelsStreak >= index - 1) topWhiteStreak = whitePixelsStreak
                    } else {
                        whitePixelsStreak = 0
                        if (pixel.isDark() && pixelOff.isDark()) {
                            blackPixels++
                            if (notOffset) overallBlackPixels++
                            blackPixelsStreak++
                            if (blackPixelsStreak >= 14) blackStreak = true
                            continue@inner
                        }
                    }
                    if (blackPixelsStreak > 6 && blackPixelsStreak >= index - 1) topBlackStreak = blackPixelsStreak
                    blackPixelsStreak = 0
                }
                if (blackPixelsStreak > 6) {
                    botBlackStreak = blackPixelsStreak
                } else if (whitePixelsStreak > 6) {
                    botWhiteStreak = whitePixelsStreak
                }
                when {
                    blackPixels > 22 -> {
                        if (x == right || x == rightOffsetX) {
                            blackColor = when {
                                topRightIsDark -> topRightPixel
                                botRightIsDark -> botRightPixel
                                else -> blackColor
                            }
                        }
                        darkBG = true
                        overallWhitePixels = 0
                        break@outer
                    }
                    blackStreak -> {
                        darkBG = true
                        if (x == right || x == rightOffsetX) {
                            blackColor = when {
                                topRightIsDark -> topRightPixel
                                botRightIsDark -> botRightPixel
                                else -> blackColor
                            }
                        }
                        if (blackPixels > 18) {
                            overallWhitePixels = 0
                            break@outer
                        }
                    }
                    whiteStreak || whitePixels > 22 -> darkBG = false
                }
            }

            val topIsBlackStreak = topBlackStreak > topWhiteStreak
            val bottomIsBlackStreak = botBlackStreak > botWhiteStreak
            if (overallWhitePixels > 9 && overallWhitePixels > overallBlackPixels) darkBG = false
            if (topIsBlackStreak && bottomIsBlackStreak) darkBG = true

            if (landscape) return listOf(if (darkBG) blackColor else whiteColor)

            val botCornersIsWhite = botLeftPixel.isWhite() && botRightPixel.isWhite()
            val topCornersIsWhite = topLeftPixel.isWhite() && topRightPixel.isWhite()
            val topCornersIsDark = topLeftIsDark && topRightIsDark
            val botCornersIsDark = botLeftIsDark && botRightIsDark
            val topOffsetCornersIsDark = image(leftOffsetX, top).isDark() && image(rightOffsetX, top).isDark()
            val botOffsetCornersIsDark = image(leftOffsetX, bot).isDark() && image(rightOffsetX, bot).isDark()

            return when {
                darkBG && botCornersIsWhite -> listOf(blackColor, blackColor, whiteColor, whiteColor)
                darkBG && topCornersIsWhite -> listOf(whiteColor, whiteColor, blackColor, blackColor)
                darkBG -> listOf(blackColor)
                topIsBlackStreak || (topCornersIsDark && topOffsetCornersIsDark && (topMidIsDark || overallBlackPixels > 9)) ->
                    listOf(blackColor, blackColor, whiteColor, whiteColor)
                bottomIsBlackStreak || (botCornersIsDark && botOffsetCornersIsDark && (bottomCenterPixel.isDark() || overallBlackPixels > 9)) ->
                    listOf(whiteColor, whiteColor, blackColor, blackColor)
                else -> listOf(whiteColor)
            }
        }

        private val Int.alpha get() = (this ushr 24) and 0xFF
        private val Int.red get() = (this shr 16) and 0xFF
        private val Int.green get() = (this shr 8) and 0xFF
        private val Int.blue get() = this and 0xFF

        private fun Int.isDark(): Boolean = red < 40 && blue < 40 && green < 40 && alpha > 200

        private fun Int.isCloseTo(other: Int): Boolean =
            abs(red - other.red) < 30 && abs(green - other.green) < 30 && abs(blue - other.blue) < 30

        private fun Int.isWhite(): Boolean = red + blue + green > 740
    }
}
