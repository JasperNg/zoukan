package zoukan.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import zoukan.desktop.ui.common.ReadingModes

/**
 * The reader's effects that are not controls: the reading mode shown when a chapter opens
 * ("Show reading mode"), the flash on page change (e-ink screens) and keeping the screen awake.
 */
@Composable
fun BoxScope.ReaderEffects(model: ReaderScreenModel, state: ReaderScreenModel.State) {
    val prefs = model.preferences
    val keepAwake by prefs.keepScreenOn().changes().collectAsState(prefs.keepScreenOn().get())
    if (keepAwake) {
        DisposableEffect(Unit) {
            val lock = KeepAwake.acquire()
            onDispose { lock.release() }
        }
    }

    // "Show reading mode": the mode's name for a moment after the reader opens or the mode changes
    var modeShown by remember { mutableStateOf(false) }
    LaunchedEffect(state.readingMode, state.manga?.id) {
        if (state.manga == null || !prefs.showReadingMode().get()) return@LaunchedEffect
        modeShown = true
        delay(1500)
        modeShown = false
    }
    AnimatedVisibility(modeShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
        Text(
            ReadingModes.label(state.readingMode),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    // "Flash on page change": a black or white screen for a moment every few pages, as Android's
    // e-ink option (it clears ghosting on e-ink monitors)
    val flash by prefs.flashOnPageChange().changes().collectAsState(prefs.flashOnPageChange().get())
    if (flash) {
        var turns by remember { mutableIntStateOf(0) }
        var color by remember { mutableStateOf<Color?>(null) }
        var first by remember { mutableStateOf(true) }
        LaunchedEffect(state.currentPage, state.generation) {
            if (first) {
                first = false
                return@LaunchedEffect
            }
            turns++
            if (turns % prefs.flashPageInterval().get().coerceAtLeast(1) != 0) return@LaunchedEffect
            val duration = prefs.flashDurationMillis().get().coerceIn(100, 1500).toLong()
            when (prefs.flashColor().get()) {
                "WHITE" -> {
                    color = Color.White
                    delay(duration)
                }
                "WHITE_BLACK" -> {
                    color = Color.White
                    delay(duration / 2)
                    color = Color.Black
                    delay(duration / 2)
                }
                else -> {
                    color = Color.Black
                    delay(duration)
                }
            }
            color = null
        }
        color?.let { Box(Modifier.fillMaxSize().background(it)) }
    }
}

/**
 * Keeps the display on while the reader is open ("Keep screen on"): SetThreadExecutionState on
 * Windows, `caffeinate` on macOS.
 */
object KeepAwake {
    fun interface Lock {
        fun release()
    }

    fun acquire(): Lock = runCatching {
        when (Platform.current) {
            Platform.WINDOWS -> {
                val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
                // Set on the UI thread, which lives as long as the app, and cleared from it
                kernel.SetThreadExecutionState(ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED)
                Lock { runCatching { kernel.SetThreadExecutionState(ES_CONTINUOUS) } }
            }
            Platform.MACOS -> {
                val pid = ProcessHandle.current().pid()
                val process = ProcessBuilder("caffeinate", "-d", "-i", "-w", pid.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Lock { process.destroy() }
            }
            else -> Lock {}
        }
    }.getOrElse {
        logcat(LogPriority.WARN, it) { "Could not keep the screen on" }
        Lock {}
    }

    private const val ES_CONTINUOUS = Int.MIN_VALUE
    private const val ES_SYSTEM_REQUIRED = 1
    private const val ES_DISPLAY_REQUIRED = 2
}
