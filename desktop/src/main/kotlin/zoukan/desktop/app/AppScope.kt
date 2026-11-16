package zoukan.desktop.app

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The process-lifetime scope for work that outlives a screen (extension loading, library
 * refreshes, sync, library adds). It is never cancelled before the app quits. A failure that
 * nothing catches is logged with the work's [CoroutineName] and still written as a report, as the
 * default handler does.
 */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + backgroundFailureHandler())

/**
 * Work that runs for as long as the app does (the notification log, the daily update check). It
 * has a scope of its own, so quitting, which waits for [AppScope]'s work to finish, does not wait
 * for it.
 */
val ServiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + backgroundFailureHandler())

private fun backgroundFailureHandler() = CoroutineExceptionHandler { context, e ->
    context.logcat(LogPriority.ERROR, e) { "Background work failed: ${context[CoroutineName]?.name ?: "unnamed"}" }
    runCatching { CrashReporter.write(Thread.currentThread(), e, fatal = false) }
}
