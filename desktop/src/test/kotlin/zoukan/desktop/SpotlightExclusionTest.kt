package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How [SpotlightExclusion] deals with a helper that crashes or hangs, and the helper's watchdog. */
class SpotlightExclusionTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()
    private var starts = 0

    @BeforeEach
    @AfterEach
    fun resetRun() = SpotlightExclusion.resetForTests()

    /** A helper command that runs [script] in /bin/sh, counting each start. Exit 128 + n stands for signal n (a crash). */
    private fun sh(script: String): (List<String>) -> List<String> = {
        starts++
        listOf("/bin/sh", "-c", script)
    }

    private fun dir() = File(temp, "downloads").apply { mkdirs() }

    private fun workDir() = File(temp, "work").apply { mkdirs() }

    @Test
    fun `a helper that crashes is not started again in the same run`() {
        assumeTrue(File("/bin/sh").canExecute())
        val spotlight = SpotlightExclusion(sh("echo 'native crash' >&2; exit 134"), enabled = true, workDir = workDir())

        spotlight.set(listOf(dir()), true, notes::add).shouldBeEmpty()
        starts shouldBe 1
        notes.single() shouldContain "exit code 134"
        notes.single() shouldContain "native crash"
        notes.single() shouldContain "will not be started again"

        // Later calls fail without starting a helper, and say nothing more
        spotlight.set(listOf(dir()), false, notes::add).shouldBeEmpty()
        spotlight.excluded(listOf(dir()), notes::add) shouldBe null
        starts shouldBe 1
        notes.size shouldBe 1
    }

    @Test
    fun `a helper that hangs is not started again in the same run`() {
        assumeTrue(File("/bin/sh").canExecute())
        val work = workDir()
        val spotlight = SpotlightExclusion(sh("echo report > hs_err_pid$$.log; sleep 30"), timeoutMillis = 500, enabled = true, workDir = work)

        spotlight.excluded(listOf(dir()), notes::add) shouldBe null
        notes.single() shouldContain "did not finish"
        notes.single() shouldContain "will not be started again"
        spotlight.set(listOf(dir()), true, notes::add).shouldBeEmpty()
        starts shouldBe 1
        // The crash report the helper wrote before it hung is gone too
        work.list().orEmpty().filter { it.startsWith("hs_err_pid") }.shouldBeEmpty()
    }

    @Test
    fun `a helper that succeeds can run again`() {
        assumeTrue(File("/bin/sh").canExecute())
        val spotlight = SpotlightExclusion(sh("echo '0 ok'"), enabled = true, workDir = workDir())
        spotlight.set(listOf(dir()), true, notes::add) shouldBe setOf(dir())
        spotlight.excluded(listOf(dir()), notes::add) shouldBe setOf(dir())
        starts shouldBe 2
        notes.shouldBeEmpty()
    }

    @Test
    fun `the stop holds for every instance that shares it`() {
        assumeTrue(File("/bin/sh").canExecute())
        val stopped = AtomicBoolean(false)
        SpotlightExclusion(sh("exit 139"), enabled = true, workDir = workDir(), stopped = stopped)
            .set(listOf(dir()), true, notes::add).shouldBeEmpty()
        stopped.get() shouldBe true
        SpotlightExclusion(sh("echo '0 ok'"), enabled = true, workDir = workDir(), stopped = stopped)
            .set(listOf(dir()), true, notes::add).shouldBeEmpty()
        starts shouldBe 1
    }

    @Test
    fun `the real helper is stopped for the rest of the run, until reset`() {
        // A timeout too short for any JVM to start: the real helper is killed before it does anything
        val first = SpotlightExclusion(timeoutMillis = 1, enabled = true, workDir = workDir())
        assumeTrue(SpotlightExclusion.helperCommand(listOf(SpotlightExclusionHelper.CHECK)) != null)
        first.excluded(listOf(dir()), notes::add) shouldBe null
        SpotlightExclusion.stoppedForThisRun shouldBe true
        notes.single() shouldContain "will not be started again"

        // A new instance (as each storage folder change makes) does not start it either
        SpotlightExclusion(enabled = true, workDir = workDir()).excluded(listOf(dir()), notes::add) shouldBe null
        notes.size shouldBe 1

        SpotlightExclusion.resetForTests()
        SpotlightExclusion.stoppedForThisRun shouldBe false
    }

    @Test
    fun `a crash report left by the helper is deleted, and nothing else`() {
        assumeTrue(File("/bin/sh").canExecute())
        val work = workDir()
        val other = File(work, "hs_err_pid1.log").apply { writeText("another process's report") }
        val spotlight = SpotlightExclusion(
            sh("echo report > hs_err_pid$$.log; echo replay > replay_pid$$.log; exit 134"),
            enabled = true,
            workDir = work,
        )
        spotlight.set(listOf(dir()), true, notes::add).shouldBeEmpty()
        notes.single() shouldContain "exit code 134"
        work.list()!!.toList() shouldBe listOf(other.name)
        other.readText() shouldBe "another process's report"
    }

    @Test
    fun `a link in place of the crash report is not followed`() {
        assumeTrue(File("/bin/sh").canExecute())
        val work = workDir()
        val victim = File(temp, "victim.txt").apply { writeText("keep") }
        val spotlight = SpotlightExclusion(sh("ln -s '${victim.path}' hs_err_pid$$.log; exit 1"), enabled = true, workDir = work)
        spotlight.set(listOf(dir()), true, notes::add).shouldBeEmpty()
        notes.single() shouldContain "exit code 1"
        victim.readText() shouldBe "keep"
        // The link is not a crash report the helper's JVM wrote, so it is left alone
        work.listFiles()!!.single().let { Files.isSymbolicLink(it.toPath()) } shouldBe true
    }

    @Test
    fun `the watchdog ends a run that hangs with its own exit code`() {
        val codes = ArrayBlockingQueue<Int>(1)
        val watchdog = SpotlightExclusionHelper.startWatchdog(50) { codes.add(it) }
        watchdog.isDaemon shouldBe true
        codes.poll(10, TimeUnit.SECONDS) shouldBe SpotlightExclusionHelper.WATCHDOG
    }

    @Test
    fun `the watchdog fires before the parent's timeout`() {
        (SpotlightExclusionHelper.WATCHDOG_MILLIS < SpotlightExclusion.TIMEOUT_MILLIS) shouldBe true
    }

    @Test
    fun `the helper's JVM writes no crash files`() {
        val argv = SpotlightExclusion.helperCommand(listOf(SpotlightExclusionHelper.CHECK, "/"))
        assumeTrue(argv != null)
        argv!!.shouldContain("-XX:-CreateCoredumpOnCrash")
        argv.shouldContain("-XX:ErrorFile=/dev/null")
        argv.shouldContain("-XX:-UsePerfData")
    }

    @Test
    fun `the helper's JVM accepts its options`() {
        val argv = SpotlightExclusion.helperCommand(listOf("delete", "/"))
        assumeTrue(argv != null)
        val process = ProcessBuilder(argv!!).directory(workDir()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor() shouldBe SpotlightExclusionHelper.USAGE
        output shouldContain "Usage"
        workDir().list().orEmpty().toList().shouldBeEmpty()
    }
}
