package zoukan.desktop.tray

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Notifications from background work (library updates, downloads, automatic backups). The window
 * shows them as tray notifications, which the OS lists in its notification center; without a tray
 * (tests, the command line) they only go to the log.
 */
object Notifications {
    enum class Kind { INFO, WARNING, ERROR }

    data class Message(val title: String, val text: String, val kind: Kind = Kind.INFO)

    private val messages = MutableSharedFlow<Message>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<Message> = messages.asSharedFlow()

    fun post(title: String, text: String, kind: Kind = Kind.INFO) {
        messages.tryEmit(Message(title, text, kind))
    }
}
