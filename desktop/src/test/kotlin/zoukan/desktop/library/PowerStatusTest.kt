package zoukan.desktop.library

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PowerStatusTest {

    /** A process that never ends: reading its output blocks until it is destroyed. */
    private class HungProcess : Process() {
        private val ended = CountDownLatch(1)

        @Volatile var destroyed = false

        private val input = object : InputStream() {
            override fun read(): Int {
                ended.await()
                return -1
            }
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            ended.await()
            return 143
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int = if (ended.count == 0L) 143 else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
            ended.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    @Test
    fun `a power tool that never answers is given up after the timeout`() {
        val process = HungProcess()
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            PowerStatus.readOutput(process, timeoutMillis = 200) shouldBe null
        }
        process.destroyed.shouldBeTrue()
    }

    @Test
    fun `pmset output is read`() {
        PowerStatus.parsePmset("Now drawing from 'AC Power'") shouldBe true
        PowerStatus.parsePmset("Now drawing from 'Battery Power'") shouldBe false
        PowerStatus.parsePmset(null) shouldBe null
    }
}
