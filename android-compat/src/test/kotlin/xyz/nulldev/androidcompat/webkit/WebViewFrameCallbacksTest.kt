package xyz.nulldev.androidcompat.webkit

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What an extension's WebViewClient sees from the provider, as on Android: the page callbacks come
 * from the main frame only (an iframe such as Turnstile or an ad finishing is not the page
 * finishing), onLoadResource gets the resource's URL, and shouldOverrideUrlLoading is asked about
 * navigations, not about every image or script. Chromium is not started: the provider's handlers
 * are called directly.
 */
class WebViewFrameCallbacksTest {

    private lateinit var looper: Looper
    private lateinit var thread: Thread
    private lateinit var handler: Handler
    private lateinit var provider: KcefWebViewProvider
    private val events = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
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
        handler = Handler(looper)
        val view = mockk<WebView>(relaxed = true) { every { webViewLooper } returns looper }
        provider = KcefWebViewProvider(view)
        provider.setWebViewClient(
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    events += "started $url"
                }

                override fun onLoadResource(
                    view: WebView?,
                    url: String?,
                ) {
                    events += "resource $url"
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    events += "override"
                    return false
                }
            },
        )
    }

    @AfterEach
    fun tearDown() {
        looper.quit()
        thread.join(5_000)
    }

    private fun drain() {
        val done = CountDownLatch(1)
        handler.post { done.countDown() }
        done.await(5, TimeUnit.SECONDS) shouldBe true
    }

    private fun field(name: String): Any =
        KcefWebViewProvider::class.java.getDeclaredField(name).apply { isAccessible = true }.get(provider)

    private fun frame(
        main: Boolean,
        address: String,
    ): CefFrame = mockk<CefFrame>(relaxed = true) {
        every { isMain } returns main
        every { url } returns address
    }

    private fun request(address: String): CefRequest = mockk<CefRequest>(relaxed = true) {
        every { url } returns address
        every { method } returns "GET"
    }

    private fun resourceHandler(
        frame: CefFrame,
        request: CefRequest,
        isNavigation: Boolean,
    ) = (field("requestHandler") as CefRequestHandlerAdapter).getResourceRequestHandler(
        mockk<CefBrowser>(relaxed = true),
        frame,
        request,
        isNavigation,
        false,
        "",
        BoolRef(),
    )

    @Test
    fun `an iframe load does not start the page`() {
        val load = field("loadHandler") as CefLoadHandlerAdapter
        load.onLoadStart(mockk<CefBrowser>(relaxed = true), frame(false, "http://127.0.0.1/frame"), CefRequest.TransitionType.TT_EXPLICIT)
        drain()
        events.shouldBeEmpty()
    }

    @Test
    fun `the main frame load starts the page`() {
        val load = field("loadHandler") as CefLoadHandlerAdapter
        load.onLoadStart(mockk<CefBrowser>(relaxed = true), frame(true, "http://127.0.0.1/page"), CefRequest.TransitionType.TT_EXPLICIT)
        drain()
        events.toList() shouldBe listOf("started http://127.0.0.1/page")
    }

    @Test
    fun `onLoadResource gets the resource URL`() {
        val page = frame(true, "http://127.0.0.1/page")
        val image = request("http://127.0.0.1/image.png")
        resourceHandler(page, image, isNavigation = false).onBeforeResourceLoad(mockk<CefBrowser>(relaxed = true), page, image)
        drain()
        events.toList() shouldBe listOf("resource http://127.0.0.1/image.png")
    }

    @Test
    fun `a subresource does not ask shouldOverrideUrlLoading`() {
        val page = frame(true, "http://127.0.0.1/page")
        val image = request("http://127.0.0.1/image.png")
        resourceHandler(page, image, isNavigation = false).onBeforeResourceLoad(mockk<CefBrowser>(relaxed = true), page, image)
        drain()
        events.contains("override") shouldBe false
    }

    @Test
    fun `a navigation still asks shouldOverrideUrlLoading`() {
        val page = frame(true, "http://127.0.0.1/page")
        val next = request("http://127.0.0.1/next")
        resourceHandler(page, next, isNavigation = true).onBeforeResourceLoad(mockk<CefBrowser>(relaxed = true), page, next)
        drain()
        events.contains("override") shouldBe true
    }
}
