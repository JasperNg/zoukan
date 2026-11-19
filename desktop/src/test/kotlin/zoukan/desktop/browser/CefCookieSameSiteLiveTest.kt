package zoukan.desktop.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.browser.CefBrowser
import org.cef.browser.CefBrowserHeadless
import org.cef.browser.CefFrame
import org.cef.browser.CefRequestContext
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import xyz.nulldev.androidcompat.webkit.KcefWebViewProvider
import java.awt.Rectangle
import java.io.File
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Checks that copying the app's cookies to Chromium does not turn a SameSite=None cookie Chromium
 * already holds into a Lax one. Needs an installed Chromium but no network (example.com is only a
 * cookie address), so it runs only when asked, one case per JVM:
 *
 *   ZOUKAN_LIVE=true ZOUKAN_CEF_DIR=<data>/browser ./gradlew :desktop:test --tests "*CefCookieSameSiteLiveTest"
 */
class CefCookieSameSiteLiveTest {
    @Test
    fun `a cookie Chromium holds keeps SameSite None after the app copies its cookies`() {
        assumeTrue(System.getenv("ZOUKAN_LIVE") == "true")
        val installDir = System.getenv("ZOUKAN_CEF_DIR")?.let(::File)
        assumeTrue(installDir?.isDirectory == true)
        val cache = Files.createTempDirectory("zoukan-cef-samesite").toFile()
        val app =
            CefAppBuilder().apply {
                setInstallDir(installDir)
                cefSettings.windowless_rendering_enabled = true
                cefSettings.root_cache_path = cache.absolutePath
                cefSettings.cache_path = cache.absolutePath
            }.build()
        val client = app.createClient()
        val created = CompletableFuture<Unit>()
        client.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) {
                    created.complete(Unit)
                }
            },
        )
        val render = mockk<CefRenderHandler>(relaxed = true) { every { getViewRect(any()) } returns Rectangle(0, 0, 800, 600) }
        val browser = CefBrowserHeadless(client, "about:blank", render).apply { createImmediately() }
        val previous = CookieHandler.getDefault()
        try {
            created.get(60, TimeUnit.SECONDS)
            val expires = System.currentTimeMillis() / 1000 + 3600
            browser.devToolsClient
                .executeDevToolsMethod(
                    "Network.setCookie",
                    """{"name":"zk_samesite","value":"1","url":"https://example.com/","secure":true,"sameSite":"None","expires":$expires}""",
                ).get(10, TimeUnit.SECONDS)

            val manager = java.net.CookieManager()
            CookieHandler.setDefault(manager)
            manager.cookieStore.add(
                URI("https://example.com/"),
                HttpCookie("zk_samesite", "1").apply {
                    path = "/"
                    secure = true
                    maxAge = 3600
                },
            )
            CefCookieSync.copyToCef()

            // Chromium applies the copy on its own thread: look at it several times
            var sameSite: String? = null
            val deadline = System.currentTimeMillis() + 2_000
            do {
                Thread.sleep(250)
                val reply =
                    browser.devToolsClient
                        .executeDevToolsMethod("Network.getCookies", """{"urls":["https://example.com/"]}""")
                        .get(10, TimeUnit.SECONDS)
                sameSite =
                    Json.parseToJsonElement(reply).jsonObject["cookies"]?.jsonArray
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "zk_samesite" }
                        ?.get("sameSite")?.jsonPrimitive?.content
            } while (System.currentTimeMillis() < deadline)
            check(sameSite == "None") { "SameSite was $sameSite" }
        } finally {
            CookieHandler.setDefault(previous)
            browser.close(true)
            client.dispose()
            app.dispose()
        }
    }
}

/**
 * Checks that Chromium resolves names with the DNS over HTTPS server ChromiumDns writes to its
 * Local State, in secure mode (no fallback to the system's DNS). Needs an installed Chromium and
 * the internet, so it runs only when asked, one case per JVM (Chromium starts once per process):
 *
 *   ZOUKAN_LIVE=true ZOUKAN_CEF_DIR=<data>/browser ZOUKAN_CEF_TEMPLATE=<url> ./gradlew :desktop:test --tests "*ChromiumDohLiveTest"
 *
 * With an unreachable template (https://127.0.0.1:9/dns-query) the page must fail to resolve,
 * proving Chromium neither ignores the preference nor falls back; with a provider's it must load.
 *
 * Chromium runs with the app's privacy switches and preferences (JcefRuntime.PRIVACY_SWITCHES,
 * Safe Browsing off) unless ZOUKAN_CEF_PRIVACY=off, and a loaded page must still run JavaScript
 * (as a Cloudflare challenge does): a script sets document.title, read back through the display handler.
 */
