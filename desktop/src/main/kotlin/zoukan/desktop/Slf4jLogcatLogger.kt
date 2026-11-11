package zoukan.desktop

import logcat.LogPriority
import logcat.LogcatLogger
import org.slf4j.LoggerFactory

/** Sends Komikku's logcat calls to SLF4J (logback), under `zoukan.<tag>`. */
class Slf4jLogcatLogger(private val minPriority: LogPriority) : LogcatLogger {
    override fun isLoggable(priority: LogPriority): Boolean = priority >= minPriority

    override fun log(priority: LogPriority, tag: String, message: String) {
        val logger = LoggerFactory.getLogger("zoukan.$tag")
        when (priority) {
            LogPriority.VERBOSE -> logger.trace(message)
            LogPriority.DEBUG -> logger.debug(message)
            LogPriority.INFO -> logger.info(message)
            LogPriority.WARN -> logger.warn(message)
            LogPriority.ERROR, LogPriority.ASSERT -> logger.error(message)
        }
    }
}
