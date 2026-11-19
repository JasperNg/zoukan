package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.ChallengeFailures
import eu.kanade.tachiyomi.network.interceptor.DesktopBrowser
import eu.kanade.tachiyomi.network.interceptor.HostLocks
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import zoukan.desktop.testing.Await
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Cloudflare challenge serialization, cooldowns, and browser clearance URL filtering. */
class BrowserChallengeAndClearanceTest {
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
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        val asked = AtomicInteger(0)
        DesktopBrowser.challengeSolver = DesktopBrowser.ChallengeSolver { _, _, _ ->
            windows.incrementAndGet()
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            // The window stays open until every other challenge has arrived and waits for the host:
            // a thread that asked and is parked can only be waiting for the host's lock
            Await.until("the other challenges to wait for the host", timeoutMillis = 5_000) {
                asked.get() == 8 && threads.filter { it !== Thread.currentThread() }.all { it.state == Thread.State.WAITING }
            }
            solved.set(true)
            concurrent.decrementAndGet()
            true
        }
        val pool = Executors.newFixedThreadPool(8) { task -> Thread(task).also { threads += it } }
        val start = CountDownLatch(1)
        val results = (1..8).map {
            pool.submit<Boolean> {
                start.await()
                asked.incrementAndGet()
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

    @Test
    fun `a challenge the user closed is not shown again within the cooldown`() {
        val minute = TimeUnit.MINUTES.toNanos(1)
        val failures = ChallengeFailures(cooldownNanos = 5 * minute)
        failures.failed("example.org", now = 0)
        // A request that starts after the failure, a minute later
        failures.shouldFailFast("example.org", waitStarted = minute, now = minute) shouldBe true
        // After the cooldown a new attempt is allowed again
        failures.shouldFailFast("example.org", waitStarted = 6 * minute, now = 6 * minute) shouldBe false
        // Another host is not affected
        failures.shouldFailFast("other.org", waitStarted = minute, now = minute) shouldBe false
    }

    @Test
    fun `pages without cookies are skipped`() {
        for (url in listOf("about:blank", "data:text/html,hi", "chrome://version", "file:///tmp/a.html", "", "not a url")) {
            clearanceUrlOrNull(url) shouldBe null
        }
    }

    @Test
    fun `http and https pages are kept`() {
        clearanceUrlOrNull("https://example.com/a")?.host shouldBe "example.com"
        clearanceUrlOrNull("http://example.com/")?.host shouldBe "example.com"
    }

    @Test
    fun `about blank returns before touching the app's services`() {
        // Injekt has nothing registered here: reaching it would log a WARN with a stack trace (it
        // never throws), so this checks only that the call returns quietly
        recordClearanceUserAgent("about:blank", null)
    }
}
