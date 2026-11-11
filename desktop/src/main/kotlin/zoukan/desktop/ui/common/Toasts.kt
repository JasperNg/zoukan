package zoukan.desktop.ui.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Short messages shown at the bottom of the window, from anywhere in the app. */
object Toasts {
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<String> = messages.asSharedFlow()

    fun show(message: String) {
        messages.tryEmit(message)
    }

    fun error(prefix: String, e: Throwable) {
        show("$prefix: ${e.message ?: e::class.simpleName}")
    }
}
