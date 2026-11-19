package zoukan.desktop.ui.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Skia's decodes are bounded by the pixels in flight, not only per page: ordinary pages keep
 * decoding side by side, a large page decodes alone, so a few large pages at once cannot take
 * several GiB of native memory.
 */
class DecodeGateTest {

    @Test
    fun `ordinary pages decode side by side`() {
        val gate = DecodeGate(capacity = 100)
        repeat(4) { gate.tryAcquire(gate.weightOf(20)) shouldBe true }
        gate.inUse shouldBe 80
    }

    @Test
    fun `a page that does not fit beside the others waits`() {
        val gate = DecodeGate(capacity = 100)
        gate.tryAcquire(gate.weightOf(60)) shouldBe true
        gate.tryAcquire(gate.weightOf(60)) shouldBe false
        gate.release(gate.weightOf(60))
        gate.tryAcquire(gate.weightOf(60)) shouldBe true
    }

    @Test
    fun `the largest page still decodes, alone`() {
        val gate = DecodeGate(capacity = 100)
        // Larger than the gate (or of unknown size): it takes the whole gate, never more
        gate.weightOf(10_000) shouldBe 100
        gate.weightOf(null) shouldBe 100
        gate.weightOf(0) shouldBe 1
        gate.tryAcquire(gate.weightOf(10_000)) shouldBe true
        gate.tryAcquire(gate.weightOf(1)) shouldBe false
    }

    @Test
    fun `the gate's numbers for real pages`() {
        val gate = DecodeGate(capacity = PageImage.MAX_DECODED_PIXELS)
        // Three of the largest strips seen (about 77 million pixels) at once, not four
        repeat(3) { gate.tryAcquire(gate.weightOf(1080L * 71_500)) shouldBe true }
        gate.tryAcquire(gate.weightOf(1080L * 71_500)) shouldBe false
    }

    @Test
    fun `a waiting large page is not overtaken by smaller ones`() {
        val gate = DecodeGate(capacity = 100)
        gate.acquire(gate.weightOf(50))
        val largeIn = AtomicBoolean(false)
        val large = Thread {
            gate.acquire(gate.weightOf(100))
            largeIn.set(true)
            gate.release(gate.weightOf(100))
        }.apply { start() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (large.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        large.state shouldBe Thread.State.WAITING
        // A small page would fit beside the first, but the large one asked first
        gate.tryAcquire(gate.weightOf(10)) shouldBe false
        largeIn.get() shouldBe false

        gate.release(gate.weightOf(50))
        large.join()
        largeIn.get() shouldBe true
        gate.inUse shouldBe 0
        gate.tryAcquire(gate.weightOf(10)) shouldBe true
    }

    @Test
    fun `a decode that fails gives its share back`() {
        val gate = DecodeGate(capacity = 100)
        runCatching { gate.withPermit(100) { throw IllegalStateException("bad page") } }
        gate.inUse shouldBe 0
        val done = CountDownLatch(1)
        gate.withPermit(100) { done.countDown() }
        done.count shouldBe 0
    }

    @Test
    fun `a decode whose thread is interrupted by the time it gets its turn is skipped`() {
        val gate = DecodeGate(capacity = 100)
        val decoded = AtomicBoolean(false)
        Thread.currentThread().interrupt()
        val skipped = runCatching { gate.withPermit(10) { decoded.set(true) } }.exceptionOrNull()
        Thread.interrupted()

        (skipped is InterruptedException) shouldBe true
        decoded.get() shouldBe false
        gate.inUse shouldBe 0
    }

    @Test
    fun `a load cancelled while it waits at the gate leaves the queue and never decodes`() = kotlinx.coroutines.runBlocking<Unit> {
        val gate = DecodeGate(capacity = 100)
        gate.acquire(100)
        val decoded = AtomicBoolean(false)
        val waiting = java.util.concurrent.atomic.AtomicReference<Thread>()
        // As the reader loads a page: the blocking decode runs interruptibly, so a cancel reaches it
        val load = launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.runInterruptible {
                waiting.set(Thread.currentThread())
                gate.withPermit(10) { decoded.set(true) }
            }
        }
        zoukan.desktop.testing.Await.until("the load to wait at the gate") { waiting.get()?.state == Thread.State.WAITING }

        load.cancel()
        load.join()
        gate.release(100)

        decoded.get() shouldBe false
        gate.inUse shouldBe 0
        // Its place in the queue is given up: the next decode goes ahead at once
        gate.tryAcquire(10) shouldBe true
    }
}
