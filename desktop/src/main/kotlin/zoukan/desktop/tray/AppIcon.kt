package zoukan.desktop.tray

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.awt.image.BufferedImage

/**
 * The app icon: the installers' vector master (desktop/packaging/icons/zoukan.svg, copied into the
 * resources by the build), drawn at the exact pixel size of each use so it stays sharp at every
 * display scale. A fixed-size bitmap blurs or aliases once Windows scaling or a Retina display
 * stretches or shrinks it.
 */
object AppIcon {
    /**
     * The sizes the window gets: the title bar (16 px at 100%) and the taskbar (32 px at 100%) at
     * Windows' 100–250% scales, and the larger sizes Alt+Tab and the task switcher use.
     */
    val WindowSizes = listOf(16, 20, 24, 28, 30, 32, 36, 40, 48, 56, 60, 64, 72, 80, 96, 128, 256)

    // Parsed once; the window, the tray and the More header all draw on the UI thread
    private val vector: Painter by lazy {
        runCatching {
            val stream = checkNotNull(AppIcon::class.java.getResourceAsStream("/zoukan-icon.svg")) { "zoukan-icon.svg is not in the resources" }
            stream.use { loadSvgPainter(it, Density(1f)) }
        }.getOrElse { e ->
            logcat(LogPriority.ERROR, e) { "Could not load the app icon" }
            FallbackPainter
        }
    }

    private val windowImages by lazy { WindowSizes.map(::image) }

    /** A vector painter of the icon, for Compose: it draws at whatever size and density it is given. */
    fun painter(): Painter = vector

    /** The icon drawn at [size] × [size] pixels. */
    fun image(size: Int): BufferedImage {
        val bitmap = ImageBitmap(size, size)
        val pixels = Size(size.toFloat(), size.toFloat())
        val painter = vector
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), pixels) {
            with(painter) { draw(pixels) }
        }
        return bitmap.toAwtImage()
    }

    /** The icon at each of [WindowSizes], for [java.awt.Window.setIconImages]. */
    fun windowImages(): List<BufferedImage> = windowImages

    /** The app's blue rounded square, if the vector icon is missing from the resources. */
    private object FallbackPainter : Painter() {
        override val intrinsicSize = Size.Unspecified

        override fun DrawScope.onDraw() {
            drawRoundRect(Color(0xFF2B6CE0), cornerRadius = CornerRadius(size.minDimension / 6))
        }
    }
}
