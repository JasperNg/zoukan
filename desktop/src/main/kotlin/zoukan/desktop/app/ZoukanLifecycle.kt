package zoukan.desktop.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.rethrowIfFatal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Single owner of Zoukan's process-lifetime coroutine scopes ([appScope], [serviceScope]),
 * component child scopes, and JVM shutdown hooks.
 *
 * All desktop shutdown callbacks register here instead of adding separate JVM shutdown hooks so
 * teardown runs in a single deterministic, reverse-registration (LIFO) order with exception
 * isolation, and tests can reset or exercise lifecycle shutdown without leaking JVM hooks.
 */
object ZoukanLifecycle : AutoCloseable {
    private val coordinator = LifecycleCoordinator(
        registerJvmHook = { hook -> Runtime.getRuntime().addShutdownHook(hook) },
    )

    val appScope: CoroutineScope
        get() = coordinator.appScope

    val serviceScope: CoroutineScope
        get() = coordinator.serviceScope

    val isShutDown: Boolean
        get() = coordinator.isShutDown

    /**
     * Creates a child scope under [appScope] with its own [SupervisorJob] so cancelling the child
     * does not cancel [appScope], while [shutdown] cancels [appScope] and its children together.
     * [AppShutdown] waits for active children of [appScope], so use [newServiceScope] for work
     * that runs for the whole session or is cancelled at quit.
     */
    fun newAppScope(
        name: String? = null,
        context: CoroutineContext = EmptyCoroutineContext,
    ): CoroutineScope = coordinator.newAppScope(name, context)

    /**
     * Creates a child scope under [serviceScope] with its own [SupervisorJob] so cancelling the
     * child does not cancel [serviceScope], while [shutdown] cancels [serviceScope] and every
     * child scope created from it.
     */
    fun newServiceScope(
        name: String? = null,
        context: CoroutineContext = EmptyCoroutineContext,
    ): CoroutineScope = coordinator.newServiceScope(name, context)

    /**
     * Registers a named callback to run during [shutdown] in reverse registration (LIFO) order.
     * Returns an [AutoCloseable] handle that unregisters the callback idempotently.
     */
    fun registerShutdownHook(name: String, block: () -> Unit): AutoCloseable =
        coordinator.registerShutdownHook(name, block)

    /**
     * Cancels [serviceScope], [appScope], and all child scopes, then runs registered shutdown hooks
     * in reverse registration order with exception isolation. Safe to call more than once.
     */
    fun shutdown() = coordinator.shutdown()

    override fun close() = shutdown()

    /** Resets scopes and registered hooks to a fresh active state without adding a duplicate JVM hook. */
    internal fun reset() = coordinator.reset()

    internal fun activeHookCount(): Int = coordinator.activeHookCount()

    internal fun hasRegisteredJvmHook(): Boolean = coordinator.hasRegisteredJvmHook()
}

internal class LifecycleCoordinator(
    private val registerJvmHook: (Thread) -> Unit,
) : AutoCloseable {
    private class HookEntry(
        val name: String,
        val block: () -> Unit,
    ) {
        val cancelled = AtomicBoolean(false)
    }

    private val lock = Any()
    private val jvmHookLock = Any()
    private val jvmHookInstalled = AtomicBoolean(false)

    @Volatile
    private var currentAppScope: CoroutineScope = createRootScope("app-scope")

    @Volatile
    private var currentServiceScope: CoroutineScope = createRootScope("service-scope")

    @Volatile
    private var shutDown = false

    private val hooks = mutableListOf<HookEntry>()

    val appScope: CoroutineScope
        get() = currentAppScope

    val serviceScope: CoroutineScope
        get() = currentServiceScope

    val isShutDown: Boolean
        get() = shutDown

    fun newAppScope(
        name: String? = null,
        context: CoroutineContext = EmptyCoroutineContext,
    ): CoroutineScope = childScope(currentAppScope, name, context)

    fun newServiceScope(
        name: String? = null,
        context: CoroutineContext = EmptyCoroutineContext,
    ): CoroutineScope = childScope(currentServiceScope, name, context)

    fun registerShutdownHook(name: String, block: () -> Unit): AutoCloseable {
        if (shutDown) return AutoCloseable { }
        ensureJvmHookRegistered()
        val entry = HookEntry(name, block)
        synchronized(lock) {
            if (shutDown) return AutoCloseable { }
            hooks += entry
        }
        return AutoCloseable {
            if (entry.cancelled.compareAndSet(false, true)) {
                synchronized(lock) {
                    hooks.remove(entry)
                }
            }
        }
    }

    fun shutdown() {
        val appToCancel: CoroutineScope
        val serviceToCancel: CoroutineScope
        val snapshot: List<HookEntry>
        synchronized(lock) {
            if (shutDown) return
            shutDown = true
            appToCancel = currentAppScope
            serviceToCancel = currentServiceScope
            snapshot = hooks.toList()
            hooks.clear()
        }

        val reason = CancellationException("ZoukanLifecycle shutdown")
        serviceToCancel.cancel(reason)
        appToCancel.cancel(reason)

        // Reverse registration (LIFO) order: later-acquired resources flush before earlier setup tears down.
        for (entry in snapshot.asReversed()) {
            if (entry.cancelled.get()) continue
            try {
                entry.block()
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                runCatching {
                    logcat(LogPriority.WARN, e) { "Shutdown hook '${entry.name}' failed" }
                }
            }
        }
    }

    override fun close() = shutdown()

    fun reset() {
        val oldApp: CoroutineScope
        val oldService: CoroutineScope
        synchronized(lock) {
            oldApp = currentAppScope
            oldService = currentServiceScope
            hooks.forEach { it.cancelled.set(true) }
            hooks.clear()
            currentAppScope = createRootScope("app-scope")
            currentServiceScope = createRootScope("service-scope")
            shutDown = false
        }
        val reason = CancellationException("ZoukanLifecycle reset")
        oldService.cancel(reason)
        oldApp.cancel(reason)
    }

    fun activeHookCount(): Int = synchronized(lock) { hooks.size }

    fun hasRegisteredJvmHook(): Boolean = jvmHookInstalled.get()

    private fun ensureJvmHookRegistered() {
        if (jvmHookInstalled.get()) return
        synchronized(jvmHookLock) {
            if (jvmHookInstalled.get()) return
            val hook = Thread({ shutdown() }, "zoukan-lifecycle-shutdown")
            try {
                registerJvmHook(hook)
                jvmHookInstalled.set(true)
            } catch (e: IllegalStateException) {
                runCatching {
                    logcat(LogPriority.DEBUG, e) { "JVM shutdown already in progress; skipping hook registration" }
                }
            }
        }
    }

    private fun childScope(
        parent: CoroutineScope,
        name: String?,
        context: CoroutineContext,
    ): CoroutineScope {
        require(context[Job] == null) { "Child scope context must not provide its own Job" }
        val named = if (name != null) CoroutineName(name) else EmptyCoroutineContext
        val childJob = SupervisorJob(parent.coroutineContext[Job])
        return CoroutineScope(parent.coroutineContext + named + context + childJob)
    }

    private companion object {
        fun createRootScope(name: String): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(name) + backgroundFailureHandler())

        fun backgroundFailureHandler() = CoroutineExceptionHandler { context, e ->
            context.logcat(LogPriority.ERROR, e) { "Background work failed: ${context[CoroutineName]?.name ?: "unnamed"}" }
            runCatching { CrashReporter.write(Thread.currentThread(), e, fatal = false) }
        }
    }
}
