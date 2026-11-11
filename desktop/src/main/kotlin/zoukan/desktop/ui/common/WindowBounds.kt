package zoukan.desktop.ui.common

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

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
        fun initialState(saved: String): WindowState {
            val bounds = decode(saved)
            val placed = bounds?.onScreen(screens())
            return WindowState(
                placement = if (bounds?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = placed?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
                size = DpSize((bounds?.width ?: DEFAULT_WIDTH).dp, (bounds?.height ?: DEFAULT_HEIGHT).dp),
            )
        }

        private fun screens(): List<Rectangle> = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())
    }
}
