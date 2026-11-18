package zoukan.desktop.compat

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.shadows.ShadowPausedMessageQueue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.testing.Await
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
    fun `an Error thrown by a message does not end the loop`() {
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
            // What an extension built against a newer API throws from a callback
            handler.post { throw NoSuchMethodError("probe") }
            handler.post { throw StackOverflowError("probe") }
            val ran = CountDownLatch(1)
            handler.post { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            thread.isAlive shouldBe true
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `an OutOfMemoryError from a message still ends the loop, for the crash reporter`() {
        val ready = CompletableFuture<Looper>()
        val thrown = CompletableFuture<Throwable>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> thrown.complete(e) }
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        Handler(looper).post { throw OutOfMemoryError("probe") }
        (thrown.get(5, TimeUnit.SECONDS) is OutOfMemoryError) shouldBe true
        thread.join(5_000)
        thread.isAlive shouldBe false
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
            // The looper has waited between those messages, and the first wait consumed the interrupt.
            // Restored after each wait, it would end every later wait at once (the spin), and a
            // message would run with it still set
            val interrupted = CompletableFuture<Boolean>()
            handler.post { interrupted.complete(Thread.currentThread().isInterrupted) }
            interrupted.get(5, TimeUnit.SECONDS) shouldBe false
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `an interrupted poll clears the interrupt, so the next poll waits for a wake`() {
        val ptr = ShadowPausedMessageQueue.nativeInit()
        try {
            val polling = Thread {
                Thread.currentThread().interrupt()
                // The interrupt ends this one, as on Android
                ShadowPausedMessageQueue.nativePollOnce(ptr, -1)
                ShadowPausedMessageQueue.nativePollOnce(ptr, -1)
            }.apply {
                isDaemon = true
                start()
            }
            // Restored, the interrupt would end the second poll at once too, and every later one: a
            // looper that spins
            Await.until("the poll to wait", state = { polling.state }) {
                polling.state == Thread.State.WAITING || !polling.isAlive
            }
            polling.isAlive shouldBe true
            polling.isInterrupted shouldBe false
            ShadowPausedMessageQueue.nativeWake(ptr)
            polling.join(5_000)
            polling.isAlive shouldBe false
        } finally {
            ShadowPausedMessageQueue.nativeDestroy(ptr)
        }
    }
}
