package zoukan.desktop

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ProcessOutputTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath

    @Test
    fun `returns the exit code and output`() {
        val output = ProcessBuilder(java, "-version").redirectErrorStream(true).start()
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
