package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import java.io.File

/** The JVM's own crash file keeps environment values, so it goes once its redacted copy is saved. */
class JvmCrashFileCleanupTest {
    @TempDir
    lateinit var root: File

    private val tmp = File(System.getProperty("java.io.tmpdir"))

    private fun deadRun(dirs: ZoukanDirs, pid: Long) {
        dirs.logs.mkdirs()
        dirs.crashes.mkdirs()
        File(dirs.logs, ".running-$pid").writeText("")
        File(dirs.logs, ".running-$pid").setLastModified(System.currentTimeMillis() - 60_000)
    }

    private fun jvmCrashFile(dir: File, pid: Long) = File(dir, "hs_err_pid$pid.log").apply {
        writeText(
            "# A fatal error has been detected by the Java Runtime Environment\n\n" +
                "Environment Variables:\nGITHUB_TOKEN=ghp_SECRETENV7\n\n",
        )
    }

    @Test
    fun `the original in the temp folder is deleted after a successful copy`() {
        val dirs = ZoukanDirs(root)
        val pid = PID
        deadRun(dirs, pid)
        val original = jvmCrashFile(tmp, pid)
        try {
            val copy = CrashReporter.checkPreviousRun(dirs)!!
            copy.name shouldBe "jvm-crash-hs_err_pid$pid.log"
            copy.readText() shouldNotContain "ghp_SECRETENV7"
            original.exists() shouldBe false
        } finally {
            original.delete()
        }
    }

    @Test
    fun `the original is kept when the copy fails`() {
        val dirs = ZoukanDirs(root)
        val pid = PID + 1
        deadRun(dirs, pid)
        // A folder where the copy should go makes writing the copy fail
        File(dirs.crashes, "jvm-crash-hs_err_pid$pid.log").mkdirs()
        val original = jvmCrashFile(tmp, pid)
        try {
            val note = CrashReporter.checkPreviousRun(dirs)!!
            note.name shouldStartWith "unclean-exit-"
            original.exists() shouldBe true
            original.readText() shouldContain "ghp_SECRETENV7"
        } finally {
            original.delete()
        }
    }

    @Test
    fun `only a JVM crash file directly in the temp or working folder is deleted`() {
        // In another folder (the data folder, here a subfolder of the temp folder)
        val elsewhere = jvmCrashFile(root, PID + 2)
        CrashReporter.deleteJvmCrashOriginal(elsewhere)
        elsewhere.exists() shouldBe true

        // In the temp folder, but not named like a JVM crash file
        val other = File(tmp, "hs_err_pidnot-a-pid-${PID + 3}.log").apply { writeText("keep") }
        try {
            CrashReporter.deleteJvmCrashOriginal(other)
            other.exists() shouldBe true
        } finally {
            other.delete()
        }

        // A missing file does not throw
        CrashReporter.deleteJvmCrashOriginal(File(tmp, "hs_err_pid${PID + 4}.log"))
    }

    private companion object {
        // Process ids no live process has (well above any OS's limit)
        const val PID = 2_000_000_101L
    }
}
