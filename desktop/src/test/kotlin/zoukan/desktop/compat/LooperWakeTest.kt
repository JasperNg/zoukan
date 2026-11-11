package zoukan.desktop.compat

import android.os.Handler
import android.os.Looper
import android.os.shadows.ShadowPausedMessageQueue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
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
}
