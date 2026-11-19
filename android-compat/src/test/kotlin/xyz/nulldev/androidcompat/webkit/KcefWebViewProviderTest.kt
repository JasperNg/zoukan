package xyz.nulldev.androidcompat.webkit

import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A WebView that an extension destroyed (often from a timeout path) ignores later calls, as on
 * Android, instead of throwing on the main looper. Chromium is not started.
 */
class KcefWebViewProviderTest {

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

    @Test
    fun `calls after destroy are ignored`() {
        val provider = provider()
        provider.destroy()

        provider.loadUrl("http://127.0.0.1/", emptyMap())
        provider.loadUrl("http://127.0.0.1/")
        provider.postUrl("http://127.0.0.1/", ByteArray(0))
        provider.loadDataWithBaseURL(null, "<p/>", "text/html", "utf-8", null)
        provider.evaluateJavaScript("1", null)
        provider.stopLoading()
        provider.reload()
        provider.goBack()
        provider.goForward()

        provider.canGoBack() shouldBe false
        provider.canGoForward() shouldBe false
        provider.url shouldBe null
        provider.originalUrl shouldBe null
    }

    @Test
    fun `evaluateJavascript after destroy does not call back`() {
        val provider = provider()
        provider.destroy()
        val calls = CopyOnWriteArrayList<String?>()
        provider.evaluateJavaScript("1", ValueCallback { calls.add(it) })

        val drained = CountDownLatch(1)
        android.os.Handler(looper).post { drained.countDown() }
        drained.await(5, TimeUnit.SECONDS) shouldBe true
        calls.shouldBeEmpty()
    }
}
