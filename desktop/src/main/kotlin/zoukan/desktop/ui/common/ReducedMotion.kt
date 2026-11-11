package zoukan.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import java.util.concurrent.TimeUnit

/**
 * Whether the system asks apps to cut down on motion: "Animation effects" off on Windows,
 * "Reduce motion" on macOS, animations off in GNOME. Read once; when it cannot be read, motion
 * stays on.
 */
object ReducedMotion {
    val enabled: Boolean by lazy {
        runCatching {
            when (Platform.current) {
                Platform.WINDOWS -> windows()
                Platform.MACOS -> run("defaults", "read", "com.apple.universalaccess", "reduceMotion") == "1"
                Platform.LINUX -> run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations") == "false"
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "Reading the reduced motion setting failed" } }.getOrNull() ?: false
    }

    private fun windows(): Boolean {
        val on = IntByReference()
        if (!MotionUser32.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, on, 0)) return false
        return on.value == 0
    }

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        return out.takeIf { process.exitValue() == 0 }
    }

    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    @Suppress("FunctionName")
    private interface MotionUser32 : StdCallLibrary {
        fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean

        companion object {
            val INSTANCE: MotionUser32 by lazy { Native.load("user32", MotionUser32::class.java) }
        }
    }
}

/** [ReducedMotion.enabled], read off the UI thread; false until it is known. */
@Composable
fun reducedMotion(): State<Boolean> = produceState(false) {
    value = withContext(Dispatchers.IO) { ReducedMotion.enabled }
}
