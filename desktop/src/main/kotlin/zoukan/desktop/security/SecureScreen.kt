package zoukan.desktop.security

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences.SecureScreenMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/**
 * Settings > Security and privacy > Secure screen, as Android's SecureActivityDelegate: with
 * "Always", or with "Incognito" while incognito mode is on, the app's windows (the main window and
 * the built-in browser's) are left out of screenshots, screen recordings and screen sharing.
 *
 * Windows: SetWindowDisplayAffinity with WDA_EXCLUDEFROMCAPTURE (Windows 10 2004 and later; the
 * window is left out), else WDA_MONITOR (older Windows; the window shows black). macOS: the
 * windows' sharingType set to NSWindowSharingNone. Linux has no such thing, so it does nothing there.
 */
object SecureScreen {

    /** Android's rule: [SecureScreenMode.INCOGNITO] follows the app-wide incognito mode. */
    fun isSecure(mode: SecureScreenMode, incognito: Boolean): Boolean =
        mode == SecureScreenMode.ALWAYS || (mode == SecureScreenMode.INCOGNITO && incognito)

    // The windows to keep in step; weak, so a disposed window just drops out. Guarded by itself
    private val windows: MutableSet<Window> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    var secure: Boolean = false
        private set

    /** Follows the preference and incognito mode for every attached window, from now on. */
    fun start(scope: CoroutineScope, preferences: SecurityPreferences, incognitoMode: Preference<Boolean>) {
        combine(preferences.secureScreen().changes(), incognitoMode.changes(), ::isSecure)
            .distinctUntilChanged()
            .onEach { set(it) }
            .launchIn(scope)
    }

    /** Keeps [window] in step from now on, also once it opens (its native window exists only then). */
    fun attach(window: Window) {
        synchronized(windows) { windows += window }
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) = apply(window, secure)
        })
        onEdt { if (window.isDisplayable) apply(window, secure) }
    }

    private fun set(value: Boolean) {
        secure = value
        val snapshot = synchronized(windows) { windows.toList() }
        onEdt { snapshot.filter { it.isDisplayable }.forEach { apply(it, secure) } }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private fun apply(window: Window, secure: Boolean) {
        runCatching {
            when (Platform.current) {
                Platform.WINDOWS -> WindowsAffinity.apply(window, secure)
                Platform.MACOS -> MacSharing.applyToAll(secure)
                Platform.LINUX -> Unit
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Secure screen: could not change the window's capture setting" } }
    }

    /** The display affinity values [WindowsAffinity] uses, for tests. */
    internal object Affinity {
        const val NONE = 0x0
        const val MONITOR = 0x1
        const val EXCLUDE_FROM_CAPTURE = 0x11
    }

    /**
     * The affinities to try in order for [secure]: left out of captures, and where Windows is too
     * old for that, shown black in them.
     */
    internal fun windowsAffinities(secure: Boolean): List<Int> =
        if (secure) listOf(Affinity.EXCLUDE_FROM_CAPTURE, Affinity.MONITOR) else listOf(Affinity.NONE)

    private object WindowsAffinity {
        fun apply(window: Window, secure: Boolean) {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window) ?: return)
            val applied = windowsAffinities(secure).any { User32Affinity.INSTANCE.SetWindowDisplayAffinity(hwnd, it) }
            if (!applied) logcat(LogPriority.WARN) { "Secure screen: SetWindowDisplayAffinity failed (${Native.getLastError()})" }
        }
    }

    @Suppress("FunctionName")
    private interface User32Affinity : StdCallLibrary {
        fun SetWindowDisplayAffinity(hwnd: WinDef.HWND, affinity: Int): Boolean

        companion object {
            val INSTANCE: User32Affinity by lazy { Native.load("user32", User32Affinity::class.java) }
        }
    }

    /**
     * macOS: every NSWindow of the app gets the sharing type, on AppKit's main thread (which is not
     * AWT's), through the Objective-C runtime.
     */
    private object MacSharing {
        private const val NS_WINDOW_SHARING_NONE = 0L
        private const val NS_WINDOW_SHARING_READ_ONLY = 1L

        private val objc by lazy { NativeLibrary.getInstance("objc") }
        private val msgSend by lazy { objc.getFunction("objc_msgSend") }
        private val mainQueue by lazy { NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }

        // A queued callback must stay reachable until it has run
        private val pending: MutableSet<DispatchFunction> = Collections.synchronizedSet(HashSet())

        fun applyToAll(secure: Boolean) {
            val work = object : DispatchFunction {
                override fun invoke(context: Pointer?) {
                    try {
                        // Read when it runs, so the last change wins whatever the order of the calls
                        setSharingType(if (SecureScreen.secure) NS_WINDOW_SHARING_NONE else NS_WINDOW_SHARING_READ_ONLY)
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) { "Secure screen: could not set the windows' sharing type" }
                    } finally {
                        pending -= this
                    }
                }
            }
            pending += work
            Dispatch.INSTANCE.dispatch_async_f(mainQueue, null, work)
        }

        private fun setSharingType(type: Long) {
            val app = send(objc.getFunction("objc_getClass").invokePointer(arrayOf("NSApplication")), "sharedApplication") ?: return
            val windows = send(app, "windows") ?: return
            val count = msgSend.invokeLong(arrayOf(windows, sel("count")))
            for (i in 0 until count) {
                val window = msgSend.invokePointer(arrayOf(windows, sel("objectAtIndex:"), i)) ?: continue
                msgSend.invokeVoid(arrayOf(window, sel("setSharingType:"), type))
            }
        }

        private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))

        private fun send(receiver: Pointer?, selector: String): Pointer? =
            receiver?.let { msgSend.invokePointer(arrayOf(it, sel(selector))) }
    }

    private interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    @Suppress("FunctionName")
    private interface Dispatch : Library {
        fun dispatch_async_f(queue: Pointer, context: Pointer?, work: DispatchFunction)

        companion object {
            val INSTANCE: Dispatch by lazy { Native.load("System", Dispatch::class.java) }
        }
    }
}
