package zoukan.desktop.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.app.Toasts
import zoukan.desktop.app.launchAction
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
