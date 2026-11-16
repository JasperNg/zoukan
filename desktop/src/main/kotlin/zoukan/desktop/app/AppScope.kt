package zoukan.desktop.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-lifetime scope for work that outlives a screen (extension loading, library
 * refreshes, sync, library adds). It is never cancelled before the app quits.
 */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
