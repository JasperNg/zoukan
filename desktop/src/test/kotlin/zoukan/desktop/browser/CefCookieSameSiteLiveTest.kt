package zoukan.desktop.browser

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.browser.CefBrowser
import org.cef.browser.CefBrowserHeadless
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRenderHandler
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import java.awt.Rectangle
import java.io.File
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
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
