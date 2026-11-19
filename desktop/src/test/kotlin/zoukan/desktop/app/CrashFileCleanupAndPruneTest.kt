package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File
import java.util.concurrent.TimeUnit

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
    fun `the main class with arguments or after a module is recognised`() {
        fun crashFile(name: String, command: String) = File(tmp, name).apply { writeText("Command Line: $command\n") }
        CrashReporter.isZoukanJvmCrash(crashFile("a.log", "-Xmx2g zoukan.desktop.MainKt --self-test extensions")) shouldBe true
        CrashReporter.isZoukanJvmCrash(crashFile("b.log", "-Xmx2g -m zoukan.desktop/zoukan.desktop.MainKt")) shouldBe true
        CrashReporter.isZoukanJvmCrash(crashFile("c.log", "--module zoukan.desktop/zoukan.desktop.MainKt --flag")) shouldBe true
        CrashReporter.isZoukanJvmCrash(crashFile("d.log", "org.example.zoukan.desktop.MainKt")) shouldBe false
        CrashReporter.isZoukanJvmCrash(crashFile("e.log", "zoukan.desktop.MainKtx")) shouldBe false
    }

    @Test
    fun `a dead run's crash file whose command line does not name the main class is collected by its pid`() {
        val commands = listOf(
            "<unknown>",
            "",
            "/Applications/Zoukan.app/Contents/MacOS/Zoukan",
            "C:\\Program Files\\Zoukan\\Zoukan.exe --flag",
        )
        commands.forEachIndexed { i, command ->
            val pid = PID + 10 + i
            val dirs = ZoukanDirs(File(root, "run$i"))
            deadRun(dirs, pid)
            val original = File(tmp, "hs_err_pid$pid.log").apply {
                writeText(
                    "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n" +
                        "#  SIGSEGV (0xb) at pc=0x000000010f4b0c40, pid=$pid, tid=8195\n#\n\n" +
                        "Command Line: $command\n\n" +
                        "Environment Variables:\nGITHUB_TOKEN=ghp_SECRETENV8\n\n",
                )
            }
            val copy = CrashReporter.checkPreviousRun(dirs, folders)!!
            copy.name shouldBe "jvm-crash-hs_err_pid$pid.log"
            copy.readText() shouldNotContain "ghp_SECRETENV8"
            original.exists() shouldBe false
        }
    }

    @Test
    fun `a crash file matching neither the main class nor a dead run is left alone`() {
        val banner = "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"

        // Another program that reused the dead run's pid
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        val other = File(tmp, "hs_err_pid$PID.log").apply {
            writeText(banner + "#  SIGSEGV (0xb) at pc=0x1, pid=$PID, tid=2\n\nCommand Line: -Xmx1g org.example.OtherApp /opt/zoukan\n")
        }
        CrashReporter.checkPreviousRun(dirs, folders)!!.name shouldStartWith "unclean-exit-"
        other.exists() shouldBe true
        CrashReporter.isZoukanJvmCrash(other, deadZoukanPid = PID) shouldBe false
        CrashReporter.deleteJvmCrashOriginal(other, folders, deadZoukanPid = PID)
        other.exists() shouldBe true

        // An unknown command line, but no dead run with that pid
        val unknown = File(tmp, "hs_err_pid${PID + 20}.log").apply { writeText(banner + "Command Line: <unknown>\n") }
        CrashReporter.isZoukanJvmCrash(unknown) shouldBe false
        CrashReporter.isZoukanJvmCrash(unknown, deadZoukanPid = PID) shouldBe false
        CrashReporter.deleteJvmCrashOriginal(unknown, folders, deadZoukanPid = PID)
        unknown.exists() shouldBe true

        // The file name gives the dead pid, but its header names another process
        val header = File(tmp, "hs_err_pid${PID + 21}.log").apply {
            writeText(banner + "#  SIGSEGV (0xb) at pc=0x1, pid=${PID + 99}, tid=2\n\nCommand Line: <unknown>\n")
        }
        CrashReporter.isZoukanJvmCrash(header, deadZoukanPid = PID + 21) shouldBe false

        // Not a JVM crash file at all
        val notJvm = File(tmp, "hs_err_pid${PID + 22}.log").apply { writeText("Command Line: <unknown>\n") }
        CrashReporter.isZoukanJvmCrash(notJvm, deadZoukanPid = PID + 22) shouldBe false
    }

    private fun launcherCrashFile(pid: Long, command: String?) = File(tmp, "hs_err_pid$pid.log").apply {
        writeText(
            "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n" +
                "#  SIGSEGV (0xb) at pc=0x1, pid=$pid, tid=2\n#\n\n" +
                (if (command == null) "" else "Command Line: $command\n\n") +
                "Environment Variables:\nGITHUB_TOKEN=ghp_SECRETENV9\n\n",
        )
    }

    @Test
    fun `the installed launcher is recognised bare or at its installed path`() {
        val commands = listOf(
            "Zoukan",
            "Zoukan.exe --flag",
            "/Applications/Zoukan.app/Contents/MacOS/Zoukan",
            "/Users/u/My Apps/Zoukan.app/Contents/MacOS/Zoukan --flag",
            "C:\\Program Files\\Zoukan\\Zoukan.exe",
            "C:\\Users\\u\\AppData\\Local\\Zoukan\\Zoukan.exe --self-test",
            "\\\\server\\share\\Zoukan\\Zoukan.exe",
            "/opt/zoukan/bin/Zoukan",
            "/home/u/Zoukan/bin/Zoukan arg",
        )
        commands.forEachIndexed { i, command ->
            val pid = PID + 30 + i
            CrashReporter.isZoukanJvmCrash(launcherCrashFile(pid, command), deadZoukanPid = pid) shouldBe true
        }
    }

    @Test
    fun `a command line that only mentions a Zoukan path, or starts with options, is another program's`() {
        val commands = listOf(
            "/opt/backup.jar /Users/u/Library/Application Support/Zoukan",
            "/opt/tool.jar --out /tmp/zoukan x",
            "C:\\Tools\\other.jar D:\\Backup\\Zoukan",
            "-cp /opt/zoukan Other",
            "-Xmx2g C:\\Program Files\\Zoukan\\Zoukan.exe --flag",
            "/opt/tool.jar /Applications/Zoukan.app/Contents/MacOS/Zoukan",
            "C:\\Tools\\other.exe D:\\Apps\\Zoukan\\Zoukan.exe",
            "/opt/zoukan",
            "/usr/bin/Zoukan",
            "ZoukanOther",
            "/Applications/Zoukan.app/Contents/MacOS/ZoukanX",
            "org.example.OtherApp",
        )
        commands.forEachIndexed { i, command ->
            val pid = PID + 50 + i
            val file = launcherCrashFile(pid, command)
            CrashReporter.isZoukanJvmCrash(file, deadZoukanPid = pid) shouldBe false
            CrashReporter.deleteJvmCrashOriginal(file, folders, deadZoukanPid = pid)
            file.exists() shouldBe true
        }
    }

    @Test
    fun `a dead run's crash file with no command line in the part read is left alone`() {
        val dirs = ZoukanDirs(root)
        deadRun(dirs, PID)
        val original = launcherCrashFile(PID, command = null)
        CrashReporter.isZoukanJvmCrash(original, deadZoukanPid = PID) shouldBe false
        CrashReporter.checkPreviousRun(dirs, folders)!!.name shouldStartWith "unclean-exit-"
        File(dirs.crashes, "jvm-crash-hs_err_pid$PID.log").exists() shouldBe false
        original.exists() shouldBe true
        original.readText() shouldContain "ghp_SECRETENV9"
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

    @Test
    fun `only Zoukan's crash file goes, another program's and the target of a link stay`() {
        FileSystemSandbox.create(root).use { sandbox ->
            val dirs = ZoukanDirs(sandbox.dataFolder)
            val temp = sandbox.otherProgramFolder
            deadRun(dirs, PID)
            jvmCrashFile(temp, PID)
            // A link named like a dead run's crash file, to a Zoukan crash file outside the temp folder
            val linkPid = PID + 70
            deadRun(dirs, linkPid)
            val linkedTo = jvmCrashFile(sandbox.userFolder, linkPid)
            sandbox.link(File(temp, "hs_err_pid$linkPid.log"), linkedTo)
            // Another program's crash file with a dead run's pid
            deadRun(dirs, FileSystemSandbox.OTHER_PID)
            sandbox.record()

            CrashReporter.checkPreviousRun(dirs, listOf(temp))

            // Outside the data folder only Zoukan's own crash file in the temp folder went (and
            // perhaps the link itself, never what it points to)
            sandbox.changesOutsideApp().filterNot { it == "deleted link tmp/hs_err_pid$linkPid.log" } shouldBe
                listOf("deleted file tmp/hs_err_pid$PID.log")
            linkedTo.readText() shouldContain "ghp_SECRETENV7"
            sandbox.otherProgramCrashFile.readText() shouldContain "org.example.OtherApp"
        }
    }

    private companion object {
        // Process ids no live process has (well above any OS's limit)
        const val PID = 2_000_000_101L
        const val ZOUKAN_COMMAND = "-Xmx2g --add-opens=java.desktop/sun.awt=ALL-UNNAMED zoukan.desktop.MainKt"
    }
}

