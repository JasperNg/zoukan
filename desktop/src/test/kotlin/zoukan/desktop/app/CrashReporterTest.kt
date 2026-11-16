package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import java.io.File

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
        File(root, "hs_err_pid$other.log").writeText("# A fatal error has been detected by the Java Runtime Environment")
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
