package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import java.io.File

/**
 * The JVM's own crash file keeps environment values, so it goes once its redacted copy is saved,
 * but only when it is Zoukan's: another Java program may have reused the pid of a killed run.
 */
class JvmCrashFileCleanupTest {
    @TempDir
    lateinit var root: File

    /** Stands in for the temp and working folders, so no file is written to the real ones. */
    @TempDir
    lateinit var tmp: File

    private val folders get() = listOf(tmp)

    private fun deadRun(dirs: ZoukanDirs, pid: Long) {
        dirs.logs.mkdirs()
        dirs.crashes.mkdirs()
        File(dirs.logs, ".running-$pid").writeText("")
        File(dirs.logs, ".running-$pid").setLastModified(System.currentTimeMillis() - 60_000)
    }

    private fun jvmCrashFile(dir: File, pid: Long, command: String = ZOUKAN_COMMAND) =
        File(dir, "hs_err_pid$pid.log").apply {
            writeText(
                "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n\n" +
                    "---------------  S U M M A R Y ------------\n\n" +
                    "Command Line: $command\n\n" +
                    "Environment Variables:\nGITHUB_TOKEN=ghp_SECRETENV7\n\n",
            )
        }

    @Test
    fun `Zoukan's crash file in the temp folder is copied and the original deleted`() {
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        val original = jvmCrashFile(tmp, PID)
        val copy = CrashReporter.checkPreviousRun(dirs, folders)!!
        copy.name shouldBe "jvm-crash-hs_err_pid$PID.log"
        copy.readText() shouldNotContain "ghp_SECRETENV7"
        original.exists() shouldBe false
    }

    @Test
    fun `a java_command line naming the main class also counts`() {
        val file = File(tmp, "hs_err_pid$PID.log").apply {
            writeText("VM Arguments:\njvm_args: -Xmx2g\njava_command: zoukan.desktop.MainKt\n")
        }
        CrashReporter.isZoukanJvmCrash(file) shouldBe true
    }

    @Test
    fun `another program's crash file with the same pid is neither copied nor deleted`() {
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        val original = jvmCrashFile(tmp, PID, command = "-Xmx512m org.example.OtherApp zoukan.desktop.MainKtx")
        val note = CrashReporter.checkPreviousRun(dirs, folders)!!
        note.name shouldStartWith "unclean-exit-"
        File(dirs.crashes, "jvm-crash-hs_err_pid$PID.log").exists() shouldBe false
        original.exists() shouldBe true
        original.readText() shouldContain "ghp_SECRETENV7"

        // Asked directly, the cleanup leaves it too
        CrashReporter.deleteJvmCrashOriginal(original, folders)
        original.exists() shouldBe true
    }

    @Test
    fun `a crash file without a command line, or one that cannot be read, stays`() {
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        val garbage = File(tmp, "hs_err_pid$PID.log").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        CrashReporter.checkPreviousRun(dirs, folders)!!.name shouldStartWith "unclean-exit-"
        garbage.exists() shouldBe true

        // A folder named like a crash file cannot be read as one
        val unreadable = File(tmp, "hs_err_pid${PID + 1}.log").apply { mkdirs() }
        CrashReporter.isZoukanJvmCrash(unreadable) shouldBe false
        CrashReporter.isZoukanJvmCrash(File(tmp, "hs_err_pid${PID + 2}.log")) shouldBe false
    }

    @Test
    fun `the original is kept when the copy fails`() {
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        // A folder where the copy should go makes writing the copy fail
        File(dirs.crashes, "jvm-crash-hs_err_pid$PID.log").mkdirs()
        val original = jvmCrashFile(tmp, PID)
        val note = CrashReporter.checkPreviousRun(dirs, folders)!!
        note.name shouldStartWith "unclean-exit-"
        original.exists() shouldBe true
        original.readText() shouldContain "ghp_SECRETENV7"
    }

    @Test
    fun `only a JVM crash file directly in the temp or working folder is deleted`() {
        // In another folder (the data folder)
        val elsewhere = jvmCrashFile(root, PID)
        CrashReporter.deleteJvmCrashOriginal(elsewhere, folders)
        elsewhere.exists() shouldBe true

        // In the temp folder, but not named like a JVM crash file
        val other = File(tmp, "hs_err_pidnot-a-pid-$PID.log").apply { writeText("Command Line: $ZOUKAN_COMMAND\n") }
        CrashReporter.deleteJvmCrashOriginal(other, folders)
        other.exists() shouldBe true

        // A missing file does not throw
        CrashReporter.deleteJvmCrashOriginal(File(tmp, "hs_err_pid${PID + 4}.log"), folders)
    }

    private companion object {
        // Process ids no live process has (well above any OS's limit)
        const val PID = 2_000_000_101L
        const val ZOUKAN_COMMAND = "-Xmx2g --add-opens=java.desktop/sun.awt=ALL-UNNAMED zoukan.desktop.MainKt"
    }
}