/** Zoukan's reports older than 30 days are deleted; newer ones and anyone else's files are kept. */
class ReportPruneTest {

    @TempDir
    lateinit var root: File

    private val now = System.currentTimeMillis()

    private fun File.file(name: String, ageDays: Long): File = resolve(name).apply {
        parentFile.mkdirs()
        writeText(name)
        setLastModified(now - TimeUnit.DAYS.toMillis(ageDays))
    }

    @Test
    fun `old reports are deleted, newer ones and other files are kept`() {
        val dirs = ZoukanDirs(root, hidesFromSpotlight = false)
        val crashes = dirs.crashes
        val reports = dirs.reports
        val oldCrash = crashes.file("crash-20260101-120000-000.txt", 40)
        val oldError = crashes.file("error-20260101-120000-001.txt", 31)
        val oldUnclean = crashes.file("unclean-exit-20260101-120000-1234.txt", 45)
        val oldJvm = crashes.file("jvm-crash-hs_err_pid1234.log", 60)
        val newCrash = crashes.file("crash-20261110-120000-000.txt", 5)
        val newError = crashes.file("error-20261116-120000-000.txt", 29)
        val foreignCrash = crashes.file("notes.txt", 90)
        val foreignLike = crashes.file("crash-mine.txt", 90)
        val oldUpdateErrors = reports.file("library-update-errors.txt", 35)
        val oldHarness = reports.file("extensions-store-windows.md", 35)
        val newHarness = reports.file("extensions-wiring-store-macos.md", 2)
        val foreignReport = reports.file("my-report.md", 90)

        CrashReporter.pruneOldReports(dirs, now)

        listOf(oldCrash, oldError, oldUnclean, oldJvm, oldUpdateErrors, oldHarness).forEach { it.exists() shouldBe false }
        listOf(newCrash, newError, foreignCrash, foreignLike, newHarness, foreignReport).forEach { it.exists() shouldBe true }
    }

