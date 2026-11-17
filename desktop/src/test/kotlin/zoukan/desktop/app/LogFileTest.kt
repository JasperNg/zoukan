package zoukan.desktop.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File

/** What the app logs reaches `<data>/logs/zoukan.log`, which the packaged app has instead of a console. */
class LogFileTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `a warning the app logs is written to the log file`() {
        val install = CrashReporter::class.java.getDeclaredMethod("installLogFile", File::class.java).apply { isAccessible = true }
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        try {
            install.invoke(CrashReporter, temp)
            LoggerFactory.getLogger("zoukan.LogFileTest").warn("probe line for the log file")
            File(temp, "zoukan.log").readText() shouldContain "probe line for the log file"
        } finally {
            root.getAppender("FILE")?.let {
                it.stop()
                root.detachAppender(it)
            }
        }
    }
}
