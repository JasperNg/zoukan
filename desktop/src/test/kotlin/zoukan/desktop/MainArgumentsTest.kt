package zoukan.desktop

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

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
