package zoukan.desktop.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.browser.CefBrowserHeadless
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.awt.Rectangle
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A page with one iframe and one image, loaded in Chromium with the provider's own handlers: the
 * extension's WebViewClient must see onPageFinished once, for the page, and onLoadResource with the
 * image's URL. Needs an installed Chromium, so it runs only when asked (Chromium starts once per
 * process):
 *
 *   ZOUKAN_LIVE=true ZOUKAN_CEF_DIR=<data>/browser ./gradlew :desktop:test --tests "*WebViewFrameCallbacksLiveTest"
 */
class WebViewFrameCallbacksLiveTest {
    @Test
    fun `an iframe finishing does not finish the page`() {
        assumeTrue(System.getenv("ZOUKAN_LIVE") == "true")
        val installDir = System.getenv("ZOUKAN_CEF_DIR")?.let(::File)
        assumeTrue(installDir?.isDirectory == true)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/page" -> "<html><body><iframe src=\"/frame\"></iframe><img src=\"/image.png\"></body></html>"
                "/frame" -> "<html><body>frame</body></html>"
                else -> ""
            }.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/image.png") 404 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"

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
        val finished = CopyOnWriteArrayList<String>()
        val resources = CopyOnWriteArrayList<String>()
        val firstFinish = CompletableFuture<String>()
        val view = mockk<WebView>(relaxed = true) { every { webViewLooper } returns looper }
        val provider = KcefWebViewProvider(view)
        provider.setWebViewClient(
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    finished += url.orEmpty()
                    firstFinish.complete(url.orEmpty())
                }

                override fun onLoadResource(
                    view: WebView?,
                    url: String?,
                ) {
                    resources += url.orEmpty()
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {}
            },
        )

        val cache = Files.createTempDirectory("zoukan-cef-frames").toFile()
        val app = CefAppBuilder().apply {
            setInstallDir(installDir)
            cefSettings.windowless_rendering_enabled = true
            cefSettings.root_cache_path = cache.absolutePath
            cefSettings.cache_path = cache.absolutePath
        }.build()
        val client = app.createClient()
        fun field(name: String) = KcefWebViewProvider::class.java.getDeclaredField(name).apply { isAccessible = true }.get(provider)
        client.addLoadHandler(field("loadHandler") as CefLoadHandlerAdapter)
        client.addRequestHandler(field("requestHandler") as CefRequestHandlerAdapter)
        val render = mockk<CefRenderHandler>(relaxed = true) { every { getViewRect(any()) } returns Rectangle(0, 0, 800, 600) }
        val browser = CefBrowserHeadless(client, "$base/page", render).apply { createImmediately() }

        try {
            runCatching { firstFinish.get(60, TimeUnit.SECONDS) }
            // Give a late iframe callback time to arrive
            val settled = CompletableFuture<Unit>()
            Handler(looper).postDelayed({ settled.complete(Unit) }, 2_000)
            settled.get(10, TimeUnit.SECONDS)
            check(finished == listOf("$base/page")) { "onPageFinished calls: $finished" }
            check(resources.any { it == "$base/image.png" }) { "onLoadResource URLs: $resources" }
        } finally {
            browser.close(true)
            client.dispose()
            looper.quit()
            thread.join(5_000)
            server.stop(0)
        }
    }
}
