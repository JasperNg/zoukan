package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CookieVisitEnd
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The WebView calls onPageFinished once Chromium's cookies for the page are copied to the app;
 * Chromium does not say when a visit with no cookies ends, so a quiet time stands for it.
 */
class CookieVisitEndTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val calls = AtomicInteger()
    private val called = CountDownLatch(1)
    private val onDone = {
        calls.incrementAndGet()
        called.countDown()
    }

    @AfterEach
    fun tearDown() {
        scheduler.shutdownNow()
    }

    @Test
    fun `the visit ends after its last cookie, once`() {
        val end = CookieVisitEnd(onDone, scheduler, quietMillis = 10_000, maxMillis = 10_000)
        end.visited(0, 2)
        calls.get() shouldBe 0
        end.visited(1, 2)
        calls.get() shouldBe 1
        end.finish()
        calls.get() shouldBe 1
    }

    @Test
    fun `a visit with no cookies ends after the quiet time`() {
        CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 10_000)
        called.await(5, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(50)
        calls.get() shouldBe 1
    }

    @Test
    fun `a visit under way is not ended by the quiet time, only by the limit`() {
        val end = CookieVisitEnd(onDone, scheduler, quietMillis = 20, maxMillis = 300)
        end.visited(0, 3)
        Thread.sleep(100)
        calls.get() shouldBe 0
        called.await(5, TimeUnit.SECONDS) shouldBe true
        end.visited(1, 3)
        end.visited(2, 3)
        calls.get() shouldBe 1
    }
}
