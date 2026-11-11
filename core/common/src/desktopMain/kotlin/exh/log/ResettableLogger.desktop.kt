// ZOUKAN -->
package exh.log

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** The desktop's stand-in for XLog's Logger: the calls the shared app code makes, to logcat. */
class Logger(private val tag: String) {
    fun d(message: String) = logcat(LogPriority.DEBUG, tag = tag) { message }
    fun i(message: String) = logcat(LogPriority.INFO, tag = tag) { message }
    fun w(message: String) = logcat(LogPriority.WARN, tag = tag) { message }
    fun w(message: String, e: Throwable) = logcat(LogPriority.WARN, e, tag = tag) { message }
    fun e(message: String) = logcat(LogPriority.ERROR, tag = tag) { message }
    fun e(message: String, e: Throwable) = logcat(LogPriority.ERROR, e, tag = tag) { message }
}

class ResettableLogger(private val resolver: () -> Logger?) {
    private var logger: Logger? = null

    @Synchronized
    operator fun invoke(): Logger? = logger ?: resolver().also { logger = it }

    @Synchronized
    fun reset() {
        logger = null
    }
}

fun Any.safeXLogTag(tag: String = (this::class.java.enclosingClass?.simpleName ?: this::class.java.simpleName)): Logger? = Logger(tag)

fun Any.safeXLogStackTag(tag: String = (this::class.java.enclosingClass?.simpleName ?: this::class.java.simpleName)): Logger? = Logger(tag)
// ZOUKAN <--
