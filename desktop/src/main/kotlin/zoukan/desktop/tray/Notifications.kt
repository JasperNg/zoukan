package zoukan.desktop.tray

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.security.Incognito

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

    /**
     * Whether a notification shows only that something happened, not what: with Security > "Hide
     * notification content" on, as on Android, and also while incognito mode is on (a desktop
     * addition, so titles and chapter names stay out of the OS notification center).
     */
    fun hidesContent(hideContentSetting: Boolean, incognito: Boolean): Boolean = hideContentSetting || incognito

    /** [hidesContent] for the current settings. A setting that cannot be read hides the content. */
    fun hidesContentNow(): Boolean = runCatching {
        hidesContent(
            hideContentSetting = Injekt.get<SecurityPreferences>().hideNotificationContent().get(),
            incognito = Incognito.mode(Injekt.get<PreferenceStore>()).get(),
        )
    }.getOrDefault(true)

    /** What the tray shows for [message]: [placeholder] under the app's name when [hideContent], otherwise the message itself. */
    fun shown(message: Message, hideContent: Boolean, placeholder: String): Message =
        if (hideContent) Message("Zoukan", placeholder, message.kind) else message
}
