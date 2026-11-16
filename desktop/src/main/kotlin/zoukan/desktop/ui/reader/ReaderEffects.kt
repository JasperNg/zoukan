package zoukan.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.OverlayColors
import zoukan.desktop.ui.common.ReadingModes
import zoukan.desktop.ui.common.Spacing
import zoukan.desktop.ui.common.collectAsState

/**
 * The reader's effects that are not controls: the reading mode shown when a chapter opens
 * ("Show reading mode"), the flash on page change (e-ink screens) and keeping the screen awake.
 */
@Composable
fun BoxScope.ReaderEffects(model: ReaderScreenModel, state: ReaderScreenModel.State) {
    val prefs = model.preferences
    val keepAwake by prefs.keepScreenOn().collectAsState()
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
    val reduceMotion = zoukan.desktop.ui.common.LocalReducedMotion.current
    AnimatedVisibility(
        modeShown,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Text(
            ReadingModes.label(state.readingMode),
            color = OverlayColors.OnScrim,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(OverlayColors.ToastScrim, Corners.Large)
                .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
        )
    }

    // "Flash on page change": a black or white screen for a moment every few pages, as Android's
    // e-ink option (it clears ghosting on e-ink monitors)
    val flash by prefs.flashOnPageChange().collectAsState()
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
 * Windows, `caffeinate` on macOS. With `display = false` it only keeps the system from sleeping,
 * for background work such as the favorites sync.
 */
object KeepAwake {
    fun interface Lock {
        fun release()
    }

    fun acquire(display: Boolean = true): Lock = runCatching {
        when (Platform.current) {
            Platform.WINDOWS -> {
                val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
                if (display) {
                    // Set on the UI thread, which lives as long as the app, and cleared from it
                    kernel.SetThreadExecutionState(ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED)
                    Lock { runCatching { kernel.SetThreadExecutionState(ES_CONTINUOUS) } }
                } else {
                    // The state is per thread and background work runs on pooled threads, so a
                    // thread of its own holds it until the lock is released
                    val done = java.util.concurrent.CountDownLatch(1)
                    Thread {
                        kernel.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)
                        runCatching { done.await() }
                        kernel.SetThreadExecutionState(ES_CONTINUOUS)
                    }.apply {
                        name = "zoukan-keep-awake"
                        isDaemon = true
                        start()
                    }
                    Lock { done.countDown() }
                }
            }
            Platform.MACOS -> {
                val pid = ProcessHandle.current().pid()
                val flags = if (display) arrayOf("-d", "-i") else arrayOf("-i")
                val process = ProcessBuilder("caffeinate", *flags, "-w", pid.toString())
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
