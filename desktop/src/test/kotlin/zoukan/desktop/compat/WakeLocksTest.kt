package zoukan.desktop.compat

import android.content.Context
import exh.util.createPartialWakeLock
import exh.util.holdSystemAwake
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.reader.KeepAwake
import java.util.concurrent.atomic.AtomicInteger

class WakeLocksTest {
    private val held = AtomicInteger()
    private val released = AtomicInteger()
    private val original = holdSystemAwake

    @BeforeEach
    fun fake() {
        holdSystemAwake = {
            held.incrementAndGet()
            KeepAwake.Lock { released.incrementAndGet() }
        }
    }

    @AfterEach
    fun restore() {
        holdSystemAwake = original
    }

    @Test
    fun `a partial wake lock keeps the computer awake until it is released`() {
        val lock = mockk<Context>(relaxed = true).createPartialWakeLock("sync")
        lock.isHeld shouldBe true
        held.get() shouldBe 1
        lock.release()
        lock.isHeld shouldBe false
        released.get() shouldBe 1
        lock.release() // a second release, as the sync may do, is ignored
        released.get() shouldBe 1
    }
}
