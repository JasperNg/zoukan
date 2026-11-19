package zoukan.desktop

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.harness.ApiLinkage
import zoukan.desktop.harness.CompatibilityHarness
import zoukan.desktop.harness.ExtensionWiringCheck
import zoukan.desktop.testing.TestHome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/** A bad option value is a usage error (exit 2) and a failing command returns 1 instead of throwing out of main. */
class MainArgumentsTest {

    @TempDir
    lateinit var temp: File

    private fun runWith(runner: (Array<String>) -> Int, vararg args: String): Pair<Int, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            val code = runner(arrayOf(*args))
            return code to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    private fun run(vararg args: String): Pair<Int, String> = runWith(::runCommand, *args)

    private fun runTools(vararg args: String): Pair<Int, String> = runWith(::runToolsCommand, *args)

    @Test
    fun `selftest report without a file prints usage and exits 2`() {
        val (code, err) = run("selftest", "--report")
        code shouldBe 2
        err shouldContain "Usage:"
    }

    @Test
    fun `library update download without a number prints usage and exits 2`() {
        val (code, err) = run("library", "update", "--download")
        code shouldBe 2
        err shouldContain "Usage:"
    }

    @Test
    fun `library update download with a word prints usage and exits 2`() {
        val (code, err) = run("library", "update", "--download", "x")
        code shouldBe 2
        err shouldContain "Usage:"
    }

    @Test
    fun `harness store without an address prints usage and exits 2`() {
        val (code, err) = runTools("harness", "--store")
        code shouldBe 2
        err shouldContain "--store needs a value"
        err shouldContain "Usage:"
    }

    @Test
    fun `developer tool commands are rejected by Main and handled by ToolsMain`() {
        val (harnessCode, harnessErr) = run("harness")
        harnessCode shouldBe 2
        harnessErr shouldContain "Unknown command harness"

        for (sub in listOf("check", "compare", "synthetic")) {
            val (code, err) = run("backup", sub)
            code shouldBe 2
            err shouldContain "Usage: zoukan backup create|restore"
        }
    }

    @Test
    fun `a command that throws returns 1 so the shutdown path runs`() {
        val (mainCode, _) = run("convert", File(temp, "missing.apk").path, File(temp, "out.jar").path)
        mainCode shouldBe 1

        val a = File(temp, "missing-a.tachibk").path
        val b = File(temp, "missing-b.tachibk").path
        val (toolsCode, _) = runTools("backup", "compare", a, b)
        toolsCode shouldBe 1
    }

    @Test
    fun `every command turns ImageIO's temporary file cache off`() {
        val useCache = javax.imageio.ImageIO.getUseCache()
        try {
            javax.imageio.ImageIO.setUseCache(true)
            run("version")
            javax.imageio.ImageIO.getUseCache() shouldBe false

            javax.imageio.ImageIO.setUseCache(true)
            runTools("help")
            javax.imageio.ImageIO.getUseCache() shouldBe false
        } finally {
            javax.imageio.ImageIO.setUseCache(useCache)
        }
    }
}

class MainDispatcherTest {
    @Test
    fun `Dispatchers Main is the Swing dispatcher`() {
        // Reading the class name does not dispatch, so it cannot hang on a looper that is not running
        Dispatchers.Main.immediate.javaClass.name shouldContain "Swing"
    }
}

class ProcessOutputTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath

    @Test
    fun `returns the exit code and output`() {
        val output = ProcessBuilder(listOf(java) + TestHome.childJvmArguments() + "-version").redirectErrorStream(true).start()
            .outputWithin(60, TimeUnit.SECONDS)
            .shouldNotBeNull()
        output.exitCode shouldBe 0
        output.text shouldContain "version"
    }

    @Test
    fun `a process that hangs is stopped at the timeout`() {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("ping", "-n", "60", "127.0.0.1")
        } else {
            listOf("sleep", "60")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val started = System.nanoTime()
        process.outputWithin(1, TimeUnit.SECONDS) shouldBe null
        (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 30) shouldBe true
        process.waitFor(10, TimeUnit.SECONDS) shouldBe true
    }
}

/** The harness and the wiring check exit 1 when the app or an install is at fault, so scripts see a regression. */
class HarnessExitCodeTest {

    @Test
    fun `a wiring run with an app gap exits 1`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "ok")
        row.linkage += ApiLinkage.Gap("android.zoukan.Missing.call()V", "no such method")
        wiringExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a wiring run where an extension failed to load exits 1`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "install: IOException")
        wiringExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a clean wiring run exits 0`() {
        val row = ExtensionWiringCheck.Row(pkg = "en.a", version = "1", load = "ok")
        row.linkage += ApiLinkage.Gap("android.zoukan.Missing.call()V", "only the deep-link activity", activityOnly = true)
        wiringExitCode(listOf(row)) shouldBe 0
    }

    @Test
    fun `a harness run where an install failed exits 1`() {
        val row = CompatibilityHarness.Row(pkg = "en.a", install = "fail", error = "signature: bad")
        harnessExitCode(listOf(row)) shouldBe 1
    }

    @Test
    fun `a harness run where every extension installed exits 0`() {
        val row = CompatibilityHarness.Row(pkg = "en.a", install = "ok (jar)", popular = "fail: HTTP 503")
        harnessExitCode(listOf(row)) shouldBe 0
    }
}

class HarnessProfileTest {
    @TempDir
    lateinit var tempDir: File

    /** The value the build set for every test, put back afterwards. */
    private var previousDataDir: String? = null

    @BeforeEach
    fun setUp() {
        previousDataDir = System.getProperty("zoukan.dataDir")
        System.setProperty("zoukan.dataDir", File(tempDir, "data").path)
    }

    @AfterEach
    fun tearDown() {
        SingleInstance.closeAll()
        previousDataDir?.let { System.setProperty("zoukan.dataDir", it) } ?: System.clearProperty("zoukan.dataDir")
    }

    @Test
    fun `the harness profile is outside the user's data folder`() {
        val data = ZoukanDirs.default().root
        harnessDirs().root.toPath().startsWith(data.toPath()) shouldBe false
        (harnessDirs().root == data) shouldBe false
    }

    @Test
    fun `a second harness run on the same profile is refused`() {
        val held = SingleInstance.acquireForCommand(harnessDirs().root, "harness")
        (held != null) shouldBe true
        lockHarness(harnessDirs()) shouldBe null
    }

    @Test
    fun `a harness run can start again once the first one has finished`() {
        val first = lockHarness(harnessDirs())
        (first != null) shouldBe true
        first?.close()
        val second = lockHarness(harnessDirs())
        (second != null) shouldBe true
        second?.close()
    }
}
