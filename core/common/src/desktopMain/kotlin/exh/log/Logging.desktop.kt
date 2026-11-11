// ZOUKAN -->
package exh.log

import logcat.LogPriority
import logcat.asLog
import logcat.logcat

actual fun Any.xLogE(log: String, e: Throwable) {
    logcat(LogPriority.ERROR) { log + "\n" + e.asLog() }
}

// The rest of the Android app's xLog helpers, for the SY code the desktop app shares (trackers,
// E-Hentai), on logcat instead of XLog
fun Any.xLogE(log: String) = logcat(LogPriority.ERROR) { log }
fun Any.xLogW(log: String) = logcat(LogPriority.WARN) { log }
fun Any.xLogD(log: String) = logcat(LogPriority.DEBUG) { log }
fun Any.xLogI(log: String) = logcat(LogPriority.INFO) { log }
fun Any.xLogW(log: String, e: Throwable) = logcat(LogPriority.WARN) { log + "\n" + e.asLog() }
fun Any.xLogD(log: String, e: Throwable) = logcat(LogPriority.DEBUG) { log + "\n" + e.asLog() }
fun Any.xLogI(log: String, e: Throwable) = logcat(LogPriority.INFO) { log + "\n" + e.asLog() }
fun Any.xLogE(log: Any?) = logcat(LogPriority.ERROR) { log.toString() }
fun Any.xLogW(log: Any?) = logcat(LogPriority.WARN) { log.toString() }
fun Any.xLogD(log: Any?) = logcat(LogPriority.DEBUG) { log.toString() }
fun Any.xLogI(log: Any?) = logcat(LogPriority.INFO) { log.toString() }
fun Any.xLogE(format: String, vararg args: Any?) = logcat(LogPriority.ERROR) { formatSafely(format, args) }
fun Any.xLogW(format: String, vararg args: Any?) = logcat(LogPriority.WARN) { formatSafely(format, args) }
fun Any.xLogD(format: String, vararg args: Any?) = logcat(LogPriority.DEBUG) { formatSafely(format, args) }
fun Any.xLogI(format: String, vararg args: Any?) = logcat(LogPriority.INFO) { formatSafely(format, args) }

private fun formatSafely(format: String, args: Array<out Any?>): String =
    runCatching { String.format(format, *args) }.getOrElse { "$format ${args.joinToString()}" }
// ZOUKAN <--
