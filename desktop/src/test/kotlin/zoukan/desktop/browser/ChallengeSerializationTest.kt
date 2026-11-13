package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import eu.kanade.tachiyomi.network.interceptor.HostLocks
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Parallel Cloudflare challenges for one host open one window, not one each. */
class ChallengeSerializationTest {
    private val previous = DesktopBrowser.challengeSolver

    @AfterEach
    fun restore() {
        DesktopBrowser.challengeSolver = previous
    }

    @Test
    fun `parallel challenges for one host show one window`() {
        val solved = AtomicBoolean(false)
        val windows = AtomicInteger(0)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        DesktopBrowser.challengeSolver = DesktopBrowser.ChallengeSolver { _, _, _ ->
            windows.incrementAndGet()
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            Thread.sleep(100)
            solved.set(true)
            concurrent.decrementAndGet()
            true
        }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map {
            pool.submit<Boolean> {
                start.await()
                DesktopBrowser.solveChallenge("https://example.org/page$it", "UA") { solved.get() }
            }
        }
        start.countDown()
        results.map { it.get(10, TimeUnit.SECONDS) } shouldBe List(8) { true }
        pool.shutdown()

        windows.get() shouldBe 1
        maxConcurrent.get() shouldBe 1
    }

    @Test
    fun `different hosts are not serialized`() {
        val locks = HostLocks()
        val inA = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            locks.withLock("a.example") {
                inA.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        inA.await(5, TimeUnit.SECONDS) shouldBe true
        // Another host gets its lock while a.example is held
        locks.withLock("b.example") { "done" } shouldBe "done"
        release.countDown()
        thread.join(5_000)
    }
}
