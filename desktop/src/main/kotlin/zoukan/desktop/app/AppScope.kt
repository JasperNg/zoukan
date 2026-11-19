package zoukan.desktop.app

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope

/**
 * The process-lifetime scope for work that outlives a screen (extension loading, library
 * refreshes, sync, library adds). It is never cancelled before the app quits. A failure that
 * nothing catches is logged with the work's [CoroutineName] and still written as a report, as the
 * default handler does.
 */
val AppScope: CoroutineScope
    get() = ZoukanLifecycle.appScope

/**
 * Work that runs for as long as the app does (the notification log, the daily update check). It
 * has a scope of its own, so quitting, which waits for [AppScope]'s work to finish, does not wait
 * for it.
 */
val ServiceScope: CoroutineScope
    get() = ZoukanLifecycle.serviceScope
