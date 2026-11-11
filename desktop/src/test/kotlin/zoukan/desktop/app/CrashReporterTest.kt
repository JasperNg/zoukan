package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
