package zoukan.desktop.compat

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.shadows.ShadowPausedMessageQueue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The emulated Android looper must not lose a message posted while its loop is between checking
 * the queue and waiting. Such a lost wake left the loop asleep with the message queued: on the
 * main looper that froze whatever coroutine resumed through Dispatchers.Main.
 */
class LooperWakeTest {

    @Test
    fun `a wake before the poll is kept`() {
        val ptr = ShadowPausedMessageQueue.nativeInit()
        try {
            ShadowPausedMessageQueue.nativeWake(ptr)
            val polled = CompletableFuture.runAsync { ShadowPausedMessageQueue.nativePollOnce(ptr, -1) }
            runCatching { polled.get(5, TimeUnit.SECONDS) }.isSuccess shouldBe true
        } finally {
            ShadowPausedMessageQueue.nativeDestroy(ptr)
        }
    }

    @Test
    fun `messages posted from another thread always run`() {
        val ready = CompletableFuture<Looper>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        val handler = Handler(looper)
        try {
            repeat(20_000) {
                val ran = CountDownLatch(1)
                handler.post { ran.countDown() }
                ran.await(5, TimeUnit.SECONDS) shouldBe true
            }
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `HandlerThread quit ends the thread`() {
        val thread = HandlerThread("probe").apply { start() }
        (thread.looper != null) shouldBe true
        thread.quit() shouldBe true
        thread.join(5_000)
        thread.isAlive shouldBe false
    }

    @Test
    fun `an interrupted HandlerThread ends`() {
        val thread = HandlerThread("probe-interrupt").apply { start() }
        try {
            val ran = CountDownLatch(1)
            Handler(thread.looper).post { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            thread.interrupt()
            thread.join(5_000)
            thread.isAlive shouldBe false
        } finally {
            thread.quit()
        }
    }

    @Test
    fun `a HandlerThread interrupted before its looper is ready still ends`() {
        repeat(200) { i ->
            val thread = HandlerThread("probe-early-interrupt-$i").apply { start() }
            thread.interrupt()
            thread.join(5_000)
            thread.isAlive shouldBe false
        }
    }

    @Test
    fun `an interrupt does not make the looper spin`() {
        val ready = CompletableFuture<Looper>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        val handler = Handler(looper)
        try {
            thread.interrupt()
            repeat(100) {
                val ran = CountDownLatch(1)
                handler.post { ran.countDown() }
                ran.await(5, TimeUnit.SECONDS) shouldBe true
            }
            val mx = ManagementFactory.getThreadMXBean()
            val before = mx.getThreadCpuTime(thread.id)
            Thread.sleep(1_000)
            val usedMillis = (mx.getThreadCpuTime(thread.id) - before) / 1_000_000
            (usedMillis < 200) shouldBe true
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }
}
