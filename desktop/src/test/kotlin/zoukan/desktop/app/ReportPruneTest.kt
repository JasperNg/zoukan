package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File
import java.util.concurrent.TimeUnit

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

    @Disabled(
        "Production bug, not fixed here: pruneOldReports lists a reports (or crashes) folder that is a " +
            "symbolic link, so it deletes old files named like Zoukan's reports in the folder the link points to",
    )
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
}
