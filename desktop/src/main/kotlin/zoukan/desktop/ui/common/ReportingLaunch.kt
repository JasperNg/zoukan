package zoukan.desktop.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.Toasts
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Launches [block]. A failure (never a cancellation) is logged, shown as a toast with
 * [failedMessage], and handed to [onError] so the caller can reset its in-progress flags.
 */
fun CoroutineScope.launchReporting(
    failedMessage: () -> String,
    onError: (Throwable) -> Unit = {},
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job = launchAction(context) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e.rethrowIfVmFailure()
        logcat(LogPriority.ERROR, e) { failedMessage() }
        Toasts.error(failedMessage(), e)
        onError(e)
    }
}

/**
 * How many user actions started with [launchAction] (so also [launchReporting]) have not ended.
 * Journeys wait for none, with the app's background work done, before they check that an action
 * changed nothing. It costs one atomic add when an action starts and one when it ends.
 */
internal val actionsRunning = AtomicInteger()

/** [launch] for a user action (a delete, a removal), counted in [actionsRunning] from the call until it ends. */
internal fun CoroutineScope.launchAction(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    actionsRunning.incrementAndGet()
    return launch(context, block = block).apply { invokeOnCompletion { actionsRunning.decrementAndGet() } }
}
