package zoukan.desktop.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import zoukan.desktop.app.Toasts
import zoukan.desktop.tray.Notifications

/**
 * Records what the user is shown while it is open: toasts ([Toasts]) and notifications
 * ([Notifications]). Both are shared flows without replay, so it starts listening when made, before
 * the code under test runs. A fault-injection test uses it to check that a failure reaches the user,
 * not only the log.
 */
class UserMessages : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val toastList = MutableStateFlow<List<String>>(emptyList())
    private val notificationList = MutableStateFlow<List<Notifications.Message>>(emptyList())

    init {
        // Undispatched: subscribed before the constructor returns
        scope.launch(start = CoroutineStart.UNDISPATCHED) { Toasts.flow.collect { t -> toastList.update { it + t } } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { Notifications.flow.collect { n -> notificationList.update { it + n } } }
    }

    val toasts: List<String> get() = toastList.value
    val notifications: List<Notifications.Message> get() = notificationList.value

    /** The error notifications so far. */
    val errors: List<Notifications.Message> get() = notifications.filter { it.kind == Notifications.Kind.ERROR }

    /** Waits until a toast [matches], and returns it. */
    fun awaitToast(timeoutMillis: Long = 10_000, matches: (String) -> Boolean): String = runBlocking {
        withTimeout(timeoutMillis) { toastList.first { list -> list.any(matches) }.first(matches) }
    }

    /** Waits until an error notification [matches], and returns it. */
    fun awaitError(timeoutMillis: Long = 10_000, matches: (Notifications.Message) -> Boolean = { true }): Notifications.Message = runBlocking {
        val wanted = { m: Notifications.Message -> m.kind == Notifications.Kind.ERROR && matches(m) }
        withTimeout(timeoutMillis) { notificationList.first { list -> list.any(wanted) }.first(wanted) }
    }

    override fun close() {
        scope.cancel()
    }
}
