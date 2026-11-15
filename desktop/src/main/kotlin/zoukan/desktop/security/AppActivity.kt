package zoukan.desktop.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.AWTEvent
import java.awt.FileDialog
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent

/**
 * When the app lock's "Lock when idle" counts the app as away, besides a minimized or hidden window.
 * Android locks on onStop, when another app comes to the front; the desktop's nearest thing is
 * another app's window becoming the active one.
 */
object LockTriggers {
    /**
     * One of the app's windows stopped being the active one. It went to the background unless the
     * window that became active is also the app's ([oppositeIsOurs]: a dialog, the built-in
     * browser), or a native file dialog took over (Windows and macOS report those as no window).
     */
    fun deactivationIsBackground(oppositeIsOurs: Boolean, nativeFileDialogOpen: Boolean): Boolean =
        !oppositeIsOurs && !nativeFileDialogOpen
}

/**
 * Whether one of the app's windows is the active one, as AWT reports activation for every window
 * of the app. "Lock when idle" counts from the moment the app stops being active, as Android counts
 * from onStop; input inactivity alone never locks.
 */
object AppActivity {
    private val _active = MutableStateFlow(true)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var installed = false

    /** Starts listening; does nothing without a display or when it already listens. */
    @Synchronized
    fun install() {
        if (installed || GraphicsEnvironment.isHeadless()) return
        installed = true
        Toolkit.getDefaultToolkit().addAWTEventListener({ event -> onEvent(event) }, AWTEvent.WINDOW_EVENT_MASK)
    }

    private fun onEvent(event: AWTEvent) {
        when (event.id) {
            WindowEvent.WINDOW_ACTIVATED -> _active.value = true
            WindowEvent.WINDOW_DEACTIVATED -> {
                val opposite = (event as WindowEvent).oppositeWindow
                val fileDialog = Window.getWindows().any { it is FileDialog && it.isShowing }
                if (LockTriggers.deactivationIsBackground(opposite != null, fileDialog)) _active.value = false
            }
        }
    }
}
