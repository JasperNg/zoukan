package zoukan.desktop.ui.common

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Where the main window was when Zoukan closed, so it opens there again: its position, size and
 * whether it was maximized. Sizes are in dp, as Compose's window state has them.
 */
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean) {
    fun encode(): String = "$x,$y,$width,$height,${if (maximized) 1 else 0}"

    /**
     * These bounds if a good part of the window's title bar is on one of [screens], so it can be
     * reached and moved; otherwise the size alone (null position), for a screen that is gone.
     */
    fun onScreen(screens: List<Rectangle>): WindowBounds? {
        val titleBar = Rectangle(x, y, width, TITLE_BAR)
        val visible = screens.any { screen ->
            val shown = screen.intersection(titleBar)
            !shown.isEmpty && shown.width >= MIN_VISIBLE && shown.height >= TITLE_BAR / 2
        }
        return if (visible) this else null
    }

    companion object {
        const val MIN_WIDTH = 800
        const val MIN_HEIGHT = 560
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 860
        private const val TITLE_BAR = 32
        private const val MIN_VISIBLE = 120

        fun decode(text: String): WindowBounds? {
            val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 5) return null
            val (x, y, width, height, maximized) = parts
            if (width < MIN_WIDTH || height < MIN_HEIGHT) return null
            return WindowBounds(x, y, width, height, maximized == 1)
        }

        /** The bounds of [state] to save; null while it is minimized, full screen or not placed yet. */
        fun of(state: WindowState, lastFloating: WindowBounds?): WindowBounds? {
            if (state.isMinimized || state.placement == WindowPlacement.Fullscreen) return null
            // A maximized window keeps the bounds it had before, to go back to when it is restored
            if (state.placement == WindowPlacement.Maximized) {
                return (lastFloating ?: WindowBounds(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, true)).copy(maximized = true)
            }
            val position = state.position
            if (!position.isSpecified || !state.size.isSpecified) return null
            return WindowBounds(
                position.x.value.toInt(),
                position.y.value.toInt(),
                state.size.width.value.toInt(),
                state.size.height.value.toInt(),
                maximized = false,
            )
        }

        /** The window state to open with: the saved bounds when they still fit a screen, else the default size, centered. */
        fun initialState(saved: String): WindowState = initialState(saved, screens())

        /** [initialState] for the given usable screen areas (screen bounds less the taskbar or dock). */
        internal fun initialState(saved: String, screens: List<Rectangle>): WindowState {
            val bounds = decode(saved)
            var placed = bounds?.onScreen(screens)
            var width = bounds?.width ?: DEFAULT_WIDTH
            var height = bounds?.height ?: DEFAULT_HEIGHT
            // Never open larger than the usable area of the screen the window lands on: the one its
            // title bar is on, else the first (the primary, where the platform default places it)
            val area = placed?.let { p ->
                screens.firstOrNull { it.intersects(Rectangle(p.x, p.y, p.width, TITLE_BAR)) }
            } ?: screens.firstOrNull()
            if (area != null) {
                width = minOf(width, area.width)
                height = minOf(height, area.height)
                // The smaller window must still have its title bar on a screen to keep the position
                placed = placed?.copy(width = width, height = height)?.onScreen(screens)
            }
            return WindowState(
                placement = if (bounds?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = placed?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
                size = DpSize(width.dp, height.dp),
            )
        }

        /** The minimum window size, never larger than [screen] (a usable area), so a small screen can show all of it. */
        internal fun minimumSize(screen: Rectangle?): Dimension =
            if (screen == null) {
                Dimension(MIN_WIDTH, MIN_HEIGHT)
            } else {
                Dimension(minOf(MIN_WIDTH, screen.width), minOf(MIN_HEIGHT, screen.height))
            }

        /** The usable area of [gc]: its bounds less the taskbar or dock; null when unknown. */
        internal fun usableArea(gc: GraphicsConfiguration?): Rectangle? = gc?.let {
            runCatching {
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(it)
                val b = it.bounds
                Rectangle(
                    b.x + insets.left,
                    b.y + insets.top,
                    b.width - insets.left - insets.right,
                    b.height - insets.top - insets.bottom,
                )
            }.getOrNull()
        }

        /** The usable areas of all screens, the primary first ([initialState] falls back to the first). */
        private fun screens(): List<Rectangle> = runCatching {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primary = env.defaultScreenDevice
            (listOf(primary) + env.screenDevices.filter { it != primary }).mapNotNull { usableArea(it.defaultConfiguration) }
        }.getOrDefault(emptyList())
    }
}
