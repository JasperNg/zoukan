package zoukan.desktop.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import zoukan.desktop.ZoukanDirs
import java.io.File
import java.time.Duration

/** While incognito mode is on, nothing reaches the log file, and a report leaves out the log and the exception messages. */
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
            hidden shouldContain "java.lang.IllegalStateException"
            hidden shouldNotContain "broken on purpose"
            hidden shouldContain "The end of the log is left out because incognito mode is on"
            hidden shouldNotContain "End of the log"
            hidden shouldNotContain "Opened a title the user read"
        }
    }

    @Test
    fun `a report written while incognito is on has class names and frames but no messages`() {
        val dirs = ZoukanDirs(temp)
        dirs.logs.mkdirs()
        File(dirs.logs, "zoukan.log").writeText("Opened a title the user read\n")
        val e = nestedFailure()
        withDirs(dirs) {
            IncognitoLog.set(true)
            listOf(true, false).forEach { fatal ->
                val text = CrashReporter.report(Thread.currentThread(), e, fatal = fatal)
                text shouldContain "The exception messages are left out because incognito mode is on."
                text shouldContain "java.lang.IllegalStateException"
                text shouldContain "Caused by: java.io.IOException"
                text shouldContain "Suppressed: java.lang.IllegalArgumentException"
                text shouldContain "at zoukan.desktop.app.IncognitoLogTest.nestedFailure"
                text shouldNotContain "https://example.org/chapter/42"
                text shouldNotContain "Chapter 42 title"
                text shouldNotContain "a secret page"
                text shouldNotContain "toString of a message"
                text shouldNotContain "Opened a title the user read"
            }
        }
    }

    @Test
    fun `a report written while incognito is off keeps the messages, redacted`() {
        val e = nestedFailure(url = "https://example.org/chapter/42?token=abc123")
        val text = CrashReporter.report(Thread.currentThread(), e, fatal = false)
        text shouldContain "https://example.org/chapter/42?token=<redacted>"
        text shouldNotContain "abc123"
        text shouldContain "Chapter 42 title"
        text shouldContain "a secret page"
        text shouldNotContain "messages are left out"
    }

    @Test
    fun `a cyclic cause chain does not loop while incognito is on`() {
        val a = IllegalStateException("message a")
        val b = RuntimeException("message b", a)
        a.initCause(b)
        a.addSuppressed(a)
        IncognitoLog.set(true)
        val text: String = assertTimeoutPreemptively<String>(Duration.ofSeconds(10)) {
            CrashReporter.report(Thread.currentThread(), a, fatal = true)
        }
        text shouldContain "Caused by: java.lang.RuntimeException"
        text shouldContain "[CIRCULAR REFERENCE: java.lang.IllegalStateException]"
        text shouldNotContain "message a"
        text shouldNotContain "message b"
    }

    @Test
    fun `the crash dialog leaves out the exception message while incognito is on and keeps the report path`() {
        val e = IllegalStateException("Chapter 42 title at https://example.org/chapter/42")
        val report = File(temp, "crash-20260101-000000-000.txt")

        val shown = CrashReporter.crashDialogMessage(e, report, incognito = false)
        shown shouldContain "IllegalStateException: Chapter 42 title"
        shown shouldContain report.absolutePath

        val hidden = CrashReporter.crashDialogMessage(e, report, incognito = true)
        hidden shouldContain "IllegalStateException"
        hidden shouldContain "left out because incognito mode is on"
        hidden shouldNotContain "Chapter 42 title"
        hidden shouldNotContain "example.org"
        hidden shouldContain report.absolutePath
    }

    /** An exception with a cause and a suppressed exception, each with a message; one also overrides toString. */
    private fun nestedFailure(url: String = "https://example.org/chapter/42"): Throwable {
        val cause = java.io.IOException("HTTP 404 $url")
        val e = IllegalStateException("Chapter 42 title", cause)
        e.addSuppressed(
            object : IllegalArgumentException("a secret page") {
                override fun toString() = "toString of a message"
            }.let { IllegalArgumentException("a secret page", it) },
        )
        return e
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
