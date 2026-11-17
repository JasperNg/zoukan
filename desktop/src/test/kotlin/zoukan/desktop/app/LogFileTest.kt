package zoukan.desktop.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File

/** What the app logs reaches `<data>/logs/zoukan.log`, which the packaged app has instead of a console. */
class LogFileTest {

    @TempDir
    lateinit var temp: File

    /** Installs the log file in [temp], runs [block], and returns what the log file holds. */
    private fun logged(block: (org.slf4j.Logger) -> Unit): String {
        val install = CrashReporter::class.java.getDeclaredMethod("installLogFile", File::class.java).apply { isAccessible = true }
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        try {
            install.invoke(CrashReporter, temp)
            block(LoggerFactory.getLogger("zoukan.LogFileTest"))
            return File(temp, "zoukan.log").readText()
        } finally {
            root.getAppender("FILE")?.let {
                it.stop()
                root.detachAppender(it)
            }
        }
    }

    @Test
    fun `a warning the app logs is written to the log file`() {
        logged { it.warn("probe line for the log file") } shouldContain "probe line for the log file"
    }

    @Test
    fun `a sign-in code or token in a logged message is redacted in the log file`() {
        val log = logged {
            it.warn("Opening https://example.org/callback?code=SIGNINCODE1&state=keep&token=TOKENVALUE2")
            it.warn("Header Authorization: Bearer BEARERVALUE3")
        }
        log shouldContain "https://example.org/callback?code=<redacted>&state=keep&token=<redacted>"
        log shouldContain "Bearer <redacted>"
        log shouldNotContain "SIGNINCODE1"
        log shouldNotContain "TOKENVALUE2"
        log shouldNotContain "BEARERVALUE3"
    }

    @Test
    fun `a token in an exception message and its cause is redacted in the log file`() {
        val cause = IllegalStateException("cause for https://example.org/a?access_token=CAUSETOKEN4")
        val log = logged {
            it.error("Request failed", RuntimeException("failed https://example.org/b?code=EXCODE5", cause))
        }
        log shouldContain "Request failed"
        log shouldContain "java.lang.RuntimeException: failed https://example.org/b?code=<redacted>"
        log shouldContain "access_token=<redacted>"
        log shouldNotContain "EXCODE5"
        log shouldNotContain "CAUSETOKEN4"
    }

    @Test
    fun `a line that cannot be laid out becomes a note instead of throwing or leaking`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val layout = CrashReporter.RedactingPatternLayout().apply {
            this.context = context
            pattern = "%msg%n"
            start()
        }
        val event = mockk<ILoggingEvent>(relaxed = true) {
            every { formattedMessage } throws IllegalStateException("https://example.org/?code=LEAK6")
            every { message } throws IllegalStateException("https://example.org/?code=LEAK6")
        }
        val line = layout.doLayout(event)
        line shouldNotContain "LEAK6"
        line shouldContain "could not be written"
    }
}
