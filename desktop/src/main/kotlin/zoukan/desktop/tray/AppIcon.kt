package zoukan.desktop.tray

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The window and tray icon: the app icon the installers use (desktop/packaging/icons, made by
 * make_icons.py), from the app's resources.
 */
object AppIcon {
    private val source: BufferedImage? by lazy {
        runCatching { AppIcon::class.java.getResourceAsStream("/zoukan-icon.png")?.use(ImageIO::read) }.getOrNull()
    }

    fun painter(size: Int = 64): Painter = BitmapPainter(image(size).toComposeImageBitmap())

    fun image(size: Int = 64): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val icon = source
            if (icon != null) {
                g.drawImage(icon, 0, 0, size, size, null)
            } else {
                g.color = java.awt.Color(0x2B, 0x6C, 0xE0)
                g.fillRoundRect(0, 0, size, size, size / 3, size / 3)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
