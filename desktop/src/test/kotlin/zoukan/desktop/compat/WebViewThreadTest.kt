package zoukan.desktop.compat

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A WebView method an extension calls from a client callback that the provider runs on a Chromium
 * thread must run on the WebView's looper, because the provider is not thread-safe. Chromium is
 * not started.
 */
class WebViewThreadTest {

    class Probe {
        @JavascriptInterface
        fun ping() = "pong"
    }

    private lateinit var looper: Looper
    private lateinit var thread: Thread

    @BeforeEach
    fun startLooper() {
        val ready = CompletableFuture<Looper>()
        thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        looper = ready.get(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun stopLooper() {
        looper.quit()
        thread.join(5_000)
    }

    private fun provider(): KcefWebViewProvider {
        val view = mockk<WebView>(relaxed = true) { every { webViewLooper } returns looper }
        return KcefWebViewProvider(view)
    }

    @Suppress("UNCHECKED_CAST")
    private fun KcefWebViewProvider.mappings(): MutableList<Any> {
        val field = KcefWebViewProvider::class.java.getDeclaredField("mappings")
        field.isAccessible = true
        return field.get(this) as MutableList<Any>
    }

    @Test
    fun `a WebView call made inside an off-thread client callback runs on the WebView looper`() {
        val provider = provider()
        val ranOn = AtomicReference<Thread>()
        val probe = Probe()

        WebView.callClientOffThread {
            provider.addJavascriptInterface(probe, "probe")
            true
        }
        // Handed to the looper, not applied on the calling thread
        if (provider.mappings().isEmpty()) {
            val drained = CountDownLatch(1)
            Handler(looper).post {
                ranOn.set(Thread.currentThread())
                drained.countDown()
            }
            drained.await(5, TimeUnit.SECONDS) shouldBe true
            provider.mappings() shouldHaveSize 1
        } else {
            // Applied at once on the calling thread: the bug
            provider.mappings().shouldBeEmpty()
        }
    }

    @Test
    fun `adding an interface while a load reads the list does not throw`() {
        val provider = provider()
        val probe = Probe()
        repeat(5) { provider.addJavascriptInterface(probe, "seed$it") }
        val list = provider.mappings()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)

        val writer = Thread {
            try {
                repeat(10_000) {
                    provider.addJavascriptInterface(probe, "extra")
                    provider.removeJavascriptInterface("extra")
                }
            } catch (e: Throwable) {
                failure.compareAndSet(null, e)
            } finally {
                done.countDown()
            }
        }.apply { start() }

        try {
            while (done.count > 0L) {
                list.forEach { it.hashCode() }
            }
        } catch (e: Throwable) {
            failure.compareAndSet(null, e)
        }
        writer.join(30_000)
        failure.get() shouldBe null
    }
}