class ChromiumDohLiveTest {
    @Test
    fun `Chromium resolves through the Local State DoH server`() {
        assumeTrue(System.getenv("ZOUKAN_LIVE") == "true")
        val installDir = System.getenv("ZOUKAN_CEF_DIR")?.let(::File)
        assumeTrue(installDir?.isDirectory == true)
        val template = System.getenv("ZOUKAN_CEF_TEMPLATE")?.takeIf { it != "off" }
        val url = System.getenv("ZOUKAN_CEF_URL") ?: "https://example.com/"
        val cache = Files.createTempDirectory("zoukan-cef-doh").toFile()
        val privacy = System.getenv("ZOUKAN_CEF_PRIVACY") != "off"
        val safeBrowsing = CompletableFuture<Any?>()
        // As JcefRuntime does before Chromium starts
        ChromiumDns.updateLocalState(null, template)?.let { File(cache, "Local State").writeText(it) }

        val app = CefAppBuilder().apply {
            setInstallDir(installDir)
            cefSettings.windowless_rendering_enabled = true
            cefSettings.root_cache_path = cache.absolutePath
            cefSettings.cache_path = cache.absolutePath
            // Extra switches, separated by "|" (for --host-resolver-rules, whose value has spaces)
            System.getenv("ZOUKAN_CEF_ARGS")?.split('|')?.filter { it.isNotBlank() }?.let { addJcefArgs(*it.toTypedArray()) }
            if (privacy) addJcefArgs(*JcefRuntime.PRIVACY_SWITCHES.toTypedArray())
            setAppHandler(
                object : MavenCefAppHandlerAdapter() {
                    override fun onContextInitialized() {
                        if (privacy) JcefRuntime.applyPrivacyPreferences()
                        safeBrowsing.complete(
                            runCatching { CefRequestContext.getGlobalContext().getPreference("safebrowsing.enabled") }
                                .getOrElse { "error: $it" },
                        )
                    }
                },
            )
        }.build()
        val result = CompletableFuture<String>()
        val title = CompletableFuture<String>()
        val client = app.createClient()
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser, newTitle: String?) {
                if (newTitle?.startsWith("zk-") == true) title.complete(newTitle)
            }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) result.complete("LOADED HTTP $httpStatusCode")
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String?,
                failedUrl: String?,
            ) {
                if (frame.isMain) result.complete("ERROR $errorCode $errorText")
            }

            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {}
        })
        val render = mockk<CefRenderHandler>(relaxed = true) { every { getViewRect(any()) } returns Rectangle(0, 0, 800, 600) }
        val browser = CefBrowserHeadless(client, url, render).apply { createImmediately() }
        val outcome = runCatching { result.get(60, TimeUnit.SECONDS) }.getOrElse { "TIMEOUT ${it::class.simpleName}" }
        val script = if (outcome.startsWith("LOADED")) {
            browser.executeJavaScript("document.title = 'zk-' + (6 * 7);", browser.url, 0)
            runCatching { title.get(15, TimeUnit.SECONDS) }.getOrElse { "TIMEOUT ${it::class.simpleName}" }
        } else {
            null
        }
        println("CHROMIUM-DOH privacy=$privacy safebrowsing.enabled=${runCatching { safeBrowsing.get(5, TimeUnit.SECONDS) }.getOrNull()} script title=$script")
        val localState = File(cache, "Local State").takeIf { it.isFile }?.readText().orEmpty()
        println("CHROMIUM-DOH template=$template args=${System.getenv("ZOUKAN_CEF_ARGS")} url=$url -> $outcome")
        println("CHROMIUM-DOH Local State dns_over_https: " + Regex("\"dns_over_https\":\\{[^}]*\\}").find(localState)?.value)
        browser.close(true)
        client.dispose()
        val expect = System.getenv("ZOUKAN_CEF_EXPECT")
        if (expect != null) {
            check(outcome.contains(expect)) { outcome }
        } else if (template == null || !template.contains("127.0.0.1")) {
            check(outcome.startsWith("LOADED")) { outcome }
            check(script == "zk-42") { "JavaScript did not run: $script" }
            if (privacy) check(safeBrowsing.get(5, TimeUnit.SECONDS) == false) { "Safe Browsing is on" }
        } else {
            check(outcome.contains("NAME_NOT_RESOLVED")) { "Chromium did not use the DoH server: $outcome" }
        }
    }
}

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
