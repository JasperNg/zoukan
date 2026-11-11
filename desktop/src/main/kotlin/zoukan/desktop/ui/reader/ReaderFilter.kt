package zoukan.desktop.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.ImageFilter
import zoukan.desktop.ui.common.DesktopPreferences

/** The reader's color settings, as Android's reader Filter tab: brightness, a color filter, grayscale and inverted colors. */
data class ReaderFilter(
    val brightness: Int? = null,
    val color: Int? = null,
    val mode: Int = 0,
    val grayscale: Boolean = false,
    val inverted: Boolean = false,
) {
    val isActive get() = brightness != null || color != null || grayscale || inverted

    companion object {
        @Composable
        fun collect(prefs: DesktopPreferences): ReaderFilter {
            val brightnessOn by prefs.customBrightness().changes().collectAsState(prefs.customBrightness().get())
            val brightness by prefs.customBrightnessValue().changes().collectAsState(prefs.customBrightnessValue().get())
            val colorOn by prefs.colorFilter().changes().collectAsState(prefs.colorFilter().get())
            val color by prefs.colorFilterValue().changes().collectAsState(prefs.colorFilterValue().get())
            val mode by prefs.colorFilterMode().changes().collectAsState(prefs.colorFilterMode().get())
            val grayscale by prefs.grayscale().changes().collectAsState(prefs.grayscale().get())
            val inverted by prefs.invertedColors().changes().collectAsState(prefs.invertedColors().get())
            return ReaderFilter(
                brightness = brightness.takeIf { brightnessOn && it != 0 },
                color = color.takeIf { colorOn },
                mode = mode,
                grayscale = grayscale,
                inverted = inverted,
            )
        }

        fun blendMode(mode: Int): BlendMode = when (mode) {
            1 -> BlendMode.Multiply
            2 -> BlendMode.Screen
            3 -> BlendMode.Overlay
            4 -> BlendMode.Lighten
            5 -> BlendMode.Darken
            else -> BlendMode.SrcOver
        }

        private fun matrix(filter: ReaderFilter): ColorMatrix? {
            var m: FloatArray? = null
            fun then(next: FloatArray) {
                m = m?.let { concat(next, it) } ?: next
            }
            if (filter.grayscale) {
                then(
                    floatArrayOf(
                        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
            }
            if (filter.inverted) {
                then(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 1f,
                        0f, -1f, 0f, 0f, 1f,
                        0f, 0f, -1f, 0f, 1f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
            }
            // Android raises the screen's brightness above zero; a window can only brighten the page
            val brightness = filter.brightness
            if (brightness != null && brightness > 0) {
                val add = brightness / 100f * 0.5f
                then(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, add,
                        0f, 1f, 0f, 0f, add,
                        0f, 0f, 1f, 0f, add,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
            }
            val result: FloatArray = m ?: return null
            return ColorMatrix(result)
        }

        /** a after b, for 4x5 color matrices whose translation is in 0..1 units. */
        private fun concat(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(20)
            for (row in 0 until 4) {
                for (col in 0 until 5) {
                    var sum = if (col == 4) a[row * 5 + 4] else 0f
                    for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
                    out[row * 5 + col] = sum
                }
            }
            return out
        }

        /** Applies [filter] to everything the reader draws. */
        fun Modifier.readerFilter(filter: ReaderFilter): Modifier {
            if (!filter.isActive) return this
            val matrix = matrix(filter)
            // The overlays go over the filtered pages, as Android lays its filter views over the reader
            return this
                .drawWithContent {
                    drawContent()
                    filter.color?.let { argb -> drawRect(Color(argb), blendMode = blendMode(filter.mode)) }
                    filter.brightness?.takeIf { it < 0 }?.let { drawRect(Color.Black.copy(alpha = -it / 100f)) }
                }
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = matrix?.let { ImageFilter.makeColorFilter(ColorFilter.makeMatrix(it), null, null).asComposeRenderEffect() }
                }
        }
    }
}