    @Test
    fun `the count limit keeps the newest 30 of each kind and leaves other files alone`() {
        val dir = File(root, "crashes")
        val errors = (0 until 35).map { dir.file("error-20261117-1200${"%02d".format(it)}-000.txt", 0).apply { setLastModified(now - it * 1000L) } }
        val crashes = (0 until 3).map { dir.file("crash-20261117-1300${"%02d".format(it)}-000.txt", 1) }
        val foreign = dir.file("readme.txt", 1)

        CrashReporter.prune(dir, now)

        errors.take(30).forEach { it.exists() shouldBe true }
        errors.drop(30).forEach { it.exists() shouldBe false }
        crashes.forEach { it.exists() shouldBe true }
        foreign.exists() shouldBe true
    }

    @Test
    fun `missing folders are not an error`() {
        CrashReporter.pruneOldReports(ZoukanDirs(File(root, "absent"), hidesFromSpotlight = false), now)
    }

    @Test
    fun `pruning changes nothing outside the data folder`() {
        FileSystemSandbox.create(root).use { sandbox ->
            val dirs = ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = false)
            val old = dirs.crashes.file("crash-20260101-120000-000.txt", 40)
            dirs.reports.file("library-update-errors.txt", 35)
            // Files named like old reports in the user's folder and beside another program's crash file
            sandbox.userFolder.file("library-update-errors.txt", 35)
            sandbox.otherProgramFolder.file("crash-20260101-120000-000.txt", 40)
            sandbox.record()

            CrashReporter.pruneOldReports(dirs, now)

            old.exists() shouldBe false
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `pruning never deletes through a reports folder that is a link to the user's files`() {
        FileSystemSandbox.create(root).use { sandbox ->
            val dirs = ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = false)
            // The user's folder holds a file named like an old report; the reports folder links to it
            sandbox.userFolder.file("library-update-errors.txt", 35)
            assumeTrue(sandbox.link(dirs.reports, sandbox.userFolder) != null, "symbolic links cannot be made here")
            sandbox.record()

            CrashReporter.pruneOldReports(dirs, now)

            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `pruning never deletes through a crashes folder that is a link to the user's files`() {
        FileSystemSandbox.create(root).use { sandbox ->
            val dirs = ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = false)
            // Old files named like crash reports, and more than the count limit of new ones
            sandbox.userFolder.file("crash-20260101-120000-000.txt", 40)
            (0 until 35).forEach { sandbox.userFolder.file("error-20261117-1200${"%02d".format(it)}-000.txt", 0) }
            assumeTrue(sandbox.link(dirs.crashes, sandbox.userFolder) != null, "symbolic links cannot be made here")
            sandbox.record()

            CrashReporter.pruneOldReports(dirs, now)
            CrashReporter.prune(dirs.crashes, now)

            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `a link inside the reports folder is not followed or deleted`() {
        FileSystemSandbox.create(root).use { sandbox ->
            val dirs = ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = false)
            val target = sandbox.userFolder.file("my-errors.txt", 35)
            dirs.reports.mkdirs()
            val link = sandbox.link(File(dirs.reports, "library-update-errors.txt"), target)
            assumeTrue(link != null, "symbolic links cannot be made here")
            sandbox.record()

            CrashReporter.pruneOldReports(dirs, now)

            target.exists() shouldBe true
            sandbox.assertNothingOutsideAppChanged()
        }
    }
}
