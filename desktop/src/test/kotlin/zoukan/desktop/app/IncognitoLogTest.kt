package zoukan.desktop.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import zoukan.desktop.ZoukanDirs
import java.io.File

/** While incognito mode is on, nothing reaches the log file, and a report leaves out the log. */
class IncognitoLogTest {

    @TempDir
    lateinit var temp: File

    @AfterEach
    fun turnOff() {
        IncognitoLog.set(false)
    }

    @Test
    fun `nothing is written to the log file while incognito is on, and logging resumes after`() {
        val install = CrashReporter::class.java.getDeclaredMethod("installLogFile", File::class.java).apply { isAccessible = true }
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val log = LoggerFactory.getLogger("zoukan.IncognitoLogTest")
        try {
            install.invoke(CrashReporter, temp)
            log.warn("before incognito")
            IncognitoLog.set(true)
            log.warn("during incognito: a title")
            log.error("during incognito with a stack trace", IllegalStateException("a chapter name"))
            log.atWarn().log("during incognito through the fluent API")
            IncognitoLog.set(false)
            log.warn("after incognito")
        } finally {
            root.getAppender("FILE")?.let {
                it.stop()
                root.detachAppender(it)
            }
        }
        val text = File(temp, "zoukan.log").readText()
        text shouldContain "before incognito"
        text shouldContain "after incognito"
        text shouldNotContain "during incognito"
        text shouldNotContain "a chapter name"
    }

    @Test
    fun `a report written while incognito is on has the stack trace but not the end of the log`() {
        val dirs = ZoukanDirs(temp)
        dirs.logs.mkdirs()
        File(dirs.logs, "zoukan.log").writeText("Opened a title the user read\n")
        withDirs(dirs) {
            val shown = CrashReporter.report(Thread.currentThread(), IllegalStateException("broken on purpose"), fatal = true)
            shown shouldContain "End of the log"
            shown shouldContain "Opened a title the user read"

            IncognitoLog.set(true)
            val hidden = CrashReporter.report(Thread.currentThread(), IllegalStateException("broken on purpose"), fatal = true)
            hidden shouldContain "java.lang.IllegalStateException: broken on purpose"
            hidden shouldContain "left out because incognito mode is on"
            hidden shouldNotContain "End of the log"
            hidden shouldNotContain "Opened a title the user read"
        }
    }

    /** Points [CrashReporter] at [dirs] for [block] without installing it (no log file, handler or marker). */
    private fun withDirs(dirs: ZoukanDirs, block: () -> Unit) {
        val field = CrashReporter::class.java.getDeclaredField("dirs").apply { isAccessible = true }
        val before = field.get(CrashReporter)
        field.set(CrashReporter, dirs)
        try {
            block()
        } finally {
            field.set(CrashReporter, before)
        }
    }
}
