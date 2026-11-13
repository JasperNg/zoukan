package zoukan.desktop.backup

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Restores run alone, and the work that reads the library never overlaps one. */
class RestoreGateTest {

    @Test
    fun `two restores never run at the same time`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val most = AtomicInteger()
        withTimeout(10_000) {
            (1..4).map {
                async(Dispatchers.Default) {
                    RestoreGate.whileRestoring {
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(50)
                        // Still up while another restore waits its turn
                        RestoreGate.isRestoring shouldBe true
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        most.get() shouldBe 1
        RestoreGate.isRestoring shouldBe false
    }

    @Test
    fun `background work does not start while a restore runs`() = runBlocking<Unit> {
        RestoreGate.whileRestoring {
            RestoreGate.unlessRestoring { "ran" } shouldBe null
        }
        RestoreGate.unlessRestoring { "ran" } shouldBe "ran"
    }

    @Test
    fun `a restore waits for background work that already started`() = runBlocking<Unit> {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restored = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.Default) {
            RestoreGate.unlessRestoring {
                inside.complete(Unit)
                release.await()
            }
        }
        withTimeout(10_000) { inside.await() }
        val restore = launch(Dispatchers.Default) { RestoreGate.whileRestoring { restored.complete(Unit) } }
        delay(200)
        // Asked for, so no new work starts, but it waits for the work under way
        RestoreGate.isRestoring shouldBe true
        restored.isCompleted shouldBe false
        RestoreGate.unlessRestoring { "ran" } shouldBe null

        release.complete(Unit)
        withTimeout(10_000) {
            worker.join()
            restore.join()
        }
        restored.isCompleted shouldBe true
        RestoreGate.isRestoring shouldBe false
    }
}
