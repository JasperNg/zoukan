package zoukan.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * How many user actions started with [launchAction] (so also `launchReporting`) have not ended.
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
