package zoukan.desktop.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/**
 * Shows the app's messages in [snackbar]; [ready] suspends while they must wait (the app lock, the
 * reader). A new message replaces the one shown, and messages that waited collapse to the newest,
 * so old messages never follow the user to other screens.
 */
suspend fun showMessages(messages: Flow<String>, snackbar: SnackbarHostState, ready: suspend () -> Unit) {
    // Cancelling showSnackbar dismisses the message shown
    messages.collectLatest { message ->
        ready()
        snackbar.showSnackbar(message)
    }
}

/** Where the app's snackbar sits and whether it may show now; one per window, see [LocalAppMessages]. */
@Stable
class AppMessages {
    private val insetReservations = LinkedHashMap<Any, Dp>()

    /** Raised by a screen whose action sits at the bottom, so the message shows above it */
    var bottomInset by mutableStateOf(0.dp)
        private set

    /**
     * Reserves at least [height] of bottom inset until the returned release callback is invoked.
     * Overlapping reservations keep [bottomInset] at the maximum active height even when disposed
     * out of order.
     */
    fun reserveBottomInset(height: Dp): () -> Unit {
        val token = Any()
        synchronized(insetReservations) {
            if (height > 0.dp) {
                insetReservations[token] = height
            }
            recomputeBottomInsetLocked()
        }
        var released = false
        return {
            synchronized(insetReservations) {
                if (!released) {
                    released = true
                    if (insetReservations.remove(token) != null) {
                        recomputeBottomInsetLocked()
                    }
                }
            }
        }
    }

    private fun recomputeBottomInsetLocked() {
        bottomInset = insetReservations.values.maxOrNull() ?: 0.dp
    }

    /** Above 0 while a screen (the reader) holds messages back */
    val held = MutableStateFlow(0)
}

val LocalAppMessages = staticCompositionLocalOf<AppMessages?> { null }

/** Keeps the snackbar above a bottom action of [height] while this is shown. */
@Composable
fun ReserveSnackbarSpace(height: Dp) {
    val messages = LocalAppMessages.current ?: return
    DisposableEffect(messages, height) {
        val release = messages.reserveBottomInset(height)
        onDispose(release)
    }
}

/** Holds messages back while this is shown; only the newest shows afterwards. */
@Composable
fun HoldMessages() {
    val messages = LocalAppMessages.current ?: return
    DisposableEffect(messages) {
        messages.held.update { it + 1 }
        onDispose { messages.held.update { it - 1 } }
    }
}
