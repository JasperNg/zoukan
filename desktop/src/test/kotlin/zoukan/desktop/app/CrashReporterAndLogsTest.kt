package zoukan.desktop.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import zoukan.desktop.ZoukanDirs
import java.io.File
import java.time.Duration

class CrashReporterTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `a report has the versions, the thread and the stack trace`() {
        val report = CrashReporter.report(Thread.currentThread(), IllegalStateException("broken on purpose"), fatal = true)
        report shouldStartWith "Zoukan crash report"
        report shouldContain "Zoukan: ${AppVersion.name}"
        report shouldContain "Thread: ${Thread.currentThread().name}"
        report shouldContain "java.lang.IllegalStateException: broken on purpose"
        report shouldContain "CrashReporterTest"
    }

    @Test
    fun `a clean previous run leaves nothing`() {
        val dirs = ZoukanDirs(root)
        CrashReporter.checkPreviousRun(dirs) shouldBe null
    }

    @Test
    fun `an unclean previous run is noted once`() {
        val dirs = ZoukanDirs(root)
        dirs.logs.mkdirs()
        File(dirs.logs, ".running-$DEAD_PID").writeText("$DEAD_PID")
        File(dirs.logs, "zoukan.log").writeText("first line\nlast thing logged\n")
        val report = CrashReporter.checkPreviousRun(dirs)
        report shouldNotBe null
        report!!.name shouldStartWith "unclean-exit-"
        report.readText() shouldContain "last thing logged"
        CrashReporter.checkPreviousRun(dirs) shouldBe null
    }

    @Test
    fun `a report does not contain a token that was logged`() {
        val dirs = ZoukanDirs(root)
        dirs.logs.mkdirs()
        File(dirs.logs, ".running-$DEAD_PID").writeText("$DEAD_PID")
        File(dirs.logs, "zoukan.log").writeText(
            "first line\n" +
                "ERROR Not a tracker sign-in address: komikku://anilist-auth#access_token=SECRETTOKEN1&token_type=Bearer\n" +
                "WARN redirect komikku://myanimelist-auth?code=SECRETCODE2\n" +
                "last thing logged\n",
        )
        val text = CrashReporter.checkPreviousRun(dirs)!!.readText()
        text shouldContain "last thing logged"
        text shouldNotContain "SECRETTOKEN1"
        text shouldNotContain "SECRETCODE2"
    }

    @Test
    fun `a report does not contain a token from the exception message`() {
        val e = IllegalStateException("Sign-in failed for komikku://myanimelist-auth?code=SECRETCODE4 with Bearer SECRETBEARER5")
        val text = CrashReporter.report(Thread.currentThread(), e, fatal = true)
        text shouldContain "IllegalStateException"
        text shouldNotContain "SECRETCODE4"
        text shouldNotContain "SECRETBEARER5"
    }

    @Test
    fun `a copied JVM crash file has no environment values`() {
        val dirs = ZoukanDirs(root)
        dirs.logs.mkdirs()
        val pid = DEAD_PID + 2
        File(dirs.logs, ".running-$pid").writeText("")
        File(dirs.logs, ".running-$pid").setLastModified(System.currentTimeMillis() - 60_000)
        File(root, "hs_err_pid$pid.log").writeText(
            "# A fatal error has been detected by the Java Runtime Environment\n\n" +
                "Command Line: -Xmx2g zoukan.desktop.MainKt\n\n" +
                "Environment Variables:\nGITHUB_TOKEN=ghp_SECRETENV3\nPATH=/usr/bin\n\n" +
                "Signal Handlers:\n",
        )
        val copy = CrashReporter.checkPreviousRun(dirs)!!
        copy.readText() shouldContain "fatal error"
        copy.readText() shouldNotContain "ghp_SECRETENV3"
        File(root, "hs_err_pid$pid.log").readText() shouldContain "ghp_SECRETENV3"
    }

    @Test
    fun `an unclean run with a fatal report points at it, and a JVM crash file is copied`() {
        val dirs = ZoukanDirs(root)
        dirs.logs.mkdirs()
        dirs.crashes.mkdirs()
        File(dirs.logs, ".running-$DEAD_PID").apply { setLastModified(System.currentTimeMillis() - 60_000) }.writeText("")
        File(dirs.logs, ".running-$DEAD_PID").setLastModified(System.currentTimeMillis() - 60_000)
        val fatal = File(dirs.crashes, "crash-20261110-120000-000.txt").apply { writeText("Zoukan crash report") }
        CrashReporter.checkPreviousRun(dirs) shouldBe fatal

        val other = DEAD_PID + 1
        File(dirs.logs, ".running-$other").writeText("")
        File(dirs.logs, ".running-$other").setLastModified(System.currentTimeMillis() - 60_000)
        File(root, "hs_err_pid$other.log").writeText(
            "# A fatal error has been detected by the Java Runtime Environment\nCommand Line: zoukan.desktop.MainKt\n",
        )
        val copy = CrashReporter.checkPreviousRun(dirs)!!
        copy.name shouldBe "jvm-crash-hs_err_pid$other.log"
        copy.readText() shouldContain "fatal error"
    }

    @Test
    fun `a marker of a process that still runs is not a crash and stays`() {
        val dirs = ZoukanDirs(root)
        dirs.logs.mkdirs()
        val pid = ProcessHandle.current().pid()
        val marker = File(dirs.logs, ".running-$pid").apply { writeText("$pid") }
        CrashReporter.checkPreviousRun(dirs) shouldBe null
        marker.exists() shouldBe true
        CrashReporter.isRunning(pid, since = System.currentTimeMillis()) shouldBe true
        CrashReporter.isRunning(DEAD_PID, since = System.currentTimeMillis()) shouldBe false
    }

    @Test
    fun `a live process that started after the marker is a reused pid`() {
        val pid = ProcessHandle.current().pid()
        // A marker written long before this JVM started belongs to an earlier process
        CrashReporter.isRunning(pid, since = 0L) shouldBe false
    }

    private companion object {
        /** No process has this id (pids stay far below it on Windows, macOS and Linux) */
        const val DEAD_PID = 2_000_000_001L
    }
}

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
