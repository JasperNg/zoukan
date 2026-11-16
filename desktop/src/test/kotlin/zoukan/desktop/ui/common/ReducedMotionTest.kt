package zoukan.desktop.ui.common

import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import zoukan.desktop.Platform
import java.util.concurrent.TimeUnit

class ReducedMotionTest {
    private val windows get() = Platform.current == Platform.WINDOWS

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a helper that hangs gives no answer within the time limit`() {
        // Keeps its output open and writes nothing for 30 s
        val hang = if (windows) arrayOf("cmd", "/c", "ping -n 30 127.0.0.1 >NUL") else arrayOf("sleep", "30")
        val started = System.nanoTime()
        ReducedMotion.run(*hang, timeoutSeconds = 1) shouldBe null
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        seconds shouldBeLessThan 5L
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `a helper that answers is read`() {
        val echo = if (windows) arrayOf("cmd", "/c", "echo 1") else arrayOf("echo", "1")
        ReducedMotion.run(*echo) shouldBe "1"
    }
}
