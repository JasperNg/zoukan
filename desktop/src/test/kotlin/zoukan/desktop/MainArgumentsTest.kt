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

    private fun run(vararg args: String): Pair<Int, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            val code = runCommand(arrayOf(*args))
            return code to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

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
        val (code, err) = run("harness", "--store")
        code shouldBe 2
        err shouldContain "Usage:"
    }

    @Test
    fun `a command that throws returns 1 so the shutdown path runs`() {
        val a = File(temp, "missing-a.tachibk").path
        val b = File(temp, "missing-b.tachibk").path
        val (code, _) = run("backup", "compare", a, b)
        code shouldBe 1
    }
}
